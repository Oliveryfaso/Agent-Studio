package dev.agentconfig.workbench.blueprint;

import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.ArtifactType;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.Confidence;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.DecisionStatus;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.PersistenceDecision;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.PreviewStatus;
import dev.agentconfig.workbench.blueprint.SkillBlueprintPreview.SkillBlueprint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads one explicitly authorized guided request and produces an in-memory S1 preview. */
public final class BlueprintPreviewService {
    private static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_LINES = 160;
    private static final int MAX_VALUE_CHARS = 2_048;
    private static final Set<String> KNOWN_KEYS = Set.of(
            "duration", "repeated-workflow", "clear-trigger", "success-criteria",
            "isolated-context", "independent-responsibility", "special-tool-boundary",
            "deterministic-enforcement", "executable-automation", "confirmed-artifact",
            "confirmed-scope", "name", "description", "goal", "input", "output",
            "trigger", "exclusion", "boundary-example", "should-trigger", "should-not-trigger", "step",
            "completion", "validation", "tool", "permission", "risk", "supporting-file");

    public SkillBlueprintPreview preview(InputStream input) throws IOException {
        GuidedRequest request = read(input);
        PersistenceDecision decision = classify(request);
        List<String> missing = missingFields(request, decision);
        Optional<SkillBlueprint> blueprint = Optional.empty();
        PreviewStatus status;
        if (decision.recommendedArtifact() == ArtifactType.HIGH_RISK_EXECUTABLE_PROPOSAL) {
            status = PreviewStatus.BLOCKED;
        } else if (decision.recommendedArtifact() == ArtifactType.UNKNOWN) {
            status = PreviewStatus.NEEDS_CONFIRMATION;
        } else if (decision.status() != DecisionStatus.DECIDED
                || decision.confirmedArtifact().isEmpty() || decision.confirmedScope().isEmpty()) {
            status = PreviewStatus.NEEDS_CONFIRMATION;
        } else if (decision.confirmedArtifact().orElseThrow() != ArtifactType.SKILL) {
            status = PreviewStatus.TRIAGE_READY;
        } else if (!missing.isEmpty()) {
            status = PreviewStatus.INCOMPLETE;
        } else {
            blueprint = Optional.of(toBlueprint(request));
            status = PreviewStatus.BLUEPRINT_READY;
        }
        String id = "sbp_" + hash(String.join("\n", List.of(
                decision.recommendedArtifact().name(), decision.status().name(),
                String.join("|", decision.evidence()), String.join("|", missing),
                decision.confirmedArtifact().map(Enum::name).orElse("none"),
                decision.confirmedScope().orElse("none"),
                blueprint.map(SkillBlueprint::id).orElse("none"))));
        List<String> findings = findings(status, decision);
        return new SkillBlueprintPreview(
                SkillBlueprintPreview.CURRENT_SCHEMA_VERSION,
                id,
                "persistence-triage-v1",
                status,
                decision,
                blueprint,
                missing,
                findings,
                false,
                true,
                false,
                false,
                false,
                false);
    }

