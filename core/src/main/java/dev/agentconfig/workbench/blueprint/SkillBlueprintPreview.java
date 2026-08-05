package dev.agentconfig.workbench.blueprint;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Versioned, in-memory result of guided persistence triage and optional Skill blueprinting. */
public record SkillBlueprintPreview(
        int schemaVersion,
        String id,
        String classificationProfileId,
        PreviewStatus status,
        PersistenceDecision decision,
        Optional<SkillBlueprint> blueprint,
        List<String> missingFields,
        List<String> findings,
        boolean workspaceContentIncluded,
        boolean userProvidedContentIncluded,
        boolean rawRequestIncluded,
        boolean llmUsed,
        boolean writesPerformed,
        boolean applyEligible) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public SkillBlueprintPreview {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported SkillBlueprintPreview schema version");
        }
        id = required(id, "id");
        classificationProfileId = required(classificationProfileId, "classificationProfileId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        blueprint = Objects.requireNonNull(blueprint, "blueprint");
        missingFields = immutable(missingFields, "missingFields");
        findings = immutable(findings, "findings");
        if (workspaceContentIncluded || rawRequestIncluded || llmUsed || writesPerformed
                || applyEligible) {
            throw new IllegalArgumentException("preview safety flags are invalid");
        }
        if (!userProvidedContentIncluded) {
            throw new IllegalArgumentException("blueprint preview contains user-provided content");
        }
        if (blueprint.isPresent() && status != PreviewStatus.BLUEPRINT_READY) {
            throw new IllegalArgumentException("a blueprint is only valid when ready");
        }
        if (status == PreviewStatus.BLUEPRINT_READY && blueprint.isEmpty()) {
            throw new IllegalArgumentException("ready preview requires a blueprint");
        }
        switch (status) {
            case BLUEPRINT_READY -> {
                if (!missingFields.isEmpty() || decision.status() != DecisionStatus.DECIDED
                        || decision.confirmedArtifact().orElse(ArtifactType.UNKNOWN)
                                != ArtifactType.SKILL
                        || !"PROJECT".equals(decision.confirmedScope().orElse(""))) {
                    throw new IllegalArgumentException("ready preview requires confirmed project Skill");
                }
            }
            case INCOMPLETE -> {
                if (blueprint.isPresent() || missingFields.isEmpty()
                        || decision.confirmedArtifact().orElse(ArtifactType.UNKNOWN)
                                != ArtifactType.SKILL
                        || decision.status() != DecisionStatus.DECIDED
                        || !"PROJECT".equals(decision.confirmedScope().orElse(""))) {
                    throw new IllegalArgumentException("incomplete preview requires missing Skill fields");
                }
            }
            case TRIAGE_READY -> {
                if (blueprint.isPresent() || decision.status() != DecisionStatus.DECIDED
                        || decision.confirmedArtifact().orElse(ArtifactType.SKILL)
                                == ArtifactType.SKILL) {
                    throw new IllegalArgumentException("triage ready requires confirmed non-Skill type");
                }
            }
            case NEEDS_CONFIRMATION -> {
                if (blueprint.isPresent() || decision.status() != DecisionStatus.NEEDS_CONFIRMATION) {
                    throw new IllegalArgumentException("confirmation status disagrees with decision");
                }
            }
            case BLOCKED -> {
                if (blueprint.isPresent()
                        || decision.recommendedArtifact()
                                != ArtifactType.HIGH_RISK_EXECUTABLE_PROPOSAL) {
                    throw new IllegalArgumentException("blocked preview requires executable proposal");
                }
            }
        }
    }

    public enum PreviewStatus {
        TRIAGE_READY, NEEDS_CONFIRMATION, INCOMPLETE, BLUEPRINT_READY, BLOCKED
    }

    public enum ArtifactType {
        PROMPT,
        INSTRUCTION,
        SKILL,
        AGENT,
        DETERMINISTIC_TOOL_POLICY,
        HIGH_RISK_EXECUTABLE_PROPOSAL,
        UNKNOWN
    }

    public enum DecisionStatus { DECIDED, NEEDS_CONFIRMATION }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record PersistenceDecision(
            ArtifactType recommendedArtifact,
            DecisionStatus status,
            Confidence confidence,
            List<String> evidence,
            List<ArtifactType> alternatives,
            boolean userConfirmationRequired,
            boolean userConfirmed,
            Optional<ArtifactType> confirmedArtifact,
            Optional<String> confirmedScope) {
        public PersistenceDecision {
            Objects.requireNonNull(recommendedArtifact, "recommendedArtifact");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(confidence, "confidence");
            evidence = immutable(evidence, "evidence");
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
            if (new LinkedHashSet<>(alternatives).size() != alternatives.size()
                    || alternatives.contains(recommendedArtifact)
                    || alternatives.contains(ArtifactType.UNKNOWN)) {
                throw new IllegalArgumentException("alternatives must be unique viable alternatives");
            }
            confirmedArtifact = Objects.requireNonNull(confirmedArtifact, "confirmedArtifact");
            confirmedScope = Objects.requireNonNull(confirmedScope, "confirmedScope")
                    .map(value -> required(value, "confirmedScope"));
            if (!userConfirmationRequired) {
                throw new IllegalArgumentException("S1 decisions always require user confirmation");
            }
            boolean confirmationPresent = confirmedArtifact.isPresent() && confirmedScope.isPresent();
            if (userConfirmed != confirmationPresent) {
                throw new IllegalArgumentException("userConfirmed must match confirmed fields");
            }
            if (status == DecisionStatus.DECIDED
                    && (recommendedArtifact == ArtifactType.UNKNOWN || !userConfirmed
                    || confirmedArtifact.orElseThrow() != recommendedArtifact)) {
                throw new IllegalArgumentException("DECIDED requires a matching confirmed artifact");
            }
            if (status == DecisionStatus.DECIDED) {
                String expectedScope = recommendedArtifact == ArtifactType.PROMPT
                        ? "SESSION" : "PROJECT";
                if (!expectedScope.equals(confirmedScope.orElseThrow())) {
                    throw new IllegalArgumentException("confirmed scope does not match artifact type");
                }
            }
        }
    }

    public record SkillBlueprint(
            int schemaVersion,
            String id,
            String name,
            String description,
            String goal,
            String scope,
            List<String> inputs,
            List<String> outputs,
            List<String> triggers,
            List<String> exclusions,
            List<String> boundaryExamples,
            List<String> shouldTriggerCases,
            List<String> shouldNotTriggerCases,
            List<String> coreSteps,
            String completionDefinition,
            List<String> validation,
            List<String> tools,
            List<String> permissionIntents,
            String risk,
            List<String> supportingFiles,
            List<String> hostExtensions,
            List<String> provenance) {
        public static final int CURRENT_SCHEMA_VERSION = 1;

        public SkillBlueprint {
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported SkillBlueprint schema version");
            }
            id = required(id, "blueprint id");
            name = required(name, "name");
            if (name.length() > 63 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new IllegalArgumentException("Skill name must match the Codex inventory profile");
            }
            description = required(description, "description");
            goal = required(goal, "goal");
            scope = required(scope, "scope");
            if (!"PROJECT".equals(scope)) {
                throw new IllegalArgumentException("S1 only supports PROJECT scope");
            }
            inputs = nonEmpty(inputs, "inputs");
            outputs = nonEmpty(outputs, "outputs");
            triggers = nonEmpty(triggers, "triggers");
            exclusions = nonEmpty(exclusions, "exclusions");
            Set<String> triggerSet = normalized(triggers);
            triggerSet.retainAll(normalized(exclusions));
            if (!triggerSet.isEmpty()) {
                throw new IllegalArgumentException("triggers and exclusions overlap");
            }
            boundaryExamples = nonEmpty(boundaryExamples, "boundaryExamples");
            shouldTriggerCases = minimum(shouldTriggerCases, "shouldTriggerCases", 3);
            shouldNotTriggerCases = minimum(shouldNotTriggerCases, "shouldNotTriggerCases", 3);
            Set<String> positiveCases = normalized(shouldTriggerCases);
            Set<String> negativeCases = normalized(shouldNotTriggerCases);
            if (positiveCases.size() != shouldTriggerCases.size()
                    || negativeCases.size() != shouldNotTriggerCases.size()) {
                throw new IllegalArgumentException("trigger cases must be unique");
            }
            positiveCases.retainAll(negativeCases);
            if (!positiveCases.isEmpty()) {
                throw new IllegalArgumentException("positive and negative trigger cases overlap");
            }
            coreSteps = nonEmpty(coreSteps, "coreSteps");
            completionDefinition = required(completionDefinition, "completionDefinition");
            validation = nonEmpty(validation, "validation");
            tools = immutable(tools, "tools");
            permissionIntents = nonEmpty(permissionIntents, "permissionIntents").stream()
                    .map(value -> value.toUpperCase(Locale.ROOT)).toList();
            Set<String> allowedPermissions = Set.of(
                    "NONE", "READ_FILES", "WRITE_PROJECT_FILES", "NETWORK", "SHELL");
            if (permissionIntents.stream().anyMatch(value -> !allowedPermissions.contains(value))
                    || (permissionIntents.contains("NONE") && permissionIntents.size() > 1)) {
                throw new IllegalArgumentException("permissionIntents are invalid");
            }
            risk = required(risk, "risk").toUpperCase(Locale.ROOT);
            if (!Set.of("LOW", "MEDIUM", "HIGH").contains(risk)) {
                throw new IllegalArgumentException("risk must be LOW, MEDIUM, or HIGH");
            }
            supportingFiles = safeSupportingFiles(supportingFiles);
            boolean highRiskIntent = permissionIntents.stream().anyMatch(
                    value -> Set.of("WRITE_PROJECT_FILES", "NETWORK", "SHELL").contains(value))
                    || supportingFiles.stream().anyMatch(value -> value.startsWith("scripts/"));
            boolean mediumRiskIntent = !tools.isEmpty() || permissionIntents.contains("READ_FILES");
            if ((highRiskIntent && !"HIGH".equals(risk))
                    || (mediumRiskIntent && "LOW".equals(risk))) {
                throw new IllegalArgumentException("risk understates tool or permission intent");
            }
            hostExtensions = immutable(hostExtensions, "hostExtensions");
            provenance = nonEmpty(provenance, "provenance");
        }
    }

    private static List<String> minimum(List<String> values, String field, int count) {
        List<String> copy = immutable(values, field);
        if (copy.size() < count) {
            throw new IllegalArgumentException(field + " requires at least " + count + " values");
        }
        return copy;
    }

    private static Set<String> normalized(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
        }
        return result;
    }

    private static List<String> safeSupportingFiles(List<String> values) {
        List<String> copy = immutable(values, "supportingFiles");
        for (String value : copy) {
            if (!portableSupportingPath(value)) {
                throw new IllegalArgumentException("supportingFiles must be portable package-relative paths");
            }
        }
        return copy;
    }

    public static boolean portableSupportingPath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\") || value.contains(":")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.matches(".*[<>\"|?*].*")) {
                return false;
            }
            String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (Set.of("CON", "PRN", "AUX", "NUL").contains(base)
                    || base.matches("COM[1-9]") || base.matches("LPT[1-9]")) {
                return false;
            }
        }
        return true;
    }

    private static List<String> nonEmpty(List<String> values, String field) {
        List<String> copy = immutable(values, field);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return copy;
    }

    private static List<String> immutable(List<String> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field)).stream()
                .map(value -> required(value, field)).toList();
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
