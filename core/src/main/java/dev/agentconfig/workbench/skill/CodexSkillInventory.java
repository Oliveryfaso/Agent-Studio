package dev.agentconfig.workbench.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned, content-free inventory of project-local Codex Skill packages. */
public record CodexSkillInventory(
        int schemaVersion,
        Status status,
        boolean contentIncluded,
        boolean writesPerformed,
        List<SkillPackage> packages,
        List<Finding> findings) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CodexSkillInventory {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Codex Skill inventory schema");
        }
        Objects.requireNonNull(status, "status");
        if (contentIncluded || writesPerformed) {
            throw new IllegalArgumentException("Skill inventory must remain content-free and read-only");
        }
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (!packages.stream().sorted(Comparator.comparing(SkillPackage::logicalPath)).toList()
                .equals(packages)) {
            throw new IllegalArgumentException("Skill packages must use stable path ordering");
        }
        if (!findings.stream().sorted(Finding.ORDER).toList().equals(findings)) {
            throw new IllegalArgumentException("Skill findings must use stable ordering");
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
        UNKNOWN_FRONTMATTER_FIELD
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

    private static String portable(String value, String label) {
        String result = nonBlank(value, label).replace('\\', '/');
        if (result.startsWith("/") || result.contains("../") || result.equals("..")) {
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
