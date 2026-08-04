package dev.agentconfig.workbench.analyze;

import dev.agentconfig.workbench.context.ContextRelation;
import dev.agentconfig.workbench.context.ContextRelationKind;
import dev.agentconfig.workbench.context.ContextResolutionStatus;
import dev.agentconfig.workbench.context.ContextSource;
import dev.agentconfig.workbench.context.ContextSourceKind;
import dev.agentconfig.workbench.context.ContextSourceState;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
import dev.agentconfig.workbench.context.claude.ClaudeRuleEvaluator;
import dev.agentconfig.workbench.ir.ActivationEvidence;
import dev.agentconfig.workbench.ir.ActivationEvidenceKind;
import dev.agentconfig.workbench.ir.ActivationOutcome;
import dev.agentconfig.workbench.ir.InstructionIr;
import dev.agentconfig.workbench.ir.InstructionScope;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.InstructionSourceKind;
import dev.agentconfig.workbench.ir.InstructionSourceState;
import dev.agentconfig.workbench.ir.IrNodeRef;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import dev.agentconfig.workbench.ir.ProvenanceEdge;
import dev.agentconfig.workbench.ir.ProvenanceKind;
import dev.agentconfig.workbench.ir.ScopeKind;
import dev.agentconfig.workbench.ir.SourceIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts one effective-context result into a content-free IR and deterministic analysis report. */
public final class InstructionAnalysisEngine {
    private static final long MAX_EFFECTIVE_BYTES_PER_SOURCE = 4L * 1024L * 1024L;
    private static final long MAX_TOTAL_EFFECTIVE_BYTES = 16L * 1024L * 1024L;

