package dev.agentconfig.workbench;

import dev.agentconfig.workbench.cli.Cli;
import dev.agentconfig.workbench.skill.CodexSkillInventory;
import dev.agentconfig.workbench.skill.CodexSkillInventoryService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CodexSkillInventoryTests {
    private int passed;
    private int skipped;

    public static void main(String[] args) throws Exception {
        new CodexSkillInventoryTests().runAll();
    }

    private void runAll() throws Exception {
        run("empty project has a complete empty inventory", this::emptyProject);
        run("valid package exposes metadata and inert executable risk", this::validPackageAndRisk);
        run("quoted description may contain comment markers", this::quotedDescription);
        run("invalid frontmatter and name mismatch are explicit", this::invalidMetadata);
        run("invalid declared name is redacted from CLI output", this::invalidNameIsRedacted);
        run("valid-looking mismatched name is redacted but still grouped",
                this::mismatchedNameIsRedacted);
        run("duplicate declared names are deterministic findings", this::duplicateNames);
        run("Skill file symlink is blocked without reading its target", this::skillFileSymlink);
        run("agents directory symlink is blocked and CLI returns partial",
                this::agentsDirectorySymlink);
        run("supporting symlink makes inventory partial", this::supportingSymlink);
        run("safe inline references form a deterministic content-free graph",
                this::safeReferenceGraph);
        run("missing and unsafe references invalidate without leaking destinations",
                this::invalidReferences);
        run("code examples external URLs and anchors are not local references",
                this::ignoredReferenceForms);
        run("reference budget returns a deterministic partial prefix",
                this::referenceBudget);
        run("supporting entry budget stops with a partial result", this::supportingEntryBudget);
        run("inventory performs zero workspace writes", this::zeroWorkspaceWrites);
        run("CLI output is content-free and partial-aware", this::cliContract);
        System.out.printf("Codex Skill inventory tests: %d passed, %d skipped%n", passed, skipped);
    }

    private void emptyProject() throws Exception {
        withTempDirectory(root -> {
            CodexSkillInventory inventory = inspect(root);
            equal(CodexSkillInventory.Status.COMPLETE, inventory.status(), "status");
            check(inventory.packages().isEmpty(), "packages must be empty");
            check(inventory.findings().isEmpty(), "findings must be empty");
            check(!inventory.contentIncluded(), "contentIncluded");
            check(!inventory.writesPerformed(), "writesPerformed");
        });
    }

    private void validPackageAndRisk() throws Exception {
        withTempDirectory(root -> {
            String secret = "inventory-secret-must-not-leak";
            writeSkill(root, "release", "release", "Prepare releases", "# Release\n");
            write(root, ".agents/skills/release/references/checklist.md", secret);
            write(root, ".agents/skills/release/scripts/publish.sh",
                    "#!/bin/sh\ntouch must-not-exist\n");

            CodexSkillInventory inventory = inspect(root);

            equal(CodexSkillInventory.Status.COMPLETE, inventory.status(), "status");
            equal(1, inventory.packages().size(), "package count");
            CodexSkillInventory.SkillPackage skill = inventory.packages().getFirst();
            equal("release", skill.declaredName(), "declared name");
            equal(CodexSkillInventory.PackageState.MINIMAL_METADATA_VALID,
                    skill.state(), "package state");
            equal(2, skill.supportingFileCount(), "supporting files");
            check(skill.risks().contains(CodexSkillInventory.Risk.SCRIPTS_DIRECTORY),
                    "scripts directory risk");
            check(skill.risks().contains(CodexSkillInventory.Risk.EXECUTABLE_SUPPORT_FILE),
                    "executable file risk");
            check(!Files.exists(root.resolve("must-not-exist")), "supporting script was executed");
        });
    }

    private void quotedDescription() throws Exception {
        withTempDirectory(root -> {
            write(root, ".agents/skills/quoted/SKILL.md",
                    "---\nname: quoted\ndescription: 'Review API: # verify ''contracts''.'\n---\n\n"
                            + "# quoted\n");
            CodexSkillInventory inventory = inspect(root);
            equal(CodexSkillInventory.PackageState.MINIMAL_METADATA_VALID,
                    inventory.packages().getFirst().state(), "package state");
            check(!hasFinding(inventory, CodexSkillInventory.FindingCode.INVALID_FRONTMATTER),
                    "quoted comment marker was treated as a YAML comment");
        });
    }

    private void invalidMetadata() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "wrong-directory", "other-name", "", "Body\n");

            CodexSkillInventory inventory = inspect(root);

            equal(CodexSkillInventory.PackageState.INVALID,
                    inventory.packages().getFirst().state(), "package state");
            check(hasFinding(inventory, CodexSkillInventory.FindingCode.NAME_DIRECTORY_MISMATCH),
                    "name mismatch finding");
            check(hasFinding(inventory, CodexSkillInventory.FindingCode.MISSING_DESCRIPTION),
                    "missing description finding");
            equal(CodexSkillInventory.Status.COMPLETE, inventory.status(),
                    "invalid metadata must not pretend the inventory read was partial");
        });
    }

    private void duplicateNames() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "alpha", "alpha", "Alpha", "Body\n");
            writeSkill(root, "beta", "alpha", "Second alpha", "Body\n");

            CodexSkillInventory inventory = inspect(root);

            long duplicates = inventory.findings().stream()
                    .filter(finding -> finding.code()
                            == CodexSkillInventory.FindingCode.DUPLICATE_DECLARED_NAME)
                    .count();
            equal(2L, duplicates, "duplicate finding count");
            equal(List.of(
                            ".agents/skills/alpha/SKILL.md",
                            ".agents/skills/beta/SKILL.md"),
                    inventory.packages().stream()
                            .map(CodexSkillInventory.SkillPackage::logicalPath).toList(),
                    "package ordering");
        });
    }

    private void invalidNameIsRedacted() throws Exception {
        withTempDirectory(root -> {
            String secretName = "NOT-A-NAME-secret-8842";
            writeSkill(root, "invalid", secretName, "Invalid name", "Body\n");

            Invocation invocation = invoke("skill-inventory", "codex", root.toString());

            equal(0, invocation.exitCode(), "exit");
            check(invocation.stdout().contains("\"declaredName\": null"),
                    "invalid name should be redacted");
            check(!invocation.stdout().contains(secretName), "invalid frontmatter value leaked");
        });
    }

    private void mismatchedNameIsRedacted() throws Exception {
        withTempDirectory(root -> {
            String secretLookingName = "secret-token-8842";
            writeSkill(root, "first", secretLookingName, "First", "Body\n");
            writeSkill(root, "second", secretLookingName, "Second", "Body\n");

            Invocation invocation = invoke("skill-inventory", "codex", root.toString());

            equal(0, invocation.exitCode(), "exit");
            check(!invocation.stdout().contains(secretLookingName), "mismatched name leaked");
            long duplicateCodes = count(invocation.stdout(), "DUPLICATE_DECLARED_NAME");
            equal(2L, duplicateCodes, "duplicate findings");
        });
    }

    private void skillFileSymlink() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path outside = Files.writeString(base.resolve("outside.md"),
                    "---\nname: escape\ndescription: outside-secret\n---\n");
            Path link = root.resolve(".agents/skills/escape/SKILL.md");
            Files.createDirectories(link.getParent());
            if (!tryCreateSymlink(link, outside)) {
                skip("symlink unsupported on this platform");
                return;
            }

            CodexSkillInventory inventory = inspect(root);
            equal(CodexSkillInventory.Status.PARTIAL, inventory.status(), "status");
            check(inventory.packages().isEmpty(), "symlink target became a package");
            check(hasFinding(inventory, CodexSkillInventory.FindingCode.SKILL_FILE_IS_SYMLINK),
                    "symlink finding");
        });
    }

    private void supportingSymlink() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            writeSkill(root, "safe", "safe", "Safe skill",
                    "[private](references/private.md) [other](references/other.md)\n");
            Path outside = Files.writeString(base.resolve("private.md"), "private-support-content");
            Path link = root.resolve(".agents/skills/safe/references/private.md");
            Files.createDirectories(link.getParent());
            if (!tryCreateSymlink(link, outside)) {
                skip("symlink unsupported on this platform");
                return;
            }

            CodexSkillInventory inventory = inspect(root);
            equal(CodexSkillInventory.Status.PARTIAL, inventory.status(), "status");
            equal(CodexSkillInventory.PackageState.PARTIAL,
                    inventory.packages().getFirst().state(), "package state");
            check(inventory.packages().getFirst().risks()
                            .contains(CodexSkillInventory.Risk.SYMLINK_SUPPORT_PATH),
                    "support link risk");
            equal(2, inventory.references().size(), "unknown reference count");
            check(inventory.references().stream().allMatch(reference ->
                            reference.resolution()
                                    == CodexSkillInventory.ReferenceResolution.UNKNOWN),
                    "incomplete support enumeration must not claim missing");
            check(inventory.references().stream().allMatch(reference ->
                            reference.targetLogicalPath().isEmpty()),
                    "unknown targets must be redacted");
        });
    }

    private void safeReferenceGraph() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "review", "review", "Review code",
                    "[checklist](references/checklist.md?mode=fast#before-review)\n"
                            + "![diagram](<assets/review flow.png>)\n"
                            + "[duplicate](references/checklist.md#other)\n");
            write(root, ".agents/skills/review/references/checklist.md", "support-secret-4412");
            write(root, ".agents/skills/review/assets/review flow.png", "image-secret-8841");

            CodexSkillInventory inventory = inspect(root);

            equal(CodexSkillInventory.Status.COMPLETE, inventory.status(), "status");
            equal(CodexSkillInventory.PackageState.MINIMAL_METADATA_VALID,
                    inventory.packages().getFirst().state(), "package state");
            equal(3, inventory.references().size(), "reference count");
            equal(List.of(
                            ".agents/skills/review/assets/review flow.png",
                            ".agents/skills/review/references/checklist.md",
                            ".agents/skills/review/references/checklist.md"),
                    inventory.references().stream()
                            .map(CodexSkillInventory.Reference::targetLogicalPath).toList(),
                    "stable target ordering");
            check(inventory.references().stream().allMatch(reference ->
                            reference.resolution()
                                    == CodexSkillInventory.ReferenceResolution.RESOLVED),
                    "resolved reference");
            equal(CodexSkillInventory.ReferenceKind.IMAGE,
                    inventory.references().getFirst().kind(), "image kind");
            try {
                new CodexSkillInventory.Reference(
                        ".agents/skills/review/SKILL.md",
                        ".agents/skills/other/private.md",
                        1,
                        1,
                        CodexSkillInventory.ReferenceKind.LINK,
                        CodexSkillInventory.ReferenceResolution.RESOLVED);
                throw new AssertionError("cross-package reference was accepted");
            } catch (IllegalArgumentException expected) {
                // Domain contract rejects edges that the service must never produce.
            }
        });
    }

    private void invalidReferences() throws Exception {
        withTempDirectory(root -> {
            String missingLabel = "missing-label-secret-9101";
            String unsafeTarget = "../../outside-secret-9102.md";
            String missingTarget = "references/missing-target-secret-9103.md";
            String secondMissingTarget = "references/missing-target-secret-9104.md";
            String absoluteTarget = "/private/absolute-secret-9105.md";
            String uncTarget = "//server/share-secret-9106.md";
            String customSchemeTarget = "skill+private:secret-9107";
            writeSkill(root, "audit", "audit", "Audit configuration",
                    "[" + missingLabel + "](" + missingTarget + ") "
                            + "[second](" + secondMissingTarget + ")\n"
                            + "[escape](" + unsafeTarget + ")\n"
                            + "[windows](C:\\private\\secret.md)\n"
                            + "[file](file:///private/secret.md)\n"
                            + "[nonportable](references/a*bad.md)\n"
                            + "[absolute](" + absoluteTarget + ")\n"
                            + "[unc](" + uncTarget + ")\n"
                            + "[scheme](" + customSchemeTarget + ")\n");

            CodexSkillInventory inventory = inspect(root);
            Invocation invocation = invoke("skill-inventory", "codex", root.toString());

            equal(CodexSkillInventory.Status.COMPLETE, inventory.status(), "scan status");
            equal(3, invocation.exitCode(), "blocking unsafe reference exit");
            equal(CodexSkillInventory.PackageState.INVALID,
                    inventory.packages().getFirst().state(), "package state");
            check(hasFinding(inventory, CodexSkillInventory.FindingCode.MISSING_REFERENCE_TARGET),
                    "missing reference finding");
            check(hasFinding(inventory, CodexSkillInventory.FindingCode.UNSAFE_LOCAL_REFERENCE),
                    "unsafe reference finding");
            equal(2, inventory.references().size(), "redacted unresolved edge count");
            check(inventory.references().stream().allMatch(reference ->
                            reference.resolution()
                                    == CodexSkillInventory.ReferenceResolution.MISSING),
                    "missing resolution");
            check(inventory.references().stream().allMatch(reference ->
                            reference.targetLogicalPath().isEmpty()),
                    "unresolved target path was retained");
            check(inventory.references().get(0).column() != inventory.references().get(1).column(),
                    "same-line unresolved references lost occurrence identity");
            check(!invocation.stdout().contains(missingLabel), "link label leaked");
            check(!invocation.stdout().contains(missingTarget), "missing destination leaked");
            check(!invocation.stdout().contains(secondMissingTarget),
                    "second missing destination leaked");
            check(invocation.stdout().contains("\"column\":"), "column missing from JSON");
            check(!invocation.stdout().contains(unsafeTarget), "unsafe destination leaked");
            check(!invocation.stdout().contains(absoluteTarget), "absolute destination leaked");
            check(!invocation.stdout().contains(uncTarget), "UNC destination leaked");
            check(!invocation.stdout().contains(customSchemeTarget), "URI destination leaked");
            check(!invocation.stdout().contains("C:\\\\private"), "Windows destination leaked");
        });
    }

    private void ignoredReferenceForms() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "docs", "docs", "Document workflows",
                    "[web](https://example.com/secret-path) and [mail](mailto:user@example.com)\n"
                            + "[section](#local-section)\n"
                            + "`[inline](references/not-real.md)`\n"
                            + "\\[escaped](references/not-real.md)\n"
                            + "<!-- [comment](references/not-real.md) -->\n"
                            + "<!--\n[multiline-comment](references/not-real.md)\n-->\n"
                            + "<!--\n```not-a-fence\n-->\n"
                            + "`<!--`\n"
                            + "```markdown\n<!--\n```not-a-close\n"
                            + "[fenced](../../outside.md)\n```\n"
                            + "[real](references/real.md)\n");
            write(root, ".agents/skills/docs/references/real.md", "real support");

            CodexSkillInventory inventory = inspect(root);

            equal(CodexSkillInventory.Status.COMPLETE, inventory.status(), "status");
            equal(1, inventory.references().size(), "ignored forms became graph edges");
            equal(".agents/skills/docs/references/real.md",
                    inventory.references().getFirst().targetLogicalPath(), "real reference");
            check(!hasFinding(inventory, CodexSkillInventory.FindingCode.MISSING_REFERENCE_TARGET),
                    "ignored form became missing");
            check(!hasFinding(inventory, CodexSkillInventory.FindingCode.UNSAFE_LOCAL_REFERENCE),
                    "fenced example became unsafe");
        });
    }

    private void referenceBudget() throws Exception {
        withTempDirectory(root -> {
            StringBuilder body = new StringBuilder();
            body.append("[long](references/")
                    .append("a".repeat(1025))
                    .append("[nested](references/nested.md))\n");
            for (int index = 0; index < 129; index++) {
                body.append("[item](references/item-")
                        .append("%03d".formatted(index)).append(".md)\n");
            }
            writeSkill(root, "bounded", "bounded", "Bounded references", body.toString());
            for (int index = 0; index < 129; index++) {
                write(root, ".agents/skills/bounded/references/item-%03d.md".formatted(index), "x");
            }
            write(root, ".agents/skills/bounded/references/nested.md", "x");

            CodexSkillInventory inventory = inspect(root);

            equal(CodexSkillInventory.Status.PARTIAL, inventory.status(), "status");
            equal(128, inventory.references().size(), "bounded reference prefix");
            check(inventory.references().stream().noneMatch(reference ->
                            reference.targetLogicalPath().endsWith("/nested.md")),
                    "nested link text inside overlong destination was reparsed");
            check(hasFinding(inventory, CodexSkillInventory.FindingCode.REFERENCE_LIMIT_REACHED),
                    "reference limit finding");
            equal(CodexSkillInventory.PackageState.PARTIAL,
                    inventory.packages().getFirst().state(), "package state");
        });
    }

    private void agentsDirectorySymlink() throws Exception {
        withTempDirectory(base -> {
            Path root = Files.createDirectory(base.resolve("workspace"));
            Path outsideAgents = Files.createDirectories(base.resolve("outside-agents/skills/escape"));
            String secret = "outside-skill-secret-7712";
            Files.writeString(outsideAgents.resolve("SKILL.md"),
                    "---\nname: escape\ndescription: " + secret + "\n---\n");
            if (!tryCreateSymlink(root.resolve(".agents"), base.resolve("outside-agents"))) {
                skip("symlink unsupported on this platform");
                return;
            }

            Invocation invocation = invoke("skill-inventory", "codex", root.toString());

            equal(3, invocation.exitCode(), "partial exit");
            check(invocation.stdout().contains("\"status\": \"PARTIAL\""),
                    "partial status missing");
            check(invocation.stdout().contains("AGENTS_PATH_IS_SYMLINK"),
                    "agents symlink finding missing");
            check(!invocation.stdout().contains(secret), "outside Skill content leaked");
            check(invocation.stderr().isEmpty(), "partial report wrote stderr");
        });
    }

    private void zeroWorkspaceWrites() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "review", "review", "Review code", "Body\n");
            Map<String, Fingerprint> before = fingerprint(root);

            CodexSkillInventory first = inspect(root);
            CodexSkillInventory second = inspect(root);

            equal(before, fingerprint(root), "workspace fingerprint");
            equal(first, second, "repeatable inventory");
        });
    }

    private void supportingEntryBudget() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "large", "large", "Large package",
                    "[late](references/item-256.md)\n");
            for (int index = 0; index < 257; index++) {
                write(root, ".agents/skills/large/references/item-%03d.md".formatted(index), "x");
            }

            CodexSkillInventory inventory = inspect(root);

            equal(CodexSkillInventory.Status.PARTIAL, inventory.status(), "status");
            check(hasFinding(inventory,
                            CodexSkillInventory.FindingCode.PACKAGE_ENTRY_LIMIT_REACHED),
                    "entry limit finding");
            equal(CodexSkillInventory.PackageState.PARTIAL,
                    inventory.packages().getFirst().state(), "package state");
            equal(1, inventory.references().size(), "unknown edge count");
            equal(CodexSkillInventory.ReferenceResolution.UNKNOWN,
                    inventory.references().getFirst().resolution(), "unknown resolution");
            check(!hasFinding(inventory, CodexSkillInventory.FindingCode.MISSING_REFERENCE_TARGET),
                    "partial enumeration claimed missing");
        });
    }

    private void cliContract() throws Exception {
        withTempDirectory(root -> {
            String secret = "skill-body-secret-8842";
            writeSkill(root, "review", "review", "Review code", secret + "\n");

            Invocation complete = invoke("skill-inventory", "codex", root.toString());

            equal(0, complete.exitCode(), "complete exit");
            check(complete.stdout().contains("\"schemaVersion\": 2"), "schema missing");
            check(complete.stdout().contains(
                            "\"referenceProfileId\": \"codex-skill-inline-reference-v1\""),
                    "reference profile missing");
            check(complete.stdout().contains("\"references\": ["), "reference graph missing");
            check(complete.stdout().contains("\"contentIncluded\": false"),
                    "content contract missing");
            check(complete.stdout().contains("\"writesPerformed\": false"),
                    "write contract missing");
            check(!complete.stdout().contains(secret), "SKILL.md content leaked");
            check(!complete.stdout().contains(root.toString()), "physical root leaked");
            check(complete.stderr().isEmpty(), "happy path stderr");

            Invocation invalid = invoke("skill-inventory", "claude-code", root.toString());
            equal(2, invalid.exitCode(), "unsupported host exit");
            check(invalid.stderr().contains("Usage:"), "usage missing");
        });
    }

    private static CodexSkillInventory inspect(Path root) throws IOException {
        return new CodexSkillInventoryService().inspect(root);
    }

    private static boolean hasFinding(
            CodexSkillInventory inventory, CodexSkillInventory.FindingCode code) {
        return inventory.findings().stream().anyMatch(finding -> finding.code() == code);
    }

    private static void writeSkill(
            Path root, String directory, String name, String description, String body)
            throws IOException {
        write(root, ".agents/skills/" + directory + "/SKILL.md",
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static Invocation invoke(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Cli.phaseOneDefaults().run(
                arguments,
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exit,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static boolean tryCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            return false;
        }
    }

    private static Map<String, Fingerprint> fingerprint(Path root) throws Exception {
        Map<String, Fingerprint> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String logical = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) {
                    result.put(logical, new Fingerprint("L", Files.readSymbolicLink(path).toString(), 0L));
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(logical, new Fingerprint("D", "", 0L));
                } else {
                    byte[] bytes = Files.readAllBytes(path);
                    result.put(logical, new Fingerprint("F", sha256(bytes),
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()));
                }
            }
        }
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static long count(String text, String needle) {
        long result = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }

    private void withTempDirectory(ThrowingConsumer<Path> test) throws Exception {
        Path root = Files.createTempDirectory("acw-skill-inventory-");
        try {
            test.accept(root);
        } finally {
            deleteOwnedTempTree(root);
        }
    }

    private static void deleteOwnedTempTree(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-skill-inventory-")
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
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (SkipTest exception) {
            System.out.println("SKIP " + name + ": " + exception.getMessage());
        }
    }

    private void skip(String reason) {
        skipped++;
        throw new SkipTest(reason);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private record Invocation(int exitCode, String stdout, String stderr) {}

    private record Fingerprint(String kind, String content, long modifiedMillis) {}

    private static final class SkipTest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SkipTest(String message) { super(message); }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}
