package dev.agentconfig.workbench.context;

import dev.agentconfig.workbench.context.claude.ClaudeDiagnostic;
import dev.agentconfig.workbench.context.claude.ClaudeDiagnosticSeverity;
import dev.agentconfig.workbench.context.claude.ClaudeImport;
import dev.agentconfig.workbench.context.claude.ClaudeImportParser;
import dev.agentconfig.workbench.context.claude.ClaudeImportScan;
import dev.agentconfig.workbench.context.claude.ClaudeRuleApplicability;
import dev.agentconfig.workbench.context.claude.ClaudeRuleEvaluation;
import dev.agentconfig.workbench.context.claude.ClaudeRuleEvaluator;
import dev.agentconfig.workbench.context.codex.CodexProjectOptions;
import dev.agentconfig.workbench.context.codex.CodexProjectOptionsDiagnostic;
import dev.agentconfig.workbench.context.codex.CodexProjectOptionsParseResult;
import dev.agentconfig.workbench.context.codex.CodexProjectOptionsParser;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a metadata-only view of project instruction precedence. Instruction contents are never
 * returned or executed. This experimental compiler intentionally omits user/global configuration.
 */
public final class EffectiveInstructionCompiler {
    private static final long MAX_HASHED_SOURCE_BYTES = 1024L * 1024L;

    public EffectiveInstructionContext compile(String hostId, Path root, Path currentDirectory)
            throws IOException {
        return compile(ContextCompileRequest.defaults(hostId, root, currentDirectory));
    }

    public EffectiveInstructionContext compile(ContextCompileRequest request) throws IOException {
        String hostId = request.hostId();
        Path root = request.authorizedRoot();
        Path currentDirectory = request.currentDirectory();
        Path logicalRoot = root.toAbsolutePath().normalize();
        Path realRoot = logicalRoot.toRealPath();
        Path realCurrentDirectory = currentDirectory.toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(realRoot) || !Files.isDirectory(realCurrentDirectory)) {
            throw new IOException("Workspace root and current directory must both be directories");
        }
        if (!realCurrentDirectory.startsWith(realRoot)) {
            throw new IllegalArgumentException("Current directory must stay within the authorized workspace");
        }

