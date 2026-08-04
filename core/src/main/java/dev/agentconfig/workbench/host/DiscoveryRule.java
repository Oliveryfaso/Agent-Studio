package dev.agentconfig.workbench.host;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * A platform-neutral allowlist selector. It intentionally avoids PathMatcher
 * because glob separator behavior differs across operating systems.
 */
public record DiscoveryRule(Kind kind, String value, String suffix, ArtifactType artifactType) {
    public enum Kind {
        EXACT_FILE,
        FILE_NAME_ANY_DEPTH,
        TREE_FILE_NAME,
        TREE_SUFFIX
    }

    public DiscoveryRule {
        Objects.requireNonNull(kind, "kind");
        value = normalize(Objects.requireNonNull(value, "value"));
        suffix = normalizeNullable(suffix);
        Objects.requireNonNull(artifactType, "artifactType");
        if (value.isEmpty() || value.startsWith("/") || value.contains("..")) {
            throw new IllegalArgumentException("Discovery rule must be a safe relative path or name");
        }
        if ((kind == Kind.TREE_FILE_NAME || kind == Kind.TREE_SUFFIX)
                && (suffix == null || suffix.isEmpty())) {
            throw new IllegalArgumentException("Tree selectors require a suffix");
        }
    }

    public static DiscoveryRule exactFile(String path, ArtifactType type) {
        return new DiscoveryRule(Kind.EXACT_FILE, path, null, type);
    }

    public static DiscoveryRule fileName(String name, ArtifactType type) {
        return new DiscoveryRule(Kind.FILE_NAME_ANY_DEPTH, name, null, type);
    }

    public static DiscoveryRule treeFileName(String anchor, String fileName, ArtifactType type) {
        return new DiscoveryRule(Kind.TREE_FILE_NAME, anchor, fileName, type);
    }

    public static DiscoveryRule treeSuffix(String anchor, String suffix, ArtifactType type) {
        return new DiscoveryRule(Kind.TREE_SUFFIX, anchor, suffix, type);
    }

    public boolean matches(Path relativePath, boolean directory) {
        if (directory) {
            return false;
        }
        String relative = portable(relativePath);
        String fileName = relativePath.getFileName().toString();
        return switch (kind) {
            case EXACT_FILE -> relative.equals(value);
            case FILE_NAME_ANY_DEPTH -> fileName.equals(value);
            case TREE_FILE_NAME -> isBelowAnchor(relative, value) && fileName.equals(suffix);
            case TREE_SUFFIX -> isBelowAnchor(relative, value)
                    && fileName.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT));
        };
    }

    /** First path segment used to recognize configuration-directory symlinks. */
    public String anchorRoot() {
        if (kind != Kind.TREE_FILE_NAME && kind != Kind.TREE_SUFFIX) {
            return null;
        }
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static boolean isBelowAnchor(String relative, String anchor) {
        return relative.startsWith(anchor + "/") || relative.contains("/" + anchor + "/");
    }

    private static String portable(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String normalize(String value) {
        String normalized = value.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : normalize(value);
    }
}