    private static GuidedRequest read(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("guided request input is required");
        }
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("request exceeds the 32768-byte limit");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("request must be strict UTF-8", exception);
        }
        return parse(text);
    }

    private static GuidedRequest parse(String text) {
        String[] lines = text.split("\\R", -1);
        int lineCount = lines.length > 0 && lines[lines.length - 1].isEmpty()
                ? lines.length - 1 : lines.length;
        if (lineCount > MAX_LINES) {
            throw new IllegalArgumentException("request exceeds the 160-line limit");
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (int index = 0; index < lineCount; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator < 1) {
                throw new IllegalArgumentException("invalid guided request line " + (index + 1));
            }
            String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).strip();
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "unknown guided request field at line " + (index + 1));
            }
            if (value.isEmpty() || value.length() > MAX_VALUE_CHARS || hasControl(value)) {
                throw new IllegalArgumentException("invalid value for guided request field: " + key);
            }
            List<String> values = fields.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (values.size() >= 16) {
                throw new IllegalArgumentException("too many values for guided request field: " + key);
            }
            values.add(value);
        }
        GuidedRequest request = new GuidedRequest(fields);
        request.validateTypedFields();
        return request;
    }

    private static PersistenceDecision classify(GuidedRequest request) {
        List<String> evidence = new ArrayList<>();
        ArtifactType recommended;
        if (request.flag("executable-automation")) {
            recommended = ArtifactType.HIGH_RISK_EXECUTABLE_PROPOSAL;
            evidence.add("EXECUTABLE_AUTOMATION_TRUE");
        } else {
            List<ArtifactType> candidates = new ArrayList<>();
            if (request.flag("deterministic-enforcement")) {
                candidates.add(ArtifactType.DETERMINISTIC_TOOL_POLICY);
                evidence.add("DETERMINISTIC_ENFORCEMENT_TRUE");
            }
            if (request.countTrue("isolated-context", "independent-responsibility",
                    "special-tool-boundary") >= 1) {
                candidates.add(ArtifactType.AGENT);
                addFlags(request, evidence, "isolated-context", "independent-responsibility",
                        "special-tool-boundary");
            }
            if (request.flag("repeated-workflow") && request.flag("clear-trigger")
                    && request.flag("success-criteria")) {
                candidates.add(ArtifactType.SKILL);
                evidence.add("REPEATED_WORKFLOW_TRUE");
                evidence.add("CLEAR_TRIGGER_TRUE");
                evidence.add("SUCCESS_CRITERIA_TRUE");
            }
            if ("persistent".equals(request.duration())) {
                candidates.add(ArtifactType.INSTRUCTION);
                evidence.add("DURATION_PERSISTENT");
            }
            if ("one-shot".equals(request.duration())) {
                candidates.add(ArtifactType.PROMPT);
                evidence.add("DURATION_ONE_SHOT");
            }
            if (candidates.size() == 1) {
                recommended = candidates.getFirst();
            } else {
                recommended = ArtifactType.UNKNOWN;
                evidence.add(candidates.isEmpty()
                        ? "INSUFFICIENT_GUIDED_SIGNALS" : "CONFLICTING_GUIDED_SIGNALS");
            }
        }
        Optional<ArtifactType> confirmed = request.single("confirmed-artifact")
                .map(BlueprintPreviewService::artifact);
        Optional<String> scope = request.single("confirmed-scope")
                .map(value -> value.toUpperCase(Locale.ROOT));
        boolean matches = confirmed.isPresent() && confirmed.orElseThrow() == recommended;
        String expectedScope = recommended == ArtifactType.PROMPT ? "SESSION" : "PROJECT";
        boolean supportedScope = expectedScope.equals(scope.orElse(""));
        DecisionStatus status = recommended == ArtifactType.UNKNOWN || !matches || !supportedScope
                ? DecisionStatus.NEEDS_CONFIRMATION : DecisionStatus.DECIDED;
        Confidence confidence = recommended == ArtifactType.UNKNOWN ? Confidence.LOW
                : matches ? Confidence.HIGH : Confidence.MEDIUM;
        EnumSet<ArtifactType> alternatives = EnumSet.allOf(ArtifactType.class);
        alternatives.remove(recommended);
        alternatives.remove(ArtifactType.UNKNOWN);
        boolean userConfirmed = confirmed.isPresent() && scope.isPresent();
        return new PersistenceDecision(recommended, status, confidence, evidence,
                List.copyOf(alternatives), true, userConfirmed, confirmed, scope);
    }

    private static List<String> missingFields(GuidedRequest request, PersistenceDecision decision) {
        if (decision.confirmedArtifact().orElse(ArtifactType.UNKNOWN) != ArtifactType.SKILL) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        requireSingle(request, missing, "name");
        requireSingle(request, missing, "description");
        requireSingle(request, missing, "goal");
        if (!"PROJECT".equals(decision.confirmedScope().orElse(""))) {
            missing.add("confirmed-scope=project");
        }
        requireList(request, missing, "input", 1);
        requireList(request, missing, "output", 1);
        requireList(request, missing, "trigger", 1);
        requireList(request, missing, "exclusion", 1);
        requireList(request, missing, "boundary-example", 1);
        requireList(request, missing, "should-trigger", 3);
        requireList(request, missing, "should-not-trigger", 3);
        requireList(request, missing, "step", 1);
        requireSingle(request, missing, "completion");
        requireList(request, missing, "validation", 1);
        requireList(request, missing, "permission", 1);
        requireSingle(request, missing, "risk");
        request.single("name").ifPresent(name -> {
            if (name.length() > 63 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                missing.add("name=1-63-hyphen-case-characters");
            }
        });
        request.single("risk").ifPresent(risk -> {
            if (!Set.of("LOW", "MEDIUM", "HIGH").contains(risk.toUpperCase(Locale.ROOT))) {
                missing.add("risk=LOW|MEDIUM|HIGH");
            }
        });
        List<String> permissions = request.values("permission").stream()
                .map(value -> value.toUpperCase(Locale.ROOT)).toList();
        Set<String> allowedPermissions = Set.of(
                "NONE", "READ_FILES", "WRITE_PROJECT_FILES", "NETWORK", "SHELL");
        if (permissions.stream().anyMatch(value -> !allowedPermissions.contains(value))) {
            missing.add("permission=known-intent");
        }
        if (permissions.contains("NONE") && permissions.size() > 1) {
            missing.add("permission=NONE-or-explicit-intents");
        }
        boolean highRiskIntent = permissions.stream().anyMatch(
                value -> Set.of("WRITE_PROJECT_FILES", "NETWORK", "SHELL").contains(value))
                || request.values("supporting-file").stream()
                        .anyMatch(value -> value.startsWith("scripts/"));
        boolean mediumRiskIntent = !request.values("tool").isEmpty()
                || permissions.contains("READ_FILES");
        String declaredRisk = request.single("risk").orElse("").toUpperCase(Locale.ROOT);
        if (highRiskIntent && !"HIGH".equals(declaredRisk)) {
            missing.add("risk=HIGH-for-write-network-shell-or-script-intent");
        } else if (mediumRiskIntent && "LOW".equals(declaredRisk)) {
            missing.add("risk=MEDIUM|HIGH-for-tool-or-read-intent");
        }
        if (duplicates(request.values("should-trigger"))) {
            missing.add("should-trigger=unique");
        }
        if (duplicates(request.values("should-not-trigger"))) {
            missing.add("should-not-trigger=unique");
        }
        Set<String> positives = normalized(request.values("should-trigger"));
        positives.retainAll(normalized(request.values("should-not-trigger")));
        if (!positives.isEmpty()) {
            missing.add("trigger-cases=non-overlapping");
        }
        Set<String> triggers = normalized(request.values("trigger"));
        triggers.retainAll(normalized(request.values("exclusion")));
        if (!triggers.isEmpty()) {
            missing.add("trigger-exclusion=non-overlapping");
        }
        return List.copyOf(missing);
    }

    private static SkillBlueprint toBlueprint(GuidedRequest request) {
        String canonical = canonicalBlueprint(request);
        return new SkillBlueprint(
                SkillBlueprint.CURRENT_SCHEMA_VERSION,
                "skb_" + hash(canonical),
                request.single("name").orElseThrow(),
                request.single("description").orElseThrow(),
                request.single("goal").orElseThrow(),
                "PROJECT",
                request.values("input"),
                request.values("output"),
                request.values("trigger"),
                request.values("exclusion"),
                request.values("boundary-example"),
                request.values("should-trigger"),
                request.values("should-not-trigger"),
                request.values("step"),
                request.single("completion").orElseThrow(),
                request.values("validation"),
                request.values("tool"),
                request.values("permission"),
                request.single("risk").orElseThrow().toUpperCase(Locale.ROOT),
                validatedSupportingFiles(request.values("supporting-file")),
                List.of("codex-project-skill"),
                List.of("USER_AUTHORED_GUIDED_REQUEST"));
    }

    private static String canonicalBlueprint(GuidedRequest request) {
        List<String> keys = List.of("name", "description", "goal", "input", "output",
                "trigger", "exclusion", "boundary-example", "should-trigger",
                "should-not-trigger", "step", "completion", "validation", "tool",
                "permission", "risk", "supporting-file");
        StringBuilder canonical = new StringBuilder("SkillBlueprint:v1\nPROJECT\n");
        for (String key : keys) {
            canonical.append(key).append(':');
            for (String value : request.values(key)) {
                canonical.append(value.length()).append(':').append(value).append('|');
            }
            canonical.append('\n');
        }
        return canonical.toString();
    }

    private static List<String> findings(
            PreviewStatus status, PersistenceDecision decision) {
        List<String> findings = new ArrayList<>();
        if (status == PreviewStatus.NEEDS_CONFIRMATION) {
            findings.add("NEEDS_USER_CONFIRMATION");
        }
        if (status == PreviewStatus.INCOMPLETE) {
            findings.add("MISSING_REQUIRED_FIELD");
        }
        if (status == PreviewStatus.BLOCKED) {
            findings.add("EXECUTABLE_ARTIFACT_OUT_OF_SCOPE");
        }
        if (decision.confirmedArtifact().isPresent()
                && decision.confirmedArtifact().orElseThrow() != decision.recommendedArtifact()) {
            findings.add("TYPE_CONFIRMATION_MISMATCH");
        }
        if (decision.confirmedScope().isPresent()) {
            String expected = decision.recommendedArtifact() == ArtifactType.PROMPT
                    ? "SESSION" : "PROJECT";
            if (!expected.equals(decision.confirmedScope().orElseThrow())) {
                findings.add("UNSUPPORTED_SCOPE");
            }
        }
        return List.copyOf(findings);
    }

    private static ArtifactType artifact(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "prompt" -> ArtifactType.PROMPT;
            case "instruction" -> ArtifactType.INSTRUCTION;
            case "skill" -> ArtifactType.SKILL;
            case "agent" -> ArtifactType.AGENT;
            case "tool-policy" -> ArtifactType.DETERMINISTIC_TOOL_POLICY;
            case "high-risk-proposal" -> ArtifactType.HIGH_RISK_EXECUTABLE_PROPOSAL;
            default -> throw new IllegalArgumentException("unsupported confirmed-artifact value");
        };
    }

    private static List<String> validatedSupportingFiles(List<String> values) {
        for (String value : values) {
            if (!SkillBlueprintPreview.portableSupportingPath(value)) {
                throw new IllegalArgumentException(
                        "supporting-file must be a portable package-relative path");
            }
        }
        return values;
    }

    private static void addFlags(
            GuidedRequest request, List<String> evidence, String... keys) {
        for (String key : keys) {
            if (request.flag(key)) {
                evidence.add(key.toUpperCase(Locale.ROOT).replace('-', '_') + "_TRUE");
            }
        }
    }

    private static void requireSingle(GuidedRequest request, List<String> missing, String key) {
        if (request.single(key).isEmpty()) {
            missing.add(key);
        }
    }

    private static void requireList(
            GuidedRequest request, List<String> missing, String key, int minimum) {
        if (request.values(key).size() < minimum) {
            missing.add(key + "[" + minimum + "]");
        }
    }

    private static boolean duplicates(List<String> values) {
        return normalized(values).size() != values.size();
    }

    private static Set<String> normalized(List<String> values) {
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String value : values) {
            normalized.add(value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
        }
        return normalized;
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record GuidedRequest(Map<String, List<String>> fields) {
        private GuidedRequest {
            fields = Map.copyOf(fields);
            for (String key : fields.keySet()) {
                if (!Set.of("input", "output", "trigger", "exclusion", "boundary-example", "should-trigger",
                        "should-not-trigger", "step", "validation", "tool", "permission",
                        "supporting-file").contains(key) && fields.get(key).size() > 1) {
                    throw new IllegalArgumentException("guided request field must be singular: " + key);
                }
            }
        }

        List<String> values(String key) {
            return fields.getOrDefault(key, List.of());
        }

        Optional<String> single(String key) {
            List<String> values = values(key);
            return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
        }

        String duration() {
            String value = single("duration").orElse("").toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !Set.of("one-shot", "persistent").contains(value)) {
                throw new IllegalArgumentException("duration must be one-shot or persistent");
            }
            return value;
        }

        boolean flag(String key) {
            return single(key).map(value -> switch (value.toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException(key + " must be true or false");
            }).orElse(false);
        }

        int countTrue(String... keys) {
            int count = 0;
            for (String key : keys) {
                if (flag(key)) {
                    count++;
                }
            }
            return count;
        }

        void validateTypedFields() {
            for (String key : List.of("repeated-workflow", "clear-trigger", "success-criteria",
                    "isolated-context", "independent-responsibility", "special-tool-boundary",
                    "deterministic-enforcement", "executable-automation")) {
                flag(key);
            }
            duration();
        }
    }
}
