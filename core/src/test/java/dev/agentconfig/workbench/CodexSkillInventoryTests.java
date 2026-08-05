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
        run("invalid frontmatter and name mismatch are explicit", this::invalidMetadata);
        run("invalid declared name is redacted from CLI output", this::invalidNameIsRedacted);
        run("valid-looking mismatched name is redacted but still grouped",
                this::mismatchedNameIsRedacted);
        run("duplicate declared names are deterministic findings", this::duplicateNames);
        run("Skill file symlink is blocked without reading its target", this::skillFileSymlink);
        run("agents directory symlink is blocked and CLI returns partial",
                this::agentsDirectorySymlink);
        run("supporting symlink makes inventory partial", this::supportingSymlink);
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
            writeSkill(root, "safe", "safe", "Safe skill", "Body\n");
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

            inspect(root);
            inspect(root);

            equal(before, fingerprint(root), "workspace fingerprint");
        });
    }

    private void supportingEntryBudget() throws Exception {
        withTempDirectory(root -> {
            writeSkill(root, "large", "large", "Large package", "Body\n");
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
        });
    }

    private void cliContract() throws Exception {
        withTempDirectory(root -> {
            String secret = "skill-body-secret-8842";
            writeSkill(root, "review", "review", "Review code", secret + "\n");

            Invocation complete = invoke("skill-inventory", "codex", root.toString());

            equal(0, complete.exitCode(), "complete exit");
            check(complete.stdout().contains("\"schemaVersion\": 1"), "schema missing");
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