    public InstructionAnalysisReport analyze(EffectiveInstructionContext context) throws IOException {
        Map<String, ContextSource> uniqueSources = uniqueSources(context.sources());
        Map<String, String> sourceIdsByPath = new HashMap<>();
        Map<String, Payload> payloads = new HashMap<>();
        List<AnalysisNotice> notices = new ArrayList<>();
        Set<String> limitationCodes = new LinkedHashSet<>();
        long totalBytes = 0;

        for (Map.Entry<String, ContextSource> entry : uniqueSources.entrySet()) {
            ContextSource source = entry.getValue();
            if (!portableIrPath(entry.getKey())) {
                limitationCodes.add("non-portable-source-path");
                continue;
            }
            sourceIdsByPath.put(entry.getKey(), sourceId(context.hostId(), source));
            if (!source.active() || source.includedBytes() == 0 || source.realPath() == null) {
                continue;
            }
            if (source.includedBytes() > MAX_EFFECTIVE_BYTES_PER_SOURCE
                    || totalBytes + source.includedBytes() > MAX_TOTAL_EFFECTIVE_BYTES) {
                notices.add(new AnalysisNotice("EFFECTIVE_PAYLOAD_ANALYSIS_LIMIT",
                        sourceIdsByPath.get(entry.getKey()), 0));
                limitationCodes.add("effective-payload-analysis-limit");
                continue;
            }
            byte[] bytes = readPrefix(source.realPath(), source.includedBytes());
            totalBytes += bytes.length;
            String effectiveHash = sha256(bytes);
            String markdown = decodeUtf8(bytes);
            if (markdown == null) {
                notices.add(new AnalysisNotice("DIRECTIVE_UTF8_REQUIRED",
                        sourceIdsByPath.get(entry.getKey()), 0));
                limitationCodes.add("directive-utf8-required");
            }
            payloads.put(entry.getKey(), new Payload(effectiveHash, markdown));
        }

        List<InstructionSource> irSources = new ArrayList<>();
        List<DirectiveSourceInput> directiveInputs = new ArrayList<>();
        for (Map.Entry<String, ContextSource> entry : uniqueSources.entrySet()) {
            String path = entry.getKey();
            ContextSource source = entry.getValue();
            if (!portableIrPath(path)) {
                continue;
            }
            String sourceId = sourceIdsByPath.get(path);
            Payload payload = payloads.get(path);
            InstructionSourceState state = irState(source.state());
            InstructionScope scope = scope(context, source, payload);
            int loadOrder = state.participatesInLoadOrder()
                    ? source.precedence() : InstructionSource.UNORDERED;
            String effectiveHash = state.participatesInLoadOrder() && payload != null
                    ? payload.effectiveHash() : "";
            irSources.add(new InstructionSource(
                    new SourceIdentity(context.hostId(), sourceId),
                    irKind(source.kind()),
                    state,
                    source.sha256(),
                    effectiveHash,
                    source.byteSize(),
                    state.participatesInLoadOrder() ? source.includedBytes() : 0,
                    path,
                    scope,
                    loadOrder,
                    activation(context, source, payload)));
            if (state.participatesInLoadOrder() && payload != null && payload.markdown() != null) {
                directiveInputs.add(new DirectiveSourceInput(sourceId, payload.markdown(),
                        new DirectiveSourceMetadata(loadOrder, 1)));
            }
        }

        DirectiveAnalysis directiveAnalysis = new DirectiveAnalyzer().analyze(directiveInputs);
        directiveAnalysis.notices().forEach(notice -> notices.add(
                new AnalysisNotice(notice.code(), notice.sourceId(), notice.line())));
        if (!directiveAnalysis.notices().isEmpty()) {
            limitationCodes.add("directive-analysis-budget");
        }

        List<dev.agentconfig.workbench.ir.DirectiveUnit> irDirectives = directiveAnalysis.units().stream()
                .map(unit -> new dev.agentconfig.workbench.ir.DirectiveUnit(
                        unit.id(),
                        new SourceIdentity(context.hostId(), unit.sourceId()),
                        unit.normalizedSha256(),
                        switch (unit.polarity()) {
                            case REQUIRE -> dev.agentconfig.workbench.ir.DirectivePolarity.REQUIRE;
                            case PROHIBIT -> dev.agentconfig.workbench.ir.DirectivePolarity.FORBID;
                            case NEUTRAL -> dev.agentconfig.workbench.ir.DirectivePolarity.INFORM;
                        },
                        unit.line()))
                .toList();

        List<ProvenanceEdge> provenance = provenance(
                context.relations(), sourceIdsByPath, irDirectives);
        IrResolutionStatus status = context.resolutionStatus() == ContextResolutionStatus.PARTIAL
                || !limitationCodes.isEmpty() ? IrResolutionStatus.PARTIAL : IrResolutionStatus.COMPLETE;
        if (context.resolutionStatus() == ContextResolutionStatus.PARTIAL) {
            limitationCodes.add("context-resolution-partial");
        }
        String irId = "ir_" + sha256((context.hostId() + "\n"
                + portable(context.realRoot().relativize(context.currentDirectory()))).getBytes(StandardCharsets.UTF_8));
        InstructionIr ir = new InstructionIr(
                InstructionIr.CURRENT_SCHEMA_VERSION,
                irId,
                status,
                irSources,
                irDirectives,
                provenance,
                List.copyOf(limitationCodes));

        List<AnalysisFinding> findings = new ArrayList<>();
        findings.addAll(exactPayloadDuplicates(irSources));
        findings.addAll(directiveFindings(directiveAnalysis));
        findings.sort(Comparator.comparing((AnalysisFinding finding) -> finding.type().name())
                .thenComparing(AnalysisFinding::id));
        notices.sort(Comparator.comparing(AnalysisNotice::sourceId)
                .thenComparingInt(AnalysisNotice::line).thenComparing(AnalysisNotice::code));
        long deterministic = findings.stream()
                .filter(value -> value.certainty() == AnalysisCertainty.DETERMINISTIC).count();
        int activeCount = (int) irSources.stream().filter(
                value -> value.state().participatesInLoadOrder()).count();
        AnalysisSummary summary = new AnalysisSummary(
                irSources.size(), activeCount, irDirectives.size(), Math.toIntExact(deterministic),
                findings.size() - Math.toIntExact(deterministic));
        return new InstructionAnalysisReport(
                InstructionAnalysisReport.CURRENT_SCHEMA_VERSION, 2, context.semanticProfile(),
                ir, findings, notices, summary);
    }

    private static Map<String, ContextSource> uniqueSources(List<ContextSource> sources) {
        Map<String, ContextSource> result = new LinkedHashMap<>();
        for (ContextSource source : sources) {
            String path = portable(source.logicalPath());
            ContextSource existing = result.get(path);
            if (existing == null || (!existing.active() && source.active())) {
                result.put(path, source);
            }
        }
        return result;
    }

