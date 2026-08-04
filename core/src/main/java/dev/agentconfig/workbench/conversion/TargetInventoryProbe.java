package dev.agentconfig.workbench.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Bounded, read-only target metadata probe. Target bytes never leave this class. */
public final class TargetInventoryProbe {
    public static final long MAX_TARGET_BYTES = 4L * 1024L * 1024L;
    public static final int MAX_TARGET_PATHS = 32;

    public TargetInventory probe(Path authorizedRoot, List<String> logicalPaths) throws IOException {
        Path root = authorizedRoot.toRealPath();
        List<String> paths = logicalPaths.stream().distinct().sorted().toList();
        if (paths.size() > MAX_TARGET_PATHS) {
            throw new IllegalArgumentException("target inventory path limit exceeded");
        }
        List<TargetInventoryEntry> entries = new ArrayList<>();
        for (String logicalPath : paths) {
            entries.add(probeOne(root, logicalPath));
        }
        return new TargetInventory(entries);
    }

    private static TargetInventoryEntry probeOne(Path root, String logicalPath) throws IOException {
        TargetInventoryEntry validated = TargetInventoryEntry.unknown(logicalPath);
        Path candidate = root.resolve(validated.logicalPath()).normalize();
        if (!candidate.startsWith(root)) {
            return state(logicalPath, TargetInventoryState.OUTSIDE_SCOPE);
        }
        Path cursor = root;
        for (Path segment : root.relativize(candidate)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(cursor)) {
                return state(logicalPath, TargetInventoryState.OUTSIDE_SCOPE);
            }
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return TargetInventoryEntry.absent(logicalPath);
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return state(logicalPath, TargetInventoryState.INVALID);
        }
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return state(logicalPath, TargetInventoryState.CHANGED_DURING_PROBE);
        }
        long size = before.size();
        if (size > MAX_TARGET_BYTES) {
            return state(logicalPath, TargetInventoryState.INVALID);
        }
        MessageDigest digest = sha256();
        long counted = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(
                candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                counted += read;
                if (counted > MAX_TARGET_BYTES) {
                    return state(logicalPath, TargetInventoryState.INVALID);
                }
                digest.update(buffer, 0, read);
            }
        } catch (NoSuchFileException exception) {
            return state(logicalPath, TargetInventoryState.CHANGED_DURING_PROBE);
        }
        BasicFileAttributes after;
        try {
            after = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return state(logicalPath, TargetInventoryState.CHANGED_DURING_PROBE);
        }
        if (!after.isRegularFile()
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
            return state(logicalPath, TargetInventoryState.CHANGED_DURING_PROBE);
        }
        return new TargetInventoryEntry(logicalPath, TargetInventoryState.PRESENT,
                HexFormat.of().formatHex(digest.digest()), counted);
    }

    private static TargetInventoryEntry state(String path, TargetInventoryState state) {
        return new TargetInventoryEntry(path, state, "", -1);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
