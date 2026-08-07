package dev.agentconfig.workbench.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Read-only, content-free classification of project-local Skills. */
public record SkillTaxonomyReport(
        int schemaVersion,
        String classifierProfileId,
        Status status,
        boolean contentIncluded,
        boolean writesPerformed,
        boolean llmUsed,
        List<Category> categories,
        List<Classification> skills,
        int unclassifiedCount) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String CLASSIFIER_PROFILE_ID = "dev-skill-taxonomy-v1";

    public SkillTaxonomyReport {
        if (schemaVersion != CURRENT_SCHEMA_VERSION
                || !CLASSIFIER_PROFILE_ID.equals(classifierProfileId)) {
            throw new IllegalArgumentException("taxonomy schema");
        }
        Objects.requireNonNull(status, "status");
        if (contentIncluded || writesPerformed || llmUsed) {
            throw new IllegalArgumentException("taxonomy must be local, read-only and deterministic");
        }
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
        if (!categories.equals(Category.buckets())) throw new IllegalArgumentException("categories");
        if (!skills.stream().sorted(Comparator.comparing(Classification::logicalPath)).toList()
                .equals(skills)) throw new IllegalArgumentException("skill ordering");
        long actual = skills.stream().filter(skill -> skill.category() == null).count();
        if (unclassifiedCount != actual) throw new IllegalArgumentException("unclassified count");
    }

    public enum Status { COMPLETE, PARTIAL }

    public enum Category {
        LIBRARY_API_REFERENCE,
        PRODUCT_VALIDATION,
        DATA_QUERY_ANALYSIS,
        BUSINESS_WORKFLOW_AUTOMATION,
        CODE_SCAFFOLDING,
        CODE_QUALITY_REVIEW,
        CI_CD_DEPLOYMENT,
        RUNBOOK_TROUBLESHOOTING,
        INFRASTRUCTURE_OPERATIONS;

        public static List<Category> buckets() {
            return List.of(values());
        }
    }

    public enum Confidence { HIGH, MEDIUM, UNCLASSIFIED }

    public enum EvidenceSource { NAME, DESCRIPTION }

    public record Classification(
            String name,
            String logicalPath,
            String sourceSha256,
            Category category,
            Confidence confidence,
            int score,
            int margin,
            List<EvidenceSource> evidenceSources) {
        public Classification {
            if (name == null || name.isBlank() || logicalPath == null || logicalPath.isBlank()
                    || sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("classification identity");
            }
            Objects.requireNonNull(confidence, "confidence");
            evidenceSources = List.copyOf(Objects.requireNonNull(
                    evidenceSources, "evidenceSources"));
            if (score < 0 || margin < 0 || (category == null)
                    != (confidence == Confidence.UNCLASSIFIED)) {
                throw new IllegalArgumentException("classification result");
            }
        }
    }
}