    private static InstructionScope scope(
            EffectiveInstructionContext context, ContextSource source, Payload payload) {
        if (context.hostId().equals("codex")) {
            Path parent = source.logicalPath().getParent();
            return parent == null
                    ? InstructionScope.project()
                    : new InstructionScope(ScopeKind.DIRECTORY_TREE, portable(parent) + "/**");
        }
        if (source.kind() == ContextSourceKind.CLAUDE_RULE && payload != null
                && payload.markdown() != null) {
            var definition = new ClaudeRuleEvaluator().parse(payload.markdown());
            if (definition.valid() && !definition.paths().isEmpty()) {
                String patternsHash = sha256(String.join("\n", definition.paths())
                        .getBytes(StandardCharsets.UTF_8));
                return new InstructionScope(ScopeKind.PATH_GLOB, "sha256:" + patternsHash);
            }
            if (definition.valid()) {
                return InstructionScope.project();
            }
        }
        Path relativeCwd = context.realRoot().relativize(context.currentDirectory());
        return new InstructionScope(ScopeKind.CURRENT_CONTEXT,
                relativeCwd.toString().isEmpty() ? "." : portable(relativeCwd));
    }

    private static List<ActivationEvidence> activation(
            EffectiveInstructionContext context, ContextSource source, Payload payload) {
        List<ActivationEvidence> evidence = new ArrayList<>();
        if (source.kind() == ContextSourceKind.CLAUDE_IMPORT) {
            evidence.add(new ActivationEvidence(ActivationEvidenceKind.EXPLICIT_REFERENCE,
                    source.active() ? ActivationOutcome.MATCHED : ActivationOutcome.NOT_EVALUATED, ""));
        } else if (source.kind() == ContextSourceKind.CLAUDE_RULE) {
            boolean unconditional = payload != null && payload.markdown() != null
                    && new ClaudeRuleEvaluator().parse(payload.markdown()).unconditional();
            ActivationEvidenceKind kind = unconditional
                    ? ActivationEvidenceKind.ALWAYS : ActivationEvidenceKind.PATH_MATCH;
            ActivationOutcome outcome = switch (source.state()) {
                case ACTIVE -> ActivationOutcome.MATCHED;
                case CONDITIONAL_NO_MATCH -> ActivationOutcome.NOT_MATCHED;
                default -> ActivationOutcome.NOT_EVALUATED;
            };
            String expression = context.targetFile() == null ? "" : portable(context.targetFile());
            evidence.add(new ActivationEvidence(kind, outcome, expression));
        } else {
            evidence.add(new ActivationEvidence(ActivationEvidenceKind.CURRENT_DIRECTORY_ANCESTOR,
                    source.active() ? ActivationOutcome.MATCHED : ActivationOutcome.NOT_EVALUATED,
                    portable(source.logicalPath())));
        }
        if (source.state() == ContextSourceState.SHADOWED) {
            evidence.add(new ActivationEvidence(ActivationEvidenceKind.HOST_PRECEDENCE,
                    ActivationOutcome.NOT_MATCHED, ""));
        }
        if (source.state() == ContextSourceState.ACTIVE_TRUNCATED) {
            evidence.add(new ActivationEvidence(ActivationEvidenceKind.BYTE_BUDGET,
                    ActivationOutcome.MATCHED, Long.toString(source.includedBytes())));
        }
        return List.copyOf(evidence);
    }

    private static List<ProvenanceEdge> provenance(
            List<ContextRelation> relations,
            Map<String, String> sourceIdsByPath,
            List<dev.agentconfig.workbench.ir.DirectiveUnit> directives) {
        Set<ProvenanceEdge> result = new LinkedHashSet<>();
        for (ContextRelation relation : relations) {
            String from = sourceIdsByPath.get(portable(relation.fromLogicalPath()));
            String to = sourceIdsByPath.get(portable(relation.toLogicalPath()));
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            result.add(new ProvenanceEdge(
                    relation.kind() == ContextRelationKind.IMPORTS
                            ? ProvenanceKind.IMPORTS : ProvenanceKind.SHADOWS,
                    IrNodeRef.source(from), IrNodeRef.source(to)));
        }
        for (dev.agentconfig.workbench.ir.DirectiveUnit directive : directives) {
            result.add(new ProvenanceEdge(ProvenanceKind.DERIVED_FROM,
                    IrNodeRef.directive(directive.id()),
                    IrNodeRef.source(directive.source().sourceId())));
        }
        return List.copyOf(result);
    }

