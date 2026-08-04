package dev.agentconfig.workbench;

import dev.agentconfig.workbench.analyze.AnalysisFindingType;
import dev.agentconfig.workbench.analyze.InstructionAnalysisEngine;
import dev.agentconfig.workbench.analyze.InstructionAnalysisReport;
import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.ContextFinding;
import dev.agentconfig.workbench.context.ContextResolutionStatus;
import dev.agentconfig.workbench.context.ContextSource;
import dev.agentconfig.workbench.context.ContextSourceState;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
import dev.agentconfig.workbench.ir.InstructionSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Conformance fixtures for the versioned Codex project-instruction semantic profile. */
public final class CodexConformanceTests {
    private static final String PROFILE = "codex-project-semantics-v1";

    private int passed;

    public static void main(String[] args) throws Exception {
        new CodexConformanceTests().runAll();
    }

    private void runAll() throws Exception {
        run("v1 walks only root-to-cwd ancestors", this::walksOnlyRootToCwdAncestors);
        run("v1 override shadows same-directory base", this::overrideShadowsBase);
        run("v1 empty override falls through to base", this::emptyOverrideFallsThrough);
        run("v1 configured fallbacks preserve priority", this::configuredFallbacksPreservePriority);
        run("v1 exact byte boundary remains complete", this::exactByteBoundary);
        run("v1 truncates current source then skips later sources", this::truncatesThenSkips);
        run("v1 separates revision and effective hashes", this::separatesRevisionAndEffectiveHashes);
        run("v1 logical identities are workspace-location independent", this::logicalIdentitiesAreStable);
        run("v1 invalid config makes resolution partial", this::invalidConfigIsPartial);
        System.out.printf("Codex conformance %s: %d passed%n", PROFILE, passed);
    }

    private void walksOnlyRootToCwdAncestors() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path cwd = Files.createDirectories(root.resolve("services/payments/api"));
            Files.createDirectories(root.resolve("services/search"));
            write(root, "AGENTS.md", "root");
            write(root, "services/AGENTS.md", "services");
            write(root, "services/payments/api/AGENTS.md", "api");
            write(root, "services/search/AGENTS.md", "sibling");

            EffectiveInstructionContext context = compile(root, cwd, null);

