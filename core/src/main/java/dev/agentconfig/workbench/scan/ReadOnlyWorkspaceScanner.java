package dev.agentconfig.workbench.scan;

import dev.agentconfig.workbench.host.ArtifactType;
import dev.agentconfig.workbench.host.HostMatch;
import dev.agentconfig.workbench.host.HostRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Metadata-only scanner. It has no write API and never follows symbolic links.
 * Discovered content is hashed and sampled as inert bytes, never interpreted or executed.
 */
public final class ReadOnlyWorkspaceScanner {
    private final HostRegistry registry;
    private final ScanLimits limits;

    public ReadOnlyWorkspaceScanner(HostRegistry registry, ScanLimits limits) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public static ReadOnlyWorkspaceScanner phaseOneDefaults() {
        return new ReadOnlyWorkspaceScanner(HostRegistry.phaseOneDefaults(), ScanLimits.defaults());
    }

    public ScanResult scan(Path authorizedRoot) throws IOException {
        return scan(authorizedRoot, ScanCancellation.neverCancelled());
    }

    public ScanResult scan(Path authorizedRoot, ScanCancellation cancellation) throws IOException {
        Objects.requireNonNull(authorizedRoot, "authorizedRoot");
        Objects.requireNonNull(cancellation, "cancellation");
        Path logicalRoot = authorizedRoot.toAbsolutePath().normalize();
        Path realRoot = logicalRoot.toRealPath();
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Authorized root is not a directory: " + logicalRoot);
        }

        List<DiscoveredArtifact> artifacts = new ArrayList<>();
        List<ScanFinding> findings = new ArrayList<>();
        List<FileCandidate> candidates = new ArrayList<>();
        Set<Path> visitedRealDirectories = new LinkedHashSet<>();
        ScanProgress progress = new ScanProgress();
        StopState stop = new StopState();