    private static List<AnalysisFinding> exactPayloadDuplicates(List<InstructionSource> sources) {
        Map<String, List<InstructionSource>> groups = new LinkedHashMap<>();
        for (InstructionSource source : sources) {
            if (!source.state().participatesInLoadOrder() || source.effectiveSha256().isEmpty()) {
                continue;
            }
            String key = source.effectiveSha256() + ":" + source.includedBytes();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source);
        }
        List<AnalysisFinding> result = new ArrayList<>();
        for (Map.Entry<String, List<InstructionSource>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            List<AnalysisReference> references = entry.getValue().stream()
                    .map(value -> new AnalysisReference(value.identity().sourceId(), "", 0)).toList();
            result.add(new AnalysisFinding("af_" + sha256(entry.getKey().getBytes(StandardCharsets.UTF_8)),
                    AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE,
                    AnalysisCertainty.DETERMINISTIC,
                    entry.getValue().getFirst().effectiveSha256(), references));
        }
        return result;
    }

    private static List<AnalysisFinding> directiveFindings(DirectiveAnalysis analysis) {
        List<AnalysisFinding> result = new ArrayList<>();
        for (DirectiveFinding finding : analysis.findings()) {
            AnalysisFindingType type = finding.type() == DirectiveFindingType.DIRECT_POLARITY_CONFLICT
                    ? AnalysisFindingType.DIRECT_POLARITY_CONFLICT
                    : AnalysisFindingType.NORMALIZED_DIRECTIVE_DUPLICATE;
            List<AnalysisReference> references = finding.references().stream()
                    .map(value -> new AnalysisReference(
                            value.sourceId(), value.unitId(), value.line())).toList();
            result.add(new AnalysisFinding(
                    "af_" + sha256(finding.id().getBytes(StandardCharsets.UTF_8)),
                    type,
                    AnalysisCertainty.HEURISTIC_CANDIDATE,
                    finding.subjectHash(),
                    references));
        }
        return result;
    }

    private static InstructionSourceKind irKind(ContextSourceKind kind) {
        return switch (kind) {
            case CLAUDE_LOCAL_MEMORY -> InstructionSourceKind.LOCAL_OVERRIDE;
            case CLAUDE_RULE -> InstructionSourceKind.MODULAR_RULE;
            case CLAUDE_IMPORT -> InstructionSourceKind.IMPORTED_GUIDANCE;
            default -> InstructionSourceKind.PROJECT_GUIDANCE;
        };
    }

    private static InstructionSourceState irState(ContextSourceState state) {
        return switch (state) {
            case ACTIVE -> InstructionSourceState.ACTIVE;
            case ACTIVE_TRUNCATED -> InstructionSourceState.ACTIVE_TRUNCATED;
            case SHADOWED -> InstructionSourceState.SHADOWED;
            case NOT_EVALUATED -> InstructionSourceState.NOT_EVALUATED;
            case INVALID, SKIPPED_SYMLINK, SKIPPED_SPECIAL_FILE, SKIPPED_TOO_LARGE ->
                    InstructionSourceState.INVALID;
            default -> InstructionSourceState.INACTIVE;
        };
    }

    private static String sourceId(String hostId, ContextSource source) {
        return "src_" + sha256((hostId + "\n" + source.kind().name() + "\n"
                + portable(source.logicalPath())).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readPrefix(Path path, long byteCount) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Effective source is no longer a regular file");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.toIntExact(byteCount));
        byte[] buffer = new byte[8192];
        long remaining = byteCount;
        try (InputStream input = Files.newInputStream(path)) {
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Effective source changed during analysis");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean portableIrPath(String value) {
        return !value.isBlank() && !value.startsWith("/") && !value.startsWith("\\")
                && !value.contains("\\") && !value.endsWith("/")
                && java.util.Arrays.stream(value.split("/", -1))
                        .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record Payload(String effectiveHash, String markdown) {}
}
