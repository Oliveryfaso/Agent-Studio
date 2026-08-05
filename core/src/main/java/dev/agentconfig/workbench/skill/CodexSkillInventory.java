package dev.agentconfig.workbench.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned, content-free inventory of project-local Codex Skill packages. */
public record CodexSkillInventory(
        int schemaVersion,
        String referenceProfileId,
        Status status,
        boolean contentIncluded,
        boolean writesPerformed,
        List<SkillPackage> packages,
        List<Reference> references,
        List<Finding> findings) {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String REFERENCE_PROFILE_ID = "codex-skill-inline-reference-v1";

    public CodexSkillInventory {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Codex Skill inventory schema");
        }
        if (!REFERENCE_PROFILE_ID.equals(referenceProfileId)) {
            throw new IllegalArgumentException("unsupported Codex Skill reference profile");
        }
        Objects.requireNonNull(status, "status");
        if (contentIncluded || writesPerformed) {
            throw new IllegalArgumentException("Skill inventory must remain content-free and read-only");
        }
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (!packages.stream().sorted(Comparator.comparing(SkillPackage::logicalPath)).toList()
                .equals(packages)) {
            throw new IllegalArgumentException("Skill packages must use stable path ordering");
        }
        if (!findings.stream().sorted(Finding.ORDER).toList().equals(findings)) {
            throw new IllegalArgumentException("Skill findings must use stable ordering");
        }
        if (!references.stream().sorted(Reference.ORDER).toList().equals(references)) {
            throw new IllegalArgumentException("Skill references must use stable ordering");
        }
        if (references.stream().distinct().count() != references.size()) {
            throw new IllegalArgumentException("Skill references must not contain duplicates");
        }
        Set<String> packageSources = packages.stream()
                .map(SkillPackage::logicalPath).collect(java.util.stream.Collectors.toSet());
        if (references.stream().anyMatch(reference ->
                !packageSources.contains(reference.sourceLogicalPath()))) {
            throw new IllegalArgumentException("Skill references must belong to an inventoried package");
        }
    }

    public enum Status { COMPLETE, PARTIAL }

    public enum PackageState { MINIMAL_METADATA_VALID, INVALID, PARTIAL }

    public enum Risk {
        SCRIPTS_DIRECTORY,
        EXECUTABLE_SUPPORT_FILE,
        SYMLINK_SUPPORT_PATH
    }

    public enum Severity { WARNING, ERROR, BLOCKING }

    public enum FindingCode {
        AGENTS_PATH_IS_SYMLINK,
        SKILLS_PATH_IS_SYMLINK,
        PACKAGE_PATH_IS_SYMLINK,
        SKILL_FILE_IS_SYMLINK,
        SUPPORT_PATH_IS_SYMLINK,
        SUPPORT_PATH_READ_FAILED,
        DIRECTORY_READ_FAILED,
        SKILL_READ_FAILED,
        SKILL_CHANGED_DURING_READ,
        SKILL_TOO_LARGE,
        PACKAGE_ENTRY_LIMIT_REACHED,
        PACKAGE_COUNT_LIMIT_REACHED,
        PACKAGE_DEPTH_LIMIT_REACHED,
        INVALID_PACKAGE_DIRECTORY_NAME,
        PATH_NOT_ANCHORED,
        MISSING_SKILL_FILE,
        INVALID_UTF8,
        MISSING_FRONTMATTER,
        INVALID_FRONTMATTER,
        MISSING_NAME,
        MISSING_DESCRIPTION,
        INVALID_NAME,
        NAME_DIRECTORY_MISMATCH,
        DUPLICATE_DECLARED_NAME,
        UNKNOWN_FRONTMATTER_FIELD,
        MISSING_REFERENCE_TARGET,
        UNSAFE_LOCAL_REFERENCE,
        REFERENCE_LIMIT_REACHED
    }

    public enum ReferenceKind { LINK, IMAGE }

    public enum ReferenceResolution { RESOLVED, MISSING, UNKNOWN }

    /**
     * Content-free reference occurrence. Only RESOLVED references retain a normalized target path;
     * unresolved targets are redacted and keep source position, kind, and resolution only.
     */
    public record Reference(
            String sourceLogicalPath,
            String targetLogicalPath,
            int line,
            int column,
            ReferenceKind kind,
            ReferenceResolution resolution) {
        private static final Comparator<Reference> ORDER = Comparator
                .comparing(Reference::sourceLogicalPath)
                .thenComparing(Reference::targetLogicalPath)
                .thenComparingInt(Reference::line)
                .thenComparingInt(Reference::column)
                .thenComparing(reference -> reference.kind().name())
                .thenComparing(reference -> reference.resolution().name());

        public Reference {
            sourceLogicalPath = portable(sourceLogicalPath, "sourceLogicalPath");
            targetLogicalPath = targetLogicalPath == null ? "" : targetLogicalPath;
            String suffix = "/SKILL.md";
            if (!sourceLogicalPath.endsWith(suffix)) {
                throw new IllegalArgumentException("reference source must be a SKILL.md path");
            }
            String packagePrefix = sourceLogicalPath.substring(
                    0, sourceLogicalPath.length() - suffix.length()) + "/";
            if (resolution == ReferenceResolution.RESOLVED) {
                targetLogicalPath = portable(targetLogicalPath, "targetLogicalPath");
            }
            if ((resolution == ReferenceResolution.RESOLVED
                            && (!targetLogicalPath.startsWith(packagePrefix)
                                    || targetLogicalPath.equals(sourceLogicalPath)))
                    || (resolution != ReferenceResolution.RESOLVED
                            && !targetLogicalPath.isEmpty())) {
                throw new IllegalArgumentException(
                        "only resolved references may expose an in-package target path");
            }
            if (line < 1 || column < 1) {
                throw new IllegalArgumentException("reference line and column must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resolution, "resolution");
        }
    }

    public record Finding(
            Severity severity,
            FindingCode code,
            String logicalPath,
            String summary) {
        private static final Comparator<Finding> ORDER = Comparator
                .comparing(Finding::logicalPath)
                .thenComparing(finding -> finding.code().name())
                .thenComparing(finding -> finding.severity().name());

        public Finding {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            logicalPath = portable(logicalPath, "logicalPath");
            summary = nonBlank(summary, "summary");
        }
    }

    public record SkillPackage(
            String directoryName,
            String declaredName,
            String logicalPath,
            long byteSize,
            String sha256,
            boolean descriptionPresent,
            int supportingFileCount,
            Set<Risk> risks,
            PackageState state) {
        public SkillPackage {
            directoryName = nonBlank(directoryName, "directoryName");
            declaredName = declaredName == null ? "" : declaredName;
            logicalPath = portable(logicalPath, "logicalPath");
            if (byteSize < 0 || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid Skill size or hash");
            }
            if (supportingFileCount < 0) {
                throw new IllegalArgumentException("supportingFileCount must not be negative");
            }
            risks = Set.copyOf(Objects.requireNonNull(risks, "risks"));
            Objects.requireNonNull(state, "state");
        }
    }

    static Comparator<Finding> findingOrder() {
        return Finding.ORDER;
    }

    static Comparator<Reference> referenceOrder() {
        return Reference.ORDER;
    }

    private static String portable(String value, String label) {
        String result = nonBlank(value, label).replace('\\', '/');
        if (result.startsWith("/") || result.contains("../") || result.equals("..")
                || result.endsWith("/..")) {
            throw new IllegalArgumentException(label + " must be a safe logical path");
        }
        return result;
    }

    private static String nonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