        List<Path> hierarchy = hierarchy(realRoot, realCurrentDirectory);
        if ("codex".equals(hostId)) {
            return compileCodex(request, logicalRoot, realRoot, realCurrentDirectory, hierarchy);
        }
        return compileClaude(request, logicalRoot, realRoot, realCurrentDirectory, hierarchy);
    }

    private static EffectiveInstructionContext compileCodex(
            ContextCompileRequest request,
            Path logicalRoot,
            Path realRoot,
            Path cwd,
            List<Path> hierarchy) throws IOException {
        CodexProjectOptions options = CodexProjectOptions.defaults();
        List<ContextFinding> findings = new ArrayList<>();
        if (request.codexConfigSnapshot().isPresent()) {
            Path snapshot = request.codexConfigSnapshot().orElseThrow().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(snapshot) || !Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) {
                findings.add(new ContextFinding(ContextFindingSeverity.ERROR,
                        "INVALID_CODEX_CONFIG_SNAPSHOT", null,
                        "The explicitly supplied Codex config snapshot must be a regular non-symlink file"));
            } else {
                long size = Files.size(snapshot);
                CodexProjectOptionsParseResult parsed = size > CodexProjectOptionsParser.MAX_SNAPSHOT_BYTES
                        ? new CodexProjectOptionsParser().parse(new byte[CodexProjectOptionsParser.MAX_SNAPSHOT_BYTES + 1])
                        : new CodexProjectOptionsParser().parse(Files.readAllBytes(snapshot));
                options = parsed.options();
                for (CodexProjectOptionsDiagnostic diagnostic : parsed.diagnostics()) {
                    findings.add(new ContextFinding(ContextFindingSeverity.ERROR,
                            "CODEX_CONFIG_" + diagnostic.code().name(), null,
                            diagnostic.message() + (diagnostic.line() > 0
                                    ? " at line " + diagnostic.line() : "")));
                }
            }
        }

        List<String> fallbackNames = validatedFallbackNames(options.fallbackFilenames(), findings);
        List<ContextSource> sources = new ArrayList<>();
        List<ContextRelation> relations = new ArrayList<>();
        long included = 0;
        int precedence = 0;
        for (Path directory : hierarchy) {
            List<Candidate> candidates = new ArrayList<>();
            addIfPresent(candidates, inspect(realRoot, directory.resolve("AGENTS.override.md"),
                    ContextSourceKind.CODEX_OVERRIDE));
            addIfPresent(candidates, inspect(realRoot, directory.resolve("AGENTS.md"),
                    ContextSourceKind.CODEX_AGENTS));
            for (String fallbackName : fallbackNames) {
                addIfPresent(candidates, inspect(realRoot, directory.resolve(fallbackName),
                        ContextSourceKind.CODEX_FALLBACK));
            }
            Candidate selected = candidates.stream().filter(EffectiveInstructionCompiler::selectable)
                    .findFirst().orElse(null);
            for (Candidate candidate : candidates) {
                if (candidate == selected) {
                    Activation activation = activate(
                            candidate, included, options.maxBytes(), precedence + 1);
                    sources.add(activation.source());
                    included += activation.source().includedBytes();
                    if (activation.source().active()) {
                        precedence++;
                    }
                } else if (selectable(candidate) && selected != null) {
                    sources.add(inactive(candidate, ContextSourceState.SHADOWED, 0,
                            "Ignored because a higher-priority instruction filename is usable in the same directory"));
                    relations.add(new ContextRelation(ContextRelationKind.SHADOWS,
                            selected.logicalPath(), candidate.logicalPath(),
                            "Higher-priority filename in the same directory"));
                } else {
                    sources.add(inactive(candidate, inactiveState(candidate), 0,
                            inactiveDetail(candidate, "File is not active")));
                }
            }
        }
        addSymlinkFindings(sources, findings);
        return new EffectiveInstructionContext(
                "codex", ProjectSemanticProfile.CODEX_PROJECT_V1.id(),
                "EXPERIMENTAL_PROJECT_SEMANTICS", "ROOT_TO_CWD_PRECEDENCE",
                resolution(findings),
                logicalRoot, realRoot, cwd, null, request.codexConfigSnapshot().isPresent(),
                Instant.now(), options.maxBytes(), included, sources,
                relations, findings,
                request.codexConfigSnapshot().isPresent() ? List.of(
                        "Global CODEX_HOME instructions are not included",
                        "Only the explicitly supplied config snapshot is used; config-layer merging is not modeled",
                        "The byte limit is modeled across the effective chain pending a versioned Codex fixture",
                        "The supplied authorized root is treated as the project root")
                        : List.of(
                                "Global CODEX_HOME instructions are not included",
                                "Custom project_doc_fallback_filenames and project_doc_max_bytes are not read",
                                "The supplied authorized root is treated as the project root"));
    }

    private static EffectiveInstructionContext compileClaude(
            ContextCompileRequest request,
            Path logicalRoot,
            Path realRoot,
            Path cwd,
            List<Path> hierarchy) throws IOException {
        List<ContextSource> sources = new ArrayList<>();
        List<ContextRelation> relations = new ArrayList<>();
        List<ContextFinding> findings = new ArrayList<>();
        ResolutionProgress progress = new ResolutionProgress();
        Set<Path> resolvedImports = new HashSet<>();
        String targetFile = request.targetFile().map(path -> normalizeTargetFile(realRoot, path))
                .orElse(null);
        for (Path directory : hierarchy) {
            Candidate memory = inspect(realRoot, directory.resolve("CLAUDE.md"),
                    ContextSourceKind.CLAUDE_MEMORY);
            Candidate projectMemory = directory.equals(realRoot)
                    ? inspect(realRoot, directory.resolve(".claude/CLAUDE.md"),
                            ContextSourceKind.CLAUDE_PROJECT_MEMORY)
                    : null;
            if (memory != null) {
                ContextSource source = activateWithoutAggregateLimit(memory, progress.nextOrder());
                sources.add(source);
                if (source.active()) {
                    progress.include(source);
                    resolveImports(realRoot, memory, 0, new LinkedHashSet<>(Set.of(memory.realPath())),
                            resolvedImports, sources, relations, findings, progress);
                }
            }
            if (projectMemory != null) {
                if (!selectable(memory)) {
                    ContextSource source = activateWithoutAggregateLimit(
                            projectMemory, progress.nextOrder());
                    sources.add(source);
                    if (source.active()) {
                        progress.include(source);
                        resolveImports(realRoot, projectMemory, 0,
                                new LinkedHashSet<>(Set.of(projectMemory.realPath())), resolvedImports,
                                sources, relations, findings, progress);
                    }
                } else {
                    sources.add(inactive(projectMemory, ContextSourceState.NOT_EVALUATED, 0,
                            "Both project memory locations exist; host behavior is ambiguous in current evidence"));
                    findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                            "AMBIGUOUS_CLAUDE_PROJECT_MEMORY", projectMemory.logicalPath(),
                            "Both CLAUDE.md and .claude/CLAUDE.md exist; this version lists root CLAUDE.md as active"));
                }
            }
            Candidate local = inspect(realRoot, directory.resolve("CLAUDE.local.md"),
                    ContextSourceKind.CLAUDE_LOCAL_MEMORY);
            if (local != null) {
                ContextSource source = activateWithoutAggregateLimit(local, progress.nextOrder());
                sources.add(source);
                if (source.active()) {
                    progress.include(source);
                    resolveImports(realRoot, local, 0, new LinkedHashSet<>(Set.of(local.realPath())),
                            resolvedImports, sources, relations, findings, progress);
                }
            }
        }

        Path rulesDirectory = realRoot.resolve(".claude/rules");
        if (Files.isSymbolicLink(rulesDirectory)) {
            findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                    "HOST_SYMLINK_LOADING_NOT_MODELED", realRoot.relativize(rulesDirectory),
                    "Claude Code may load this symlinked rules directory, but the workbench does not follow it"));
        } else if (Files.isDirectory(rulesDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(rulesDirectory)) {
            try (var paths = Files.walk(rulesDirectory)) {
                for (Path rule : paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName() != null
                                && path.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(Path::toString)).toList()) {
                    Candidate candidate = inspect(realRoot, rule, ContextSourceKind.CLAUDE_RULE);
                    if (candidate != null) {
                        evaluateRule(candidate, targetFile, sources, findings, progress);
                    }
                }
            }
        }
        addSymlinkFindings(sources, findings);
        return new EffectiveInstructionContext(
                "claude-code", ProjectSemanticProfile.CLAUDE_CODE_PROJECT_V1.id(),
                "EXPERIMENTAL_PROJECT_SEMANTICS",
                "CONCATENATION_WITH_RULE_ORDER_APPROXIMATION", resolution(findings),
                logicalRoot, realRoot, cwd, targetFile == null ? null : Path.of(targetFile), false,
                Instant.now(), 0, progress.includedBytes(), sources,
                relations, findings,
                List.of(
                        "User-level ~/.claude instructions are not included",
                        "External imports are reported but not read because approval state is unavailable",
                        "The import lexer currently supports unquoted paths without whitespace",
                        "Exact ordering between project memory and project rules is not version-fixtured",
                        "claudeMdExcludes, managed memory, symlink loading, and on-demand descendant memory are not modeled",
                        "The supplied authorized root bounds ancestor discovery"));
    }

    private static List<String> validatedFallbackNames(
            List<String> configured, List<ContextFinding> findings) {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : configured) {
            boolean invalid = invalidFallbackName(name);
            if (invalid) {
                findings.add(new ContextFinding(ContextFindingSeverity.ERROR,
                        "INVALID_CODEX_FALLBACK_FILENAME", null,
                        "Fallback entries must be plain filenames without path separators"));
            } else if (name.equals("AGENTS.md") || name.equals("AGENTS.override.md")) {
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "REDUNDANT_CODEX_FALLBACK_FILENAME", null,
                        "A fallback duplicates a built-in Codex instruction filename and was ignored"));
            } else if (!unique.add(name)) {
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "DUPLICATE_CODEX_FALLBACK_FILENAME", null,
                        "A duplicate fallback filename was ignored"));
            }
        }
        return List.copyOf(unique);
    }

    private static boolean invalidFallbackName(String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            return true;
        }
        try {
            return Path.of(name).isAbsolute();
        } catch (java.nio.file.InvalidPathException exception) {
            return true;
        }
    }

    private static String normalizeTargetFile(Path realRoot, Path requested) {
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException("Target file must be project-relative");
        }
        Path normalized = requested.normalize();
        if (normalized.toString().isEmpty() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Target file must stay within the authorized workspace");
        }
        Path resolved = realRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(realRoot)) {
            throw new IllegalArgumentException("Target file must stay within the authorized workspace");
        }
        return portable(normalized);
    }

    private static void evaluateRule(
            Candidate candidate,
            String targetFile,
            List<ContextSource> sources,
            List<ContextFinding> findings,
            ResolutionProgress progress) throws IOException {
        if (!selectable(candidate)) {
            sources.add(inactive(candidate, inactiveState(candidate), 0,
                    inactiveDetail(candidate, "Rule is not usable")));
            return;
        }
        String markdown = readUtf8(candidate, "RULE_CONTENT_UNREADABLE", findings);
        if (markdown == null) {
            sources.add(inactive(candidate, ContextSourceState.INVALID, 0,
                    "Rule activation could not be evaluated"));
            return;
        }

        ClaudeRuleEvaluator evaluator = new ClaudeRuleEvaluator();
        ClaudeRuleEvaluation evaluation;
        if (targetFile == null) {
            var definition = evaluator.parse(markdown);
            addClaudeDiagnostics(candidate.logicalPath(), definition.diagnostics(), findings);
            if (!definition.valid()) {
                sources.add(inactive(candidate, ContextSourceState.INVALID, 0,
                        "Rule frontmatter is invalid"));
                return;
            }
            if (!definition.unconditional()) {
                sources.add(inactive(candidate, ContextSourceState.NOT_EVALUATED, 0,
                        "Path-scoped rule needs --target-file before activation can be evaluated"));
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "CLAUDE_TARGET_FILE_REQUIRED", candidate.logicalPath(),
                        "A path-scoped rule was found without a target file"));
                return;
            }
            ContextSource active = source(candidate, ContextSourceState.ACTIVE,
                    progress.nextOrder(), candidate.byteSize(), "Unconditional project rule");
            sources.add(active);
            progress.include(active);
            return;
        }

        evaluation = evaluator.evaluate(markdown, targetFile);
        addClaudeDiagnostics(candidate.logicalPath(), evaluation.diagnostics(), findings);
        switch (evaluation.applicability()) {
            case ALWAYS -> {
                ContextSource active = source(candidate, ContextSourceState.ACTIVE,
                        progress.nextOrder(), candidate.byteSize(), "Unconditional project rule");
                sources.add(active);
                progress.include(active);
            }
            case CONDITIONAL_MATCH -> {
                ContextSource active = source(candidate, ContextSourceState.ACTIVE,
                        progress.nextOrder(), candidate.byteSize(),
                        "Path-scoped rule matches the supplied target file");
                sources.add(active);
                progress.include(active);
            }
            case CONDITIONAL_NO_MATCH -> sources.add(inactive(
                    candidate, ContextSourceState.CONDITIONAL_NO_MATCH, 0,
                    "Path-scoped rule does not match the supplied target file"));
            case INVALID -> sources.add(inactive(
                    candidate, ContextSourceState.INVALID, 0, "Rule activation is invalid"));
        }
    }

    private static void resolveImports(
            Path realRoot,
            Candidate owner,
            int depth,
            Set<Path> stack,
            Set<Path> resolved,
            List<ContextSource> sources,
            List<ContextRelation> relations,
            List<ContextFinding> findings,
            ResolutionProgress progress) throws IOException {
        String markdown = readUtf8(owner, "CLAUDE_IMPORT_SCAN_UNAVAILABLE", findings);
        if (markdown == null) {
            return;
        }
        ClaudeImportScan scan = new ClaudeImportParser().parse(markdown);
        addClaudeDiagnostics(owner.logicalPath(), scan.diagnostics(), findings);
        for (ClaudeImport reference : scan.imports()) {
            if (depth >= ClaudeImportParser.OFFICIAL_MAXIMUM_RECURSIVE_HOPS) {
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "CLAUDE_IMPORT_DEPTH_LIMIT", owner.logicalPath(),
                        "An import exceeds Claude Code's four-hop recursive import limit"));
                continue;
            }
            Path lexicalTarget = owner.realPath().getParent().resolve(reference.relativePath()).normalize();
            Path displayPath = safeLogical(realRoot, lexicalTarget);
            relations.add(new ContextRelation(ContextRelationKind.IMPORTS,
                    owner.logicalPath(), displayPath,
                    "Import reference at line " + reference.line()));
            if (!lexicalTarget.startsWith(realRoot)) {
                sources.add(pseudoImport(displayPath, ContextSourceState.EXTERNAL_APPROVAL_REQUIRED,
                        "External import is not read because Claude approval state is unavailable"));
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "CLAUDE_EXTERNAL_IMPORT_APPROVAL_REQUIRED", displayPath,
                        "An import resolves outside the authorized workspace and was not read"));
                continue;
            }
            Candidate imported = inspect(realRoot, lexicalTarget, ContextSourceKind.CLAUDE_IMPORT);
            if (imported == null) {
                sources.add(pseudoImport(displayPath, ContextSourceState.INVALID,
                        "Imported file does not exist"));
                findings.add(new ContextFinding(ContextFindingSeverity.ERROR,
                        "CLAUDE_IMPORT_NOT_FOUND", displayPath, "Imported file does not exist"));
                continue;
            }
            if (!selectable(imported)) {
                sources.add(inactive(imported, inactiveState(imported), 0,
                        inactiveDetail(imported, "Imported file is not usable")));
                continue;
            }
            Path realTarget = imported.realPath();
            if (!realTarget.startsWith(realRoot)) {
                sources.add(pseudoImport(displayPath, ContextSourceState.EXTERNAL_APPROVAL_REQUIRED,
                        "Import resolves outside the authorized workspace"));
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "CLAUDE_EXTERNAL_IMPORT_APPROVAL_REQUIRED", displayPath,
                        "An import resolves outside the authorized workspace and was not read"));
                continue;
            }
            if (stack.contains(realTarget)) {
                sources.add(inactive(imported, ContextSourceState.INVALID, 0,
                        "Recursive import cycle detected"));
                findings.add(new ContextFinding(ContextFindingSeverity.ERROR,
                        "CLAUDE_IMPORT_CYCLE", imported.logicalPath(),
                        "Recursive import cycle detected"));
                continue;
            }
            if (!resolved.add(realTarget)) {
                continue;
            }
            ContextSource active = source(imported, ContextSourceState.ACTIVE,
                    progress.nextOrder(), imported.byteSize(),
                    "Imported by " + portable(owner.logicalPath()) + " at line " + reference.line());
            sources.add(active);
            progress.include(active);
            Set<Path> nestedStack = new LinkedHashSet<>(stack);
            nestedStack.add(realTarget);
            resolveImports(realRoot, imported, depth + 1, nestedStack, resolved,
                    sources, relations, findings, progress);
        }
    }

    private static ContextSource pseudoImport(
            Path logicalPath, ContextSourceState state, String detail) {
        return new ContextSource(logicalPath, null, ContextSourceKind.CLAUDE_IMPORT,
                state, 0, 0, 0, "", detail);
    }

    private static String readUtf8(
            Candidate candidate, String findingCode, List<ContextFinding> findings) throws IOException {
        if (candidate.byteSize() > MAX_HASHED_SOURCE_BYTES) {
            findings.add(new ContextFinding(ContextFindingSeverity.WARNING, findingCode,
                    candidate.logicalPath(),
                    "Semantic parsing was skipped because the file exceeds the 1 MiB parser limit"));
            return null;
        }
        byte[] bytes = Files.readAllBytes(candidate.realPath());
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            findings.add(new ContextFinding(ContextFindingSeverity.ERROR, findingCode,
                    candidate.logicalPath(), "Semantic parsing requires valid UTF-8"));
            return null;
        }
    }

    private static void addClaudeDiagnostics(
            Path logicalPath,
            List<ClaudeDiagnostic> diagnostics,
            List<ContextFinding> findings) {
        for (ClaudeDiagnostic diagnostic : diagnostics) {
            ContextFindingSeverity severity = diagnostic.severity() == ClaudeDiagnosticSeverity.ERROR
                    ? ContextFindingSeverity.ERROR : ContextFindingSeverity.WARNING;
            String position = diagnostic.line() > 0 ? " at line " + diagnostic.line() : "";
            findings.add(new ContextFinding(severity, "CLAUDE_" + diagnostic.code(), logicalPath,
                    diagnostic.message() + position));
        }
    }

    private static void addIfPresent(List<Candidate> candidates, Candidate candidate) {
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static void addSymlinkFindings(
            List<ContextSource> sources, List<ContextFinding> findings) {
        for (ContextSource source : sources) {
            if (source.state() == ContextSourceState.SKIPPED_SYMLINK) {
                findings.add(new ContextFinding(ContextFindingSeverity.WARNING,
                        "HOST_SYMLINK_LOADING_NOT_MODELED", source.logicalPath(),
                        "The host may load this symlink, but the workbench does not follow it"));
            }
        }
    }

    private static ContextResolutionStatus resolution(List<ContextFinding> findings) {
        return findings.isEmpty() ? ContextResolutionStatus.COMPLETE : ContextResolutionStatus.PARTIAL;
    }

    private static Path safeLogical(Path root, Path path) {
        try {
            return root.relativize(path);
        } catch (IllegalArgumentException exception) {
            return path;
        }
    }

    private static List<Path> hierarchy(Path root, Path cwd) {
        List<Path> result = new ArrayList<>();
        Path cursor = root;
        result.add(cursor);
        if (root.equals(cwd)) {
            return List.copyOf(result);
        }
        for (Path segment : root.relativize(cwd)) {
            cursor = cursor.resolve(segment);
            result.add(cursor);
        }
        return List.copyOf(result);
    }

    private static Candidate inspect(Path root, Path path, ContextSourceKind kind) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path logical = root.relativize(path);
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            return new Candidate(logical, null, kind, attributes.size(), "", CandidateState.SYMLINK);
        }
        if (!attributes.isRegularFile()) {
            return new Candidate(logical, path, kind, attributes.size(), "", CandidateState.SPECIAL);
        }
        if (attributes.size() == 0) {
            return new Candidate(logical, path.toRealPath(), kind, 0, sha256(path), CandidateState.EMPTY);
        }
        if (attributes.size() > MAX_HASHED_SOURCE_BYTES) {
            return new Candidate(
                    logical, path.toRealPath(), kind, attributes.size(), "", CandidateState.USABLE_UNHASHED);
        }
        return new Candidate(logical, path.toRealPath(), kind, attributes.size(), sha256(path), CandidateState.USABLE);
    }

    private static boolean selectable(Candidate candidate) {
        return candidate != null && (candidate.state() == CandidateState.USABLE
                || candidate.state() == CandidateState.USABLE_UNHASHED);
    }

    private static Activation activate(Candidate candidate, long included, long maximum, int precedence) {
        if (!selectable(candidate)) {
            return new Activation(inactive(candidate, inactiveState(candidate), 0,
                    inactiveDetail(candidate, "File is not active")));
        }
        long remaining = Math.max(0, maximum - included);
        if (remaining == 0) {
            return new Activation(inactive(candidate, ContextSourceState.SKIPPED_LIMIT, 0,
                    "Not included because the configured project instruction limit was reached"));
        }
        long effective = Math.min(candidate.byteSize(), remaining);
        ContextSourceState state = effective == candidate.byteSize()
                ? ContextSourceState.ACTIVE : ContextSourceState.ACTIVE_TRUNCATED;
        return new Activation(source(candidate, state, precedence, effective,
                state == ContextSourceState.ACTIVE ? "Included in root-to-current-directory order"
                        : "Only the bytes remaining under the configured instruction limit are effective"));
    }

    private static ContextSource activateWithoutAggregateLimit(Candidate candidate, int precedence) {
        if (!selectable(candidate)) {
            return inactive(candidate, inactiveState(candidate), 0,
                    inactiveDetail(candidate, "File is not active"));
        }
        return source(candidate, ContextSourceState.ACTIVE, precedence, candidate.byteSize(),
                "Included in filesystem-root-to-current-directory order within the authorized root");
    }

    private static ContextSource inactive(
            Candidate candidate, ContextSourceState state, int precedence, String detail) {
        return source(candidate, state, precedence, 0, detail);
    }

    private static ContextSource source(
            Candidate candidate, ContextSourceState state, int precedence, long includedBytes, String detail) {
        String effectiveDetail = candidate.state() == CandidateState.USABLE_UNHASHED
                ? detail + "; full-file hash omitted above 1 MiB" : detail;
        return new ContextSource(candidate.logicalPath(), candidate.realPath(), candidate.kind(), state,
                precedence, candidate.byteSize(), includedBytes, candidate.sha256(), effectiveDetail);
    }

    private static ContextSourceState inactiveState(Candidate candidate) {
        return switch (candidate.state()) {
            case EMPTY -> ContextSourceState.EMPTY;
            case SYMLINK -> ContextSourceState.SKIPPED_SYMLINK;
            case SPECIAL -> ContextSourceState.SKIPPED_SPECIAL_FILE;
            case USABLE, USABLE_UNHASHED -> ContextSourceState.SHADOWED;
        };
    }

    private static String inactiveDetail(Candidate candidate, String fallback) {
        return switch (candidate.state()) {
            case EMPTY -> "Empty instruction file is not included";
            case SYMLINK -> "Symbolic-link instruction files are not followed by this tool";
            case SPECIAL -> "Path is not a regular file";
            case USABLE -> fallback;
            case USABLE_UNHASHED -> fallback + "; full-file hash omitted above 1 MiB";
        };
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private enum CandidateState {
        USABLE,
        USABLE_UNHASHED,
        EMPTY,
        SYMLINK,
        SPECIAL
    }

    private record Candidate(
            Path logicalPath,
            Path realPath,
            ContextSourceKind kind,
            long byteSize,
            String sha256,
            CandidateState state) {}

    private record Activation(ContextSource source) {}

    private static final class ResolutionProgress {
        private int order;
        private long includedBytes;

        private int nextOrder() {
            return order + 1;
        }

        private void include(ContextSource source) {
            if (!source.active()) {
                throw new IllegalArgumentException("Only active sources can advance load order");
            }
            order++;
            includedBytes += source.includedBytes();
        }

        private long includedBytes() {
            return includedBytes;
        }
    }
}
