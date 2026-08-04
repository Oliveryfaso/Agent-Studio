package dev.agentconfig.workbench.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads only the minimum Git administrative metadata needed to classify a workspace and HEAD.
 * It never executes Git, opens the index/object database, or examines worktree content.
 */
public final class GitMetadataProbe {
    private static final int MAX_METADATA_BYTES = 4096;
    private static final Pattern OBJECT_ID = Pattern.compile("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})");
    private static final Pattern REF_CHARACTERS = Pattern.compile("[A-Za-z0-9._/-]+");

    public GitMetadata probe(GitProbeRequest request) {
        Objects.requireNonNull(request, "request");
        List<GitProbeFinding> findings = new ArrayList<>();
        List<GitProbeUnknown> unknowns = new ArrayList<>();
        unknowns.add(new GitProbeUnknown(
                GitProbeUnknown.Code.DIRTY_STATE_NOT_PROBED,
                "Dirty state is unknown because this probe does not inspect the index or worktree content"));

        Path logicalRoot = request.approvedRoot().toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = logicalRoot.toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                return unavailable(new GitDirDescriptor(
                        GitDirDescriptor.Kind.UNSUPPORTED,
                        GitDirDescriptor.Location.UNRESOLVED,
                        Optional.of(logicalRoot)), findings, unknowns,
                        logicalRoot, GitProbeFinding.Code.GIT_ENTRY_UNSUPPORTED,
                        "Approved root is not a directory");
            }
        } catch (IOException | SecurityException exception) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.UNSUPPORTED,
                    GitDirDescriptor.Location.UNRESOLVED,
                    Optional.of(logicalRoot)), findings, unknowns,
                    logicalRoot, GitProbeFinding.Code.METADATA_READ_FAILED,
                    "Approved root could not be resolved");
        }

        Path gitEntry = realRoot.resolve(".git");
        BasicFileAttributes entryAttributes;
        try {
            entryAttributes = Files.readAttributes(
                    gitEntry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exception) {
            unknowns.add(new GitProbeUnknown(
                    GitProbeUnknown.Code.NOT_A_GIT_WORKSPACE,
                    "No .git administrative entry exists at the approved workspace root"));
            return new GitMetadata(
                    false,
                    new GitDirDescriptor(GitDirDescriptor.Kind.MISSING,
                            GitDirDescriptor.Location.NONE, Optional.empty()),
                    Optional.empty(),
                    GitMetadata.WorktreeState.UNKNOWN_NOT_PROBED,
                    findings,
                    unknowns);
        } catch (IOException | SecurityException exception) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.UNSUPPORTED,
                    GitDirDescriptor.Location.UNRESOLVED,
                    Optional.of(gitEntry)), findings, unknowns,
                    gitEntry, GitProbeFinding.Code.METADATA_READ_FAILED,
                    ".git administrative entry could not be inspected");
        }

        if (entryAttributes.isSymbolicLink()) {
            GitDirDescriptor.Location location = lexicalSymlinkLocation(gitEntry, realRoot);
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.SYMLINK_REJECTED, location, Optional.of(gitEntry)),
                    findings, unknowns, gitEntry,
                    GitProbeFinding.Code.GIT_ENTRY_SYMLINK_REJECTED,
                    "A symlink .git entry is not trusted by the metadata probe");
        }
        if (entryAttributes.isDirectory()) {
            return probeDirectory(realRoot, gitEntry, GitDirDescriptor.Kind.DIRECTORY,
                    GitDirDescriptor.Location.WITHIN_APPROVED_ROOT, findings, unknowns);
        }
        if (!entryAttributes.isRegularFile()) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.UNSUPPORTED,
                    GitDirDescriptor.Location.WITHIN_APPROVED_ROOT,
                    Optional.of(gitEntry)), findings, unknowns, gitEntry,
                    GitProbeFinding.Code.GIT_ENTRY_UNSUPPORTED,
                    ".git entry is neither a regular file nor a directory");
        }
        return probeGitFile(request, realRoot, gitEntry, findings, unknowns);
    }

    private GitMetadata probeGitFile(
            GitProbeRequest request,
            Path realRoot,
            Path gitFile,
            List<GitProbeFinding> findings,
            List<GitProbeUnknown> unknowns) {
        String declaration;
        try {
            declaration = readSingleMetadataLine(gitFile);
        } catch (MetadataException exception) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.GITFILE_POINTER,
                    GitDirDescriptor.Location.UNRESOLVED,
                    Optional.empty()), findings, unknowns, gitFile,
                    exception.code, exception.getMessage());
        }
        if (!declaration.startsWith("gitdir: ")) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.GITFILE_POINTER,
                    GitDirDescriptor.Location.UNRESOLVED,
                    Optional.empty()), findings, unknowns, gitFile,
                    GitProbeFinding.Code.GITFILE_INVALID,
                    ".git file does not contain a valid gitdir declaration");
        }
        String rawTarget = declaration.substring("gitdir: ".length()).strip();
        if (rawTarget.isEmpty() || containsControl(rawTarget)) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.GITFILE_POINTER,
                    GitDirDescriptor.Location.UNRESOLVED,
                    Optional.empty()), findings, unknowns, gitFile,
                    GitProbeFinding.Code.GITFILE_INVALID,
                    ".git file contains an unsafe gitdir path");
        }

        final Path declared;
        try {
            Path value = Path.of(rawTarget);
            declared = (value.isAbsolute() ? value : gitFile.getParent().resolve(value))
                    .toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.GITFILE_POINTER,
                    GitDirDescriptor.Location.UNRESOLVED,
                    Optional.empty()), findings, unknowns, gitFile,
                    GitProbeFinding.Code.GITFILE_INVALID,
                    ".git file contains a path invalid on this platform");
        }

        if (declared.startsWith(realRoot)) {
            return probeDirectory(realRoot, declared, GitDirDescriptor.Kind.GITFILE_POINTER,
                    GitDirDescriptor.Location.WITHIN_APPROVED_ROOT, findings, unknowns);
        }
        if (!request.allowBoundedExternalWorktreeMetadata()) {
            return unavailable(new GitDirDescriptor(
                    GitDirDescriptor.Kind.GITFILE_POINTER,
                    GitDirDescriptor.Location.OUTSIDE_APPROVED_ROOT_REJECTED,
                    Optional.of(declared)), findings, unknowns, gitFile,
                    GitProbeFinding.Code.EXTERNAL_METADATA_NOT_AUTHORIZED,
                    "External Git administrative metadata was not separately authorized");
        }

        Optional<Path> boundedGitDir = validateBoundedWorktreePointer(
                realRoot, gitFile, declared, findings);
        if (boundedGitDir.isEmpty()) {
            addHeadUnavailable(unknowns);
            return new GitMetadata(
                    false,
                    new GitDirDescriptor(GitDirDescriptor.Kind.GITFILE_POINTER,
                            GitDirDescriptor.Location.OUTSIDE_APPROVED_ROOT_REJECTED,
                            Optional.of(declared)),
                    Optional.empty(),
                    GitMetadata.WorktreeState.UNKNOWN_NOT_PROBED,
                    findings,
                    unknowns);
        }
        return probeDirectory(realRoot, boundedGitDir.orElseThrow(),
                GitDirDescriptor.Kind.GITFILE_POINTER,
                GitDirDescriptor.Location.BOUNDED_EXTERNAL_WORKTREE,
                findings, unknowns);
    }

    private Optional<Path> validateBoundedWorktreePointer(
            Path realRoot,
            Path workspaceGitFile,
            Path declared,
            List<GitProbeFinding> findings) {
        try {
            BasicFileAttributes targetAttributes = Files.readAttributes(
                    declared, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (targetAttributes.isSymbolicLink() || !targetAttributes.isDirectory()) {
                throw new MetadataException(GitProbeFinding.Code.WORKTREE_POINTER_INVALID,
                        "External gitdir is not a non-symlink directory");
            }
            Path gitDir = declared.toRealPath();

            String commonDeclaration = readSingleMetadataLine(gitDir.resolve("commondir"));
            Path commonValue = Path.of(commonDeclaration);
            if (commonValue.isAbsolute()) {
                throw new MetadataException(GitProbeFinding.Code.WORKTREE_POINTER_INVALID,
                        "Linked-worktree commondir must be a bounded relative path");
            }
            Path commonDir = gitDir.resolve(commonValue).normalize().toRealPath();
            Path worktreesDirectory = commonDir.resolve("worktrees").normalize();
            if (!gitDir.getParent().equals(worktreesDirectory)
                    || gitDir.getFileName() == null
                    || gitDir.getFileName().toString().isBlank()) {
                throw new MetadataException(GitProbeFinding.Code.WORKTREE_POINTER_INVALID,
                        "External gitdir is not one immediate child of the common worktrees directory");
            }

            String backlinkDeclaration = readSingleMetadataLine(gitDir.resolve("gitdir"));
            Path backlinkValue = Path.of(backlinkDeclaration);
            Path backlink = (backlinkValue.isAbsolute()
                    ? backlinkValue : gitDir.resolve(backlinkValue)).toAbsolutePath().normalize();
            BasicFileAttributes backlinkAttributes = Files.readAttributes(
                    backlink, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (backlinkAttributes.isSymbolicLink()
                    || !backlinkAttributes.isRegularFile()
                    || !backlink.toRealPath().equals(workspaceGitFile.toRealPath())) {
                throw new MetadataException(GitProbeFinding.Code.WORKTREE_POINTER_INVALID,
                        "External gitdir does not point back to this workspace .git file");
            }
            if (!workspaceGitFile.getParent().equals(realRoot)) {
                throw new MetadataException(GitProbeFinding.Code.WORKTREE_POINTER_INVALID,
                        "Workspace backlink is outside the approved root");
            }
            return Optional.of(gitDir);
        } catch (MetadataException exception) {
            findings.add(finding(GitProbeFinding.Severity.BLOCKING,
                    exception.code, workspaceGitFile, exception.getMessage()));
        } catch (IOException | RuntimeException exception) {
            findings.add(finding(GitProbeFinding.Severity.BLOCKING,
                    GitProbeFinding.Code.WORKTREE_POINTER_INVALID,
                    workspaceGitFile,
                    "External worktree pointer could not be bounded and verified"));
        }
        return Optional.empty();
    }

    private GitMetadata probeDirectory(
            Path realRoot,
            Path candidate,
            GitDirDescriptor.Kind kind,
            GitDirDescriptor.Location intendedLocation,
            List<GitProbeFinding> findings,
            List<GitProbeUnknown> unknowns) {
        Path gitDir;
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                return unavailable(new GitDirDescriptor(kind,
                        GitDirDescriptor.Location.UNRESOLVED, Optional.of(candidate)),
                        findings, unknowns, candidate,
                        GitProbeFinding.Code.GITFILE_INVALID,
                        "Resolved gitdir is not a non-symlink directory");
            }
            gitDir = candidate.toRealPath();
            if (intendedLocation == GitDirDescriptor.Location.WITHIN_APPROVED_ROOT
                    && !gitDir.startsWith(realRoot)) {
                return unavailable(new GitDirDescriptor(kind,
                        GitDirDescriptor.Location.OUTSIDE_APPROVED_ROOT_REJECTED,
                        Optional.of(gitDir)), findings, unknowns, candidate,
                        GitProbeFinding.Code.GIT_DIR_OUTSIDE_APPROVED_ROOT,
                        "Git administrative directory resolves outside the approved root");
            }
        } catch (IOException | SecurityException exception) {
            return unavailable(new GitDirDescriptor(kind,
                    GitDirDescriptor.Location.UNRESOLVED, Optional.of(candidate)),
                    findings, unknowns, candidate,
                    GitProbeFinding.Code.METADATA_READ_FAILED,
                    "Git administrative directory could not be inspected");
        }

        Optional<GitHead> head = readHead(gitDir.resolve("HEAD"), findings);
        if (head.isEmpty()) {
            addHeadUnavailable(unknowns);
        }
        return new GitMetadata(
                true,
                new GitDirDescriptor(kind, intendedLocation, Optional.of(gitDir)),
                head,
                GitMetadata.WorktreeState.UNKNOWN_NOT_PROBED,
                findings,
                unknowns);
    }

    private Optional<GitHead> readHead(Path headPath, List<GitProbeFinding> findings) {
        final String line;
        try {
            line = readSingleMetadataLine(headPath);
        } catch (MetadataException exception) {
            findings.add(finding(GitProbeFinding.Severity.ERROR,
                    exception.code, headPath, exception.getMessage()));
            return Optional.empty();
        }
        if (line.startsWith("ref: ")) {
            String ref = line.substring("ref: ".length()).strip();
            if (safeRef(ref)) {
                return Optional.of(new GitHead(GitHead.Kind.SYMBOLIC_REF, ref));
            }
        } else if (OBJECT_ID.matcher(line).matches()) {
            return Optional.of(new GitHead(
                    GitHead.Kind.DETACHED_HASH, line.toLowerCase(Locale.ROOT)));
        }
        findings.add(finding(GitProbeFinding.Severity.ERROR,
                GitProbeFinding.Code.METADATA_INVALID_FORMAT,
                headPath,
                "HEAD is neither a safe symbolic ref nor a full object ID"));
        return Optional.empty();
    }

    private static boolean safeRef(String ref) {
        return ref.startsWith("refs/")
                && REF_CHARACTERS.matcher(ref).matches()
                && !ref.contains("..")
                && !ref.contains("//")
                && !ref.contains("@{")
                && !ref.endsWith("/")
                && !ref.endsWith(".")
                && !ref.endsWith(".lock");
    }

    private static String readSingleMetadataLine(Path path) throws MetadataException {
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_MISSING,
                    "Required Git administrative metadata is missing");
        } catch (IOException | SecurityException exception) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_READ_FAILED,
                    "Git administrative metadata could not be inspected");
        }
        if (before.isSymbolicLink()) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_SYMLINK_REJECTED,
                    "Symlinked Git administrative metadata was not opened");
        }
        if (!before.isRegularFile()) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_INVALID_FORMAT,
                    "Git administrative metadata is not a regular file");
        }
        if (before.size() > MAX_METADATA_BYTES) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_TOO_LARGE,
                    "Git administrative metadata exceeds the read limit");
        }

        byte[] bytes;
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (var channel = Files.newByteChannel(path, options);
                InputStream input = java.nio.channels.Channels.newInputStream(channel);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_METADATA_BYTES) {
                    throw new MetadataException(GitProbeFinding.Code.METADATA_TOO_LARGE,
                            "Git administrative metadata exceeds the read limit");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        } catch (MetadataException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_READ_FAILED,
                    "Git administrative metadata could not be read");
        }

        try {
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (after.isSymbolicLink()
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || (before.fileKey() != null && after.fileKey() != null
                            && !before.fileKey().equals(after.fileKey()))) {
                throw new MetadataException(GitProbeFinding.Code.METADATA_CONCURRENTLY_MODIFIED,
                        "Git administrative metadata changed while it was read");
            }
        } catch (MetadataException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_CONCURRENTLY_MODIFIED,
                    "Git administrative metadata could not be revalidated after reading");
        }

        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_INVALID_UTF8,
                    "Git administrative metadata is not valid UTF-8");
        }
        String oneLine = removeOneTrailingLineEnding(decoded);
        if (oneLine.isEmpty() || oneLine.indexOf('\n') >= 0 || oneLine.indexOf('\r') >= 0) {
            throw new MetadataException(GitProbeFinding.Code.METADATA_INVALID_FORMAT,
                    "Git administrative metadata must contain exactly one non-empty line");
        }
        return oneLine;
    }

    private static String removeOneTrailingLineEnding(String value) {
        if (!value.endsWith("\n")) {
            return value;
        }
        String result = value.substring(0, value.length() - 1);
        if (result.endsWith("\r")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(character -> Character.isISOControl(character));
    }

    private static GitDirDescriptor.Location lexicalSymlinkLocation(Path link, Path realRoot) {
        try {
            Path target = Files.readSymbolicLink(link);
            Path resolved = (target.isAbsolute() ? target : link.getParent().resolve(target))
                    .toAbsolutePath().normalize();
            return resolved.startsWith(realRoot)
                    ? GitDirDescriptor.Location.WITHIN_APPROVED_ROOT
                    : GitDirDescriptor.Location.OUTSIDE_APPROVED_ROOT_REJECTED;
        } catch (IOException | SecurityException exception) {
            return GitDirDescriptor.Location.UNRESOLVED;
        }
    }

    private static GitMetadata unavailable(
            GitDirDescriptor descriptor,
            List<GitProbeFinding> findings,
            List<GitProbeUnknown> unknowns,
            Path path,
            GitProbeFinding.Code code,
            String detail) {
        findings.add(finding(GitProbeFinding.Severity.BLOCKING, code, path, detail));
        addHeadUnavailable(unknowns);
        return new GitMetadata(
                false,
                descriptor,
                Optional.empty(),
                GitMetadata.WorktreeState.UNKNOWN_NOT_PROBED,
                findings,
                unknowns);
    }

    private static void addHeadUnavailable(List<GitProbeUnknown> unknowns) {
        if (unknowns.stream().noneMatch(unknown -> unknown.code() == GitProbeUnknown.Code.HEAD_UNAVAILABLE)) {
            unknowns.add(new GitProbeUnknown(
                    GitProbeUnknown.Code.HEAD_UNAVAILABLE,
                    "HEAD could not be reported without crossing a safety or validity boundary"));
        }
    }

    private static GitProbeFinding finding(
            GitProbeFinding.Severity severity,
            GitProbeFinding.Code code,
            Path path,
            String detail) {
        return new GitProbeFinding(severity, code, path, detail);
    }

    private static final class MetadataException extends Exception {
        private static final long serialVersionUID = 1L;
        private final GitProbeFinding.Code code;

        private MetadataException(GitProbeFinding.Code code, String message) {
            super(message);
            this.code = code;
        }
    }
}