        FileVisitor<Path> visitor = new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (stopIfCancelled(cancellation, stop, findings)) {
                    return FileVisitResult.TERMINATE;
                }
                if (incrementAndLimit(progress, stop, findings, realRoot, directory)) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = realRoot.relativize(directory);
                if (!relative.toString().isEmpty() && !registry.match(relative, false).isEmpty()) {
                    findings.add(finding(Severity.BLOCKING, FindingCode.UNSUPPORTED_SPECIAL_FILE,
                            relative, "An allowlisted file path is a directory; its subtree was skipped"));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    Path resolved = directory.toRealPath();
                    if (!resolved.startsWith(realRoot)) {
                        findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_ESCAPE,
                                display(relative), "Directory resolves outside the approved root"));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!visitedRealDirectories.add(resolved)) {
                        findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_CYCLE,
                                display(relative), "Directory resolves to an already visited location"));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                } catch (FileSystemLoopException exception) {
                    findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_CYCLE,
                            display(relative), "Directory resolution encountered a cycle"));
                    return FileVisitResult.SKIP_SUBTREE;
                } catch (IOException exception) {
                    findings.add(finding(Severity.ERROR, FindingCode.WALK_FAILED,
                            display(relative), "Directory could not be resolved: " + safeException(exception)));
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (stopIfCancelled(cancellation, stop, findings)) {
                    return FileVisitResult.TERMINATE;
                }
                if (incrementAndLimit(progress, stop, findings, realRoot, file)) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = realRoot.relativize(file);
                if (attributes.isDirectory()) {
                    if (hasOmittedDescendants(file, relative, realRoot, findings)
                            && stop.markPartial(ScanStopReason.DEPTH_LIMIT_REACHED)) {
                        findings.add(finding(Severity.WARNING, FindingCode.DEPTH_LIMIT_REACHED,
                                relative, "Maximum traversal depth was reached; deeper entries were skipped"));
                    }
                    return FileVisitResult.CONTINUE;
                }
                List<HostMatch> matches = registry.match(relative, false);
                boolean configurationDirectory = registry.isKnownConfigurationDirectory(relative);

                if (attributes.isSymbolicLink()) {
                    inspectSymbolicLink(file, relative, realRoot, matches, configurationDirectory, findings);
                    return FileVisitResult.CONTINUE;
                }
                if (matches.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) {
                    findings.add(finding(Severity.BLOCKING, FindingCode.UNSUPPORTED_SPECIAL_FILE,
                            relative, "Allowlisted path is not a regular file and was not opened"));
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.size() > limits.maxArtifactBytes()) {
                    findings.add(finding(Severity.WARNING, FindingCode.FILE_TOO_LARGE, relative,
                            "Artifact exceeds the Phase 1 byte limit and was not opened"));
                    return FileVisitResult.CONTINUE;
                }
                candidates.add(new FileCandidate(file, relative, attributes, matches));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                if (stopIfCancelled(cancellation, stop, findings)) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = safeRelative(realRoot, file);
                FindingCode code = exception instanceof FileSystemLoopException
                        ? FindingCode.SYMLINK_CYCLE : FindingCode.UNREADABLE_FILE;
                findings.add(finding(code == FindingCode.SYMLINK_CYCLE ? Severity.BLOCKING : Severity.ERROR,
                        code, relative, "Path could not be inspected: " + safeException(exception)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
                if (stopIfCancelled(cancellation, stop, findings)) {
                    return FileVisitResult.TERMINATE;
                }
                if (exception != null) {
                    findings.add(finding(Severity.ERROR, FindingCode.WALK_FAILED,
                            safeRelative(realRoot, directory),
                            "Directory traversal did not complete: " + safeException(exception)));
                }
                return FileVisitResult.CONTINUE;
            }
        };

        Files.walkFileTree(realRoot, EnumSet.noneOf(FileVisitOption.class), limits.maxDepth(), visitor);
        candidates.sort(Comparator.comparing(candidate -> portable(candidate.relative())));
        if (!stop.stopped()) {
            for (FileCandidate candidate : candidates) {
                if (stopIfCancelled(cancellation, stop, findings)) {
                    break;
                }
                if (candidate.attributes().size() > progress.remainingBytes(limits.maxTotalBytes())) {
                    stopForByteLimit(stop, findings, candidate.relative(), limits.maxTotalBytes());
                    break;
                }
                inspectRegularFile(candidate.file(), candidate.relative(), realRoot,
                        candidate.attributes(), candidate.matches(), artifacts, findings,
                        cancellation, progress, stop);
                if (stop.stopped()) {
                    break;
                }
            }
        }
        artifacts.sort(Comparator.comparing(artifact -> portable(artifact.logicalPath())));
        findings.sort(Comparator.comparing((ScanFinding finding) -> portable(finding.logicalPath()))
                .thenComparing(finding -> finding.code().name()));
        return new ScanResult(logicalRoot, realRoot, Instant.now(), artifacts, findings,
                stop.partial() ? ScanCompletionStatus.PARTIAL : ScanCompletionStatus.COMPLETE,
                stop.reason());
    }

    private static boolean hasOmittedDescendants(
            Path directory,
            Path relative,
            Path realRoot,
            List<ScanFinding> findings) {
        try {
            Path resolved = directory.toRealPath();
            if (!resolved.startsWith(realRoot)) {
                findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_ESCAPE,
                        relative, "Depth-boundary directory resolves outside the approved root"));
                return true;
            }
            try (var entries = Files.newDirectoryStream(resolved)) {
                return entries.iterator().hasNext();
            }
        } catch (IOException | SecurityException exception) {
            findings.add(finding(Severity.ERROR, FindingCode.WALK_FAILED,
                    relative, "Depth-boundary directory could not be inspected: "
                            + safeException(exception)));
            return true;
        }
    }

    private void inspectSymbolicLink(
            Path file,
            Path relative,
            Path realRoot,
            List<HostMatch> matches,
            boolean configurationDirectory,
            List<ScanFinding> findings) {
        if (matches.isEmpty() && !configurationDirectory) {
            return;
        }
        try {
            Path resolved = resolveSymbolicLinkTarget(file);
            if (!resolved.startsWith(realRoot)) {
                findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_ESCAPE, relative,
                        "Symlink target is outside the approved root and was not opened"));
            } else if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                findings.add(finding(Severity.WARNING, FindingCode.SYMLINK_DIRECTORY_SKIPPED, relative,
                        "Symlinked configuration directory was not traversed"));
            } else {
                findings.add(finding(Severity.WARNING, FindingCode.SYMLINK_FILE_SKIPPED, relative,
                        "Phase 1 does not open symlinked configuration files, including in-root targets"));
            }
        } catch (FileSystemLoopException exception) {
            findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_CYCLE, relative,
                    "Symlink resolution encountered a cycle"));
        } catch (NoSuchFileException exception) {
            findings.add(finding(Severity.ERROR, FindingCode.SYMLINK_BROKEN, relative,
                    "Symlink target does not exist"));
        } catch (IOException exception) {
            findings.add(finding(Severity.ERROR, FindingCode.READ_FAILED, relative,
                    "Symlink could not be resolved: " + safeException(exception)));
        }
    }

    private static Path resolveSymbolicLinkTarget(Path initial) throws IOException {
        Set<Path> seen = new LinkedHashSet<>();
        Path current = initial.toAbsolutePath().normalize();
        for (int depth = 0; depth < 40; depth++) {
            if (!Files.isSymbolicLink(current)) {
                return current.toRealPath();
            }
            if (!seen.add(current)) {
                throw new FileSystemLoopException(initial.toString());
            }
            Path target = Files.readSymbolicLink(current);
            current = target.isAbsolute()
                    ? target.normalize()
                    : current.getParent().resolve(target).normalize();
        }
        throw new FileSystemLoopException(initial.toString());
    }

    private void inspectRegularFile(
            Path file,
            Path relative,
            Path realRoot,
            BasicFileAttributes walkAttributes,
            List<HostMatch> matches,
            List<DiscoveredArtifact> artifacts,
            List<ScanFinding> findings,
            ScanCancellation cancellation,
            ScanProgress progress,
            StopState stop) {
        try {
            Path resolvedBefore = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!resolvedBefore.startsWith(realRoot)) {
                findings.add(finding(Severity.BLOCKING, FindingCode.SYMLINK_ESCAPE, relative,
                        "File resolves outside the approved root"));
                return;
            }
            BasicFileAttributes before = Files.readAttributes(
                    resolvedBefore, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            ContentInspection content = inspectContent(
                    resolvedBefore,
                    before.size(),
                    limits.contentSampleBytes(),
                    limits.maxTotalBytes(),
                    cancellation,
                    progress);
            BasicFileAttributes after = Files.readAttributes(
                    resolvedBefore, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path resolvedAfter = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!unchanged(walkAttributes, before, after) || !resolvedBefore.equals(resolvedAfter)) {
                findings.add(finding(Severity.BLOCKING, FindingCode.CONCURRENT_MODIFICATION, relative,
                        "File changed while it was being scanned; hash was discarded"));
                return;
            }

            Set<String> hostIds = new LinkedHashSet<>();
            Set<ArtifactType> types = EnumSet.noneOf(ArtifactType.class);
            for (HostMatch match : matches) {
                hostIds.add(match.hostId());
                types.addAll(match.artifactTypes());
            }
            TextHints.Result hints = TextHints.inspect(content.sample());
            artifacts.add(new DiscoveredArtifact(
                    relative,
                    resolvedBefore,
                    hostIds,
                    types,
                    false,
                    after.size(),
                    content.sha256(),
                    hints.encodingHint(),
                    hints.lineEnding()));
        } catch (ScanStoppedException exception) {
            if (exception.reason() == ScanStopReason.CANCELLED) {
                stopForCancellation(stop, findings);
            } else {
                stopForByteLimit(stop, findings, relative, limits.maxTotalBytes());
            }
        } catch (NoSuchFileException exception) {
            findings.add(finding(Severity.ERROR, FindingCode.CONCURRENT_MODIFICATION, relative,
                    "File disappeared while it was being scanned"));
        } catch (IOException exception) {
            findings.add(finding(Severity.ERROR, FindingCode.READ_FAILED, relative,
                    "File could not be read: " + safeException(exception)));
        }
    }

    private static ContentInspection inspectContent(
            Path file,
            long expectedSize,
            int sampleLimit,
            long maxTotalBytes,
            ScanCancellation cancellation,
            ScanProgress progress) throws IOException, ScanStoppedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
        ByteArrayOutputStream sample = new ByteArrayOutputStream(sampleLimit);
        byte[] buffer = new byte[8192];
        long fileBytesRead = 0;
        try (InputStream input = Files.newInputStream(file)) {
            while (fileBytesRead < expectedSize) {
                if (cancellation.isCancellationRequested()) {
                    throw new ScanStoppedException(ScanStopReason.CANCELLED);
                }
                long remainingBudget = progress.remainingBytes(maxTotalBytes);
                if (remainingBudget == 0) {
                    throw new ScanStoppedException(ScanStopReason.TOTAL_BYTE_LIMIT_REACHED);
                }
                int readLimit = (int) Math.min(buffer.length,
                        Math.min(expectedSize - fileBytesRead, remainingBudget));
                int count = input.read(buffer, 0, readLimit);
                if (count == -1) {
                    break;
                }
                digest.update(buffer, 0, count);
                fileBytesRead += count;
                progress.addBytes(count);
                int remaining = sampleLimit - sample.size();
                if (remaining > 0) {
                    sample.write(buffer, 0, Math.min(count, remaining));
                }
            }
        }
        return new ContentInspection(HexFormat.of().formatHex(digest.digest()), sample.toByteArray());
    }

    private static boolean unchanged(
            BasicFileAttributes walked,
            BasicFileAttributes before,
            BasicFileAttributes after) {
        if (walked.size() != before.size() || before.size() != after.size()) {
            return false;
        }
        if (!walked.lastModifiedTime().equals(before.lastModifiedTime())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            return false;
        }
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return beforeKey == null || afterKey == null || beforeKey.equals(afterKey);
    }

    private boolean incrementAndLimit(
            ScanProgress progress,
            StopState stop,
            List<ScanFinding> findings,
            Path realRoot,
            Path current) {
        progress.incrementEntries();
        if (progress.entries() <= limits.maxEntries()) {
            return false;
        }
        stop.stop(ScanStopReason.ENTRY_LIMIT_REACHED);
        findings.add(finding(Severity.BLOCKING, FindingCode.ENTRY_LIMIT_REACHED,
                safeRelative(realRoot, current), "Entry limit reached; scan stopped"));
        return true;
    }

    private static boolean stopIfCancelled(
            ScanCancellation cancellation,
            StopState stop,
            List<ScanFinding> findings) {
        if (stop.stopped()) {
            return true;
        }
        if (!cancellation.isCancellationRequested()) {
            return false;
        }
        stopForCancellation(stop, findings);
        return true;
    }

    private static void stopForCancellation(StopState stop, List<ScanFinding> findings) {
        if (stop.stop(ScanStopReason.CANCELLED)) {
            findings.add(finding(Severity.WARNING, FindingCode.SCAN_CANCELLED,
                    Path.of("."), "Scan was cancelled; returned inventory is partial"));
        }
    }

    private static void stopForByteLimit(
            StopState stop,
            List<ScanFinding> findings,
            Path relative,
            long maxTotalBytes) {
        if (stop.stop(ScanStopReason.TOTAL_BYTE_LIMIT_REACHED)) {
            findings.add(finding(Severity.BLOCKING, FindingCode.TOTAL_BYTE_LIMIT_REACHED,
                    relative, "Aggregate read budget of " + maxTotalBytes
                            + " bytes was reached; scan stopped before this artifact was added"));
        }
    }

    private static ScanFinding finding(
            Severity severity,
            FindingCode code,
            Path path,
            String detail) {
        return new ScanFinding(severity, code, display(path), detail);
    }

    private static Path safeRelative(Path root, Path path) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            return absolute.startsWith(root) ? root.relativize(absolute) : Path.of(".");
        } catch (RuntimeException exception) {
            return Path.of(".");
        }
    }

    private static Path display(Path path) {
        return path.toString().isEmpty() ? Path.of(".") : path;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeException(Exception exception) {
        return exception.getClass().getSimpleName();
    }

    private record ContentInspection(String sha256, byte[] sample) {
        private ContentInspection {
            sample = sample.clone();
        }

        @Override
        public byte[] sample() {
            return sample.clone();
        }
    }

    private record FileCandidate(
            Path file,
            Path relative,
            BasicFileAttributes attributes,
            List<HostMatch> matches) {
        private FileCandidate {
            matches = List.copyOf(matches);
        }
    }

    private static final class ScanProgress {
        private long entries;
        private long bytesRead;

        void incrementEntries() {
            entries++;
        }

        long entries() {
            return entries;
        }

        void addBytes(long count) {
            bytesRead = Math.addExact(bytesRead, count);
        }

        long remainingBytes(long maximum) {
            return maximum - bytesRead;
        }
    }

    private static final class StopState {
        private ScanStopReason reason = ScanStopReason.NONE;
        private boolean terminal;

        boolean stop(ScanStopReason requestedReason) {
            Objects.requireNonNull(requestedReason, "requestedReason");
            if (requestedReason == ScanStopReason.NONE || stopped()) {
                return false;
            }
            reason = requestedReason;
            terminal = true;
            return true;
        }

        boolean markPartial(ScanStopReason requestedReason) {
            Objects.requireNonNull(requestedReason, "requestedReason");
            if (requestedReason == ScanStopReason.NONE || partial()) {
                return false;
            }
            reason = requestedReason;
            return true;
        }

        boolean stopped() {
            return terminal;
        }

        boolean partial() {
            return reason != ScanStopReason.NONE;
        }

        ScanStopReason reason() {
            return reason;
        }
    }

    private static final class ScanStoppedException extends Exception {
        private static final long serialVersionUID = 1L;
        private final ScanStopReason reason;

        ScanStoppedException(ScanStopReason reason) {
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        ScanStopReason reason() {
            return reason;
        }
    }
}