            equal(List.of(
                    "AGENTS.md",
                    "services/AGENTS.md",
                    "services/payments/api/AGENTS.md"), activePaths(context), "active chain");
            equal(List.of(1, 2, 3), context.sources().stream()
                    .filter(ContextSource::active).map(ContextSource::precedence).toList(), "precedence");
            check(context.sources().stream().noneMatch(source ->
                    portable(source.logicalPath()).startsWith("services/search/")),
                    "sibling source entered the root-to-cwd chain");
            equal(ContextResolutionStatus.COMPLETE, context.resolutionStatus(), "resolution");
        });
    }

    private void overrideShadowsBase() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            write(root, "AGENTS.override.md", "override");
            write(root, "AGENTS.md", "base");

            EffectiveInstructionContext context = compile(root, root, null);

            equal(List.of("AGENTS.override.md"), activePaths(context), "active source");
            equal(ContextSourceState.SHADOWED, source(context, "AGENTS.md").state(), "base state");
            check(context.relations().stream().anyMatch(relation ->
                    portable(relation.fromLogicalPath()).equals("AGENTS.override.md")
                            && portable(relation.toLogicalPath()).equals("AGENTS.md")),
                    "override-to-base shadow edge missing");
            equal(ContextResolutionStatus.COMPLETE, context.resolutionStatus(), "resolution");
        });
    }

    private void emptyOverrideFallsThrough() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Files.write(root.resolve("AGENTS.override.md"), new byte[0]);
            write(root, "AGENTS.md", "base");

            EffectiveInstructionContext context = compile(root, root, null);

            equal(ContextSourceState.EMPTY,
                    source(context, "AGENTS.override.md").state(), "empty override state");
            equal(ContextSourceState.ACTIVE, source(context, "AGENTS.md").state(), "base state");
            equal(List.of("AGENTS.md"), activePaths(context), "active source");
            equal(ContextResolutionStatus.COMPLETE, context.resolutionStatus(), "resolution");
        });
    }

    private void configuredFallbacksPreservePriority() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            write(root, "PROJECT.md", "first fallback");
            write(root, "GUIDE.md", "second fallback");
            Path config = config(base, "project_doc_fallback_filenames = [\"PROJECT.md\", \"GUIDE.md\"]\n");

            EffectiveInstructionContext context = compile(root, root, config);

            equal(List.of("PROJECT.md"), activePaths(context), "active fallback");
            equal(ContextSourceState.SHADOWED, source(context, "GUIDE.md").state(),
                    "lower-priority fallback state");
            equal(ContextResolutionStatus.COMPLETE, context.resolutionStatus(), "resolution");
        });
    }

    private void exactByteBoundary() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path cwd = Files.createDirectory(root.resolve("nested"));
            write(root, "AGENTS.md", "root");
            write(root, "nested/AGENTS.md", "nest");
            Path config = config(base, "project_doc_max_bytes = 8\n");

            EffectiveInstructionContext context = compile(root, cwd, config);

            equal(8L, context.maxCombinedBytes(), "maximum bytes");
            equal(8L, context.includedBytes(), "included bytes");
            equal(List.of(ContextSourceState.ACTIVE, ContextSourceState.ACTIVE),
                    context.sources().stream().filter(ContextSource::active)
                            .map(ContextSource::state).toList(), "boundary states");
            equal(List.of(4L, 4L), context.sources().stream().filter(ContextSource::active)
                    .map(ContextSource::includedBytes).toList(), "included source bytes");
            equal(ContextResolutionStatus.COMPLETE, context.resolutionStatus(), "resolution");
        });
    }

    private void truncatesThenSkips() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path cwd = Files.createDirectories(root.resolve("nested/leaf"));
            write(root, "AGENTS.md", "root");
            write(root, "nested/AGENTS.md", "nested");
            write(root, "nested/leaf/AGENTS.md", "later");
            Path config = config(base, "project_doc_max_bytes = 8\n");

            EffectiveInstructionContext context = compile(root, cwd, config);

            ContextSource truncated = source(context, "nested/AGENTS.md");
            ContextSource skipped = source(context, "nested/leaf/AGENTS.md");
            equal(ContextSourceState.ACTIVE_TRUNCATED, truncated.state(), "truncated state");
            equal(4L, truncated.includedBytes(), "truncated bytes");
            equal(2, truncated.precedence(), "truncated load order");
            equal(ContextSourceState.SKIPPED_LIMIT, skipped.state(), "later state");
            equal(0L, skipped.includedBytes(), "later included bytes");
            equal(0, skipped.precedence(), "later load order");
            equal(8L, context.includedBytes(), "aggregate bytes");
            equal(ContextResolutionStatus.COMPLETE, context.resolutionStatus(), "resolution");
        });
    }

    private void separatesRevisionAndEffectiveHashes() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path cwd = Files.createDirectory(root.resolve("nested"));
            write(root, "AGENTS.md", "same");
            write(root, "nested/AGENTS.md", "sameDIFFERENT");
            Path config = config(base, "project_doc_max_bytes = 8\n");

            InstructionAnalysisReport report = analyze(root, cwd, config);
            InstructionSource full = irSource(report, "AGENTS.md");
            InstructionSource truncated = irSource(report, "nested/AGENTS.md");

            equal(sha256("same"), full.revisionSha256(), "full revision hash");
            equal(sha256("same"), full.effectiveSha256(), "full effective hash");
            equal(sha256("sameDIFFERENT"), truncated.revisionSha256(), "truncated revision hash");
            equal(sha256("same"), truncated.effectiveSha256(), "truncated effective hash");
            check(!truncated.revisionSha256().equals(truncated.effectiveSha256()),
                    "truncated revision and effective hashes collapsed");
            equal(1L, report.findings().stream().filter(finding ->
                    finding.type() == AnalysisFindingType.EXACT_EFFECTIVE_DUPLICATE).count(),
                    "effective duplicate count");
        });
    }

    private void logicalIdentitiesAreStable() throws Exception {
        withTempDirectory(base -> {
            Path firstRoot = Files.createDirectories(base.resolve("first/workspace"));
            Path firstCwd = Files.createDirectories(firstRoot.resolve("service/api"));
            Path secondRoot = Files.createDirectories(base.resolve("second/different-name"));
            Path secondCwd = Files.createDirectories(secondRoot.resolve("service/api"));
            write(firstRoot, "AGENTS.md", "root");
            write(firstRoot, "service/api/AGENTS.md", "api");
            write(secondRoot, "AGENTS.md", "root");
            write(secondRoot, "service/api/AGENTS.md", "api");

            InstructionAnalysisReport first = analyze(firstRoot, firstCwd, null);
            InstructionAnalysisReport second = analyze(secondRoot, secondCwd, null);

            equal(first.instructionIr().id(), second.instructionIr().id(), "IR id");
            equal(first.instructionIr().sources().stream().map(InstructionSource::logicalPath).toList(),
                    second.instructionIr().sources().stream().map(InstructionSource::logicalPath).toList(),
                    "logical paths");
            equal(first.instructionIr().sources().stream()
                            .map(source -> source.identity().sourceId()).toList(),
                    second.instructionIr().sources().stream()
                            .map(source -> source.identity().sourceId()).toList(),
                    "source identities");
        });
    }

    private void invalidConfigIsPartial() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            write(root, "AGENTS.md", "root");
            Path config = config(base, "project_doc_max_bytes = 0\n");

            EffectiveInstructionContext context = compile(root, root, config);

            equal(ContextResolutionStatus.PARTIAL, context.resolutionStatus(), "resolution");
            check(context.findings().stream().map(ContextFinding::code)
                            .anyMatch("CODEX_CONFIG_INVALID_TARGET_VALUE"::equals),
                    "invalid config diagnostic missing");
            equal(32L * 1024L, context.maxCombinedBytes(), "default budget after invalid config");
            equal(ContextSourceState.ACTIVE, source(context, "AGENTS.md").state(), "source state");
        });
    }

    private static EffectiveInstructionContext compile(Path root, Path cwd, Path config)
            throws IOException {
        EffectiveInstructionContext context = new EffectiveInstructionCompiler().compile(
                new ContextCompileRequest("codex", root, cwd,
                        Optional.ofNullable(config), Optional.empty()));
        equal(PROFILE, context.semanticProfile(), "semantic profile");
        return context;
    }

    private static InstructionAnalysisReport analyze(Path root, Path cwd, Path config)
            throws IOException {
        return new InstructionAnalysisEngine().analyze(compile(root, cwd, config));
    }

    private static Path config(Path base, String content) throws IOException {
        return Files.writeString(base.resolve("codex-v1.toml"), content, StandardCharsets.UTF_8);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static List<String> activePaths(EffectiveInstructionContext context) {
        return context.sources().stream().filter(ContextSource::active)
                .map(source -> portable(source.logicalPath())).toList();
    }

    private static ContextSource source(EffectiveInstructionContext context, String logicalPath) {
        return context.sources().stream()
                .filter(value -> portable(value.logicalPath()).equals(logicalPath))
                .findFirst().orElseThrow(() -> new AssertionError("Missing source: " + logicalPath));
    }

    private static InstructionSource irSource(InstructionAnalysisReport report, String logicalPath) {
        return report.instructionIr().sources().stream()
                .filter(source -> source.logicalPath().equals(logicalPath))
                .findFirst().orElseThrow(() -> new AssertionError("Missing IR source: " + logicalPath));
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-codex-conformance-v1-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-codex-conformance-v1-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to clean an unexpected test path: " + root);
        }
        try (var paths = Files.walk(root)) {
            List<Path> owned = new ArrayList<>(paths.toList());
            owned.sort(Comparator.reverseOrder());
            for (Path path : owned) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
