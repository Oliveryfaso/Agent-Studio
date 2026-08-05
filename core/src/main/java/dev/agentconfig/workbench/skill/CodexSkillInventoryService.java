package dev.agentconfig.workbench.skill;

import static dev.agentconfig.workbench.skill.CodexSkillInventory.FindingCode.*;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads bounded project-local {@code .agents/skills/<name>/SKILL.md} metadata and body references.
 * Supporting files are enumerated as inert paths; they are never opened or executed.
 */
public final class CodexSkillInventoryService {
    private static final long MAX_SKILL_BYTES = 128 * 1024;
    private static final int MAX_PACKAGE_ENTRIES = 256;
    private static final int MAX_PACKAGES = 256;
    private static final int MAX_PACKAGE_DEPTH = 5;
    private static final Set<String> EXECUTABLE_SUFFIXES = Set.of(
            ".sh", ".bash", ".zsh", ".fish", ".py", ".js", ".mjs", ".cjs",
            ".ps1", ".bat", ".cmd", ".exe", ".com");

    public CodexSkillInventory inspect(Path authorizedRoot) throws IOException {
        Path logicalRoot = authorizedRoot.toAbsolutePath().normalize();
        Path realRoot = logicalRoot.toRealPath();
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Authorized root is not a directory");
        }

        List<CodexSkillInventory.SkillPackage> packages = new ArrayList<>();
        List<CodexSkillInventory.Reference> references = new ArrayList<>();
        List<CodexSkillInventory.Finding> findings = new ArrayList<>();
        List<NameDeclaration> declarations = new ArrayList<>();
        MutableStatus status = new MutableStatus();
        Path agents = realRoot.resolve(".agents");
        if (!Files.exists(agents, LinkOption.NOFOLLOW_LINKS)) {
            return inventory(status, packages, references, findings);
        }
        if (Files.isSymbolicLink(agents)) {
            blocking(findings, AGENTS_PATH_IS_SYMLINK, ".agents",
                    "The project .agents path is a symbolic link and was not followed");
            status.partial = true;
            return inventory(status, packages, references, findings);
        }
        if (!Files.isDirectory(agents, LinkOption.NOFOLLOW_LINKS)) {
            error(findings, DIRECTORY_READ_FAILED, ".agents",
                    "The project .agents path is not a directory");
            status.partial = true;
            return inventory(status, packages, references, findings);
        }
        if (!isAnchoredDirectory(agents, realRoot, ".agents", findings, status)) {
            return inventory(status, packages, references, findings);
        }

        Path skills = agents.resolve("skills");
        if (!Files.exists(skills, LinkOption.NOFOLLOW_LINKS)) {
            return inventory(status, packages, references, findings);
        }
        if (Files.isSymbolicLink(skills)) {
            blocking(findings, SKILLS_PATH_IS_SYMLINK, ".agents/skills",
                    "The project Skill directory is a symbolic link and was not followed");
            status.partial = true;
            return inventory(status, packages, references, findings);
        }
        if (!Files.isDirectory(skills, LinkOption.NOFOLLOW_LINKS)) {
            error(findings, DIRECTORY_READ_FAILED, ".agents/skills",
                    "The project Skill path is not a directory");
            status.partial = true;
            return inventory(status, packages, references, findings);
        }
        if (!isAnchoredDirectory(skills, realRoot, ".agents/skills", findings, status)) {
            return inventory(status, packages, references, findings);
        }

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(skills)) {
            for (Path entry : entries) {
                candidates.add(entry);
                if (candidates.size() > MAX_PACKAGES) {
                    candidates.clear();
                    error(findings, PACKAGE_COUNT_LIMIT_REACHED, ".agents/skills",
                            "Project Skill inventory exceeds the 256-package limit");
                    status.partial = true;
                    return inventory(status, packages, references, findings);
                }
            }
        } catch (IOException exception) {
            error(findings, DIRECTORY_READ_FAILED, ".agents/skills",
                    "The project Skill directory could not be enumerated");
            status.partial = true;
            return inventory(status, packages, references, findings);
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path candidate : candidates) {
            inspectPackage(realRoot, candidate, packages, references, findings, declarations, status);
        }
        addDuplicateNameFindings(declarations, findings);
        return inventory(status, packages, references, findings);
    }

    private static void inspectPackage(
            Path realRoot,
            Path packageDirectory,
            List<CodexSkillInventory.SkillPackage> packages,
            List<CodexSkillInventory.Reference> references,
            List<CodexSkillInventory.Finding> findings,
            List<NameDeclaration> declarations,
            MutableStatus status) {
        String directoryName = packageDirectory.getFileName().toString();
        if (directoryName.length() > 63
                || !directoryName.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            error(findings, INVALID_PACKAGE_DIRECTORY_NAME, ".agents/skills",
                    "A Skill package directory has a name outside the minimal Codex profile");
            return;
        }
        String packagePath = ".agents/skills/" + directoryName;
        if (Files.isSymbolicLink(packageDirectory)) {
            blocking(findings, PACKAGE_PATH_IS_SYMLINK, packagePath,
                    "The Skill package path is a symbolic link and was not followed");
            status.partial = true;
            return;
        }
        if (!Files.isDirectory(packageDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!isAnchoredDirectory(packageDirectory, realRoot, packagePath, findings, status)) {
            return;
        }
        Path skillFile = packageDirectory.resolve("SKILL.md");
        String skillPath = packagePath + "/SKILL.md";
        if (!Files.exists(skillFile, LinkOption.NOFOLLOW_LINKS)) {
            error(findings, MISSING_SKILL_FILE, packagePath,
                    "A directory under .agents/skills has no SKILL.md");
            return;
        }
        if (Files.isSymbolicLink(skillFile)) {
            blocking(findings, SKILL_FILE_IS_SYMLINK, skillPath,
                    "SKILL.md is a symbolic link and was not opened");
            status.partial = true;
            return;
        }

        BasicFileAttributes before;
        byte[] bytes;
        try {
            Path resolvedSkill = skillFile.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!resolvedSkill.startsWith(realRoot)
                    || !resolvedSkill.equals(skillFile.toAbsolutePath().normalize())) {
                blocking(findings, PATH_NOT_ANCHORED, skillPath,
                        "SKILL.md does not resolve to its anchored project path");
                status.partial = true;
                return;
            }
            before = Files.readAttributes(
                    resolvedSkill, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                error(findings, SKILL_READ_FAILED, skillPath, "SKILL.md is not a regular file");
                status.partial = true;
                return;
            }
            if (before.size() > MAX_SKILL_BYTES) {
                error(findings, SKILL_TOO_LARGE, skillPath,
                        "SKILL.md exceeds the 128 KiB inventory limit and was not opened");
                status.partial = true;
                return;
            }
            bytes = readBoundedNoFollow(resolvedSkill);
            BasicFileAttributes after = Files.readAttributes(
                    resolvedSkill, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path resolvedAfter = skillFile.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || bytes.length != after.size()
                    || !resolvedSkill.equals(resolvedAfter)) {
                error(findings, SKILL_CHANGED_DURING_READ, skillPath,
                        "SKILL.md changed while inventory was reading it");
                status.partial = true;
                return;
            }
        } catch (SkillTooLargeException exception) {
            error(findings, SKILL_TOO_LARGE, skillPath,
                    "SKILL.md exceeded the 128 KiB limit while it was being read");
            status.partial = true;
            return;
        } catch (IOException exception) {
            error(findings, SKILL_READ_FAILED, skillPath, "SKILL.md could not be read");
            status.partial = true;
            return;
        }

        ParsedFrontmatter frontmatter = parseFrontmatter(bytes, skillPath, findings);
        if (!frontmatter.declaredName.isEmpty()) {
            declarations.add(new NameDeclaration(frontmatter.declaredName, skillPath));
        }
        PackageInspection support = inspectSupportingPaths(
                realRoot, packageDirectory, packagePath, findings, status);
        CodexSkillReferenceParser.Result referenceResult = CodexSkillReferenceParser.parse(
                frontmatter.lines,
                frontmatter.bodyStart,
                packagePath,
                skillPath,
                support.supportingLogicalPaths,
                !support.partial);
        references.addAll(referenceResult.references());
        findings.addAll(referenceResult.findings());
        if (referenceResult.partial()) {
            status.partial = true;
        }
        CodexSkillInventory.PackageState state = frontmatter.valid && !referenceResult.invalid()
                ? CodexSkillInventory.PackageState.MINIMAL_METADATA_VALID
                : CodexSkillInventory.PackageState.INVALID;
        if (support.partial || referenceResult.partial()) {
            state = CodexSkillInventory.PackageState.PARTIAL;
        }
        packages.add(new CodexSkillInventory.SkillPackage(
                directoryName,
                frontmatter.publicName,
                skillPath,
                bytes.length,
                sha256(bytes),
                frontmatter.descriptionPresent,
                support.supportingFileCount,
                support.risks,
                state));
    }

    private static ParsedFrontmatter parseFrontmatter(
            byte[] bytes, String skillPath, List<CodexSkillInventory.Finding> findings) {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            error(findings, INVALID_UTF8, skillPath, "SKILL.md is not valid UTF-8");
            return ParsedFrontmatter.invalid();
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length == 0 || !"---".equals(stripBom(lines[0]).strip())) {
            error(findings, MISSING_FRONTMATTER, skillPath,
                    "SKILL.md must begin with YAML frontmatter");
            return ParsedFrontmatter.invalid(lines);
        }
        int closing = -1;
        for (int index = 1; index < Math.min(lines.length, 101); index++) {
            if ("---".equals(lines[index].strip())) {
                closing = index;
                break;
            }
        }
        if (closing < 0) {
            error(findings, INVALID_FRONTMATTER, skillPath,
                    "SKILL.md frontmatter has no bounded closing delimiter");
            return ParsedFrontmatter.invalid(lines);
        }
        Map<String, String> values = new LinkedHashMap<>();
        boolean valid = true;
        for (int index = 1; index < closing; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                error(findings, INVALID_FRONTMATTER, skillPath,
                        "Skill frontmatter contains an unsupported YAML construct");
                valid = false;
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = scalar(line.substring(colon + 1).strip());
            if (!key.equals("name") && !key.equals("description")) {
                warning(findings, UNKNOWN_FRONTMATTER_FIELD, skillPath,
                        "Skill frontmatter contains a field outside the minimal Codex profile");
                continue;
            }
            if (value == null || values.putIfAbsent(key, value) != null) {
                error(findings, INVALID_FRONTMATTER, skillPath,
                        "Skill frontmatter contains a duplicate or complex required field");
                valid = false;
            }
        }
        String name = values.getOrDefault("name", "");
        String description = values.getOrDefault("description", "");
        boolean safeName = false;
        if (name.isBlank()) {
            error(findings, MISSING_NAME, skillPath, "Skill frontmatter has no scalar name");
            valid = false;
        } else if (name.length() > 63 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            error(findings, INVALID_NAME, skillPath,
                    "Skill name must use 1-63 lowercase letters or digits separated by hyphens");
            valid = false;
        } else {
            safeName = true;
        }
        String directoryName = Path.of(skillPath).getParent().getFileName().toString();
        if (safeName && !name.equals(directoryName)) {
            error(findings, NAME_DIRECTORY_MISMATCH, skillPath,
                    "Declared Skill name does not match its package directory");
            valid = false;
        }
        if (description.isBlank()) {
            error(findings, MISSING_DESCRIPTION, skillPath,
                    "Skill frontmatter has no scalar description");
            valid = false;
        }
        boolean matchesDirectory = safeName && name.equals(directoryName);
        return new ParsedFrontmatter(
                safeName ? name : "",
                matchesDirectory ? name : "",
                !description.isBlank(),
                valid,
                lines,
                closing + 1);
    }

    private static PackageInspection inspectSupportingPaths(
            Path realRoot,
            Path packageDirectory,
            String packagePath,
            List<CodexSkillInventory.Finding> findings,
            MutableStatus status) {
        SupportAccumulator accumulator = new SupportAccumulator();
        if (Files.isDirectory(packageDirectory.resolve("scripts"), LinkOption.NOFOLLOW_LINKS)) {
            accumulator.risks.add(CodexSkillInventory.Risk.SCRIPTS_DIRECTORY);
        }
        walkSupportingDirectory(realRoot, packageDirectory, packageDirectory, packagePath,
                findings, status, accumulator);
        return new PackageInspection(
                accumulator.supportingFiles,
                Set.copyOf(accumulator.risks),
                Set.copyOf(accumulator.supportingLogicalPaths),
                accumulator.partial);
    }

    private static void walkSupportingDirectory(
            Path realRoot,
            Path packageDirectory,
            Path directory,
            String packagePath,
            List<CodexSkillInventory.Finding> findings,
            MutableStatus status,
            SupportAccumulator accumulator) {
        if (accumulator.stopped) {
            return;
        }
        int remaining = MAX_PACKAGE_ENTRIES - accumulator.entries;
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
                if (children.size() > remaining) {
                    error(findings, PACKAGE_ENTRY_LIMIT_REACHED, packagePath,
                            "Skill package exceeds the 256-entry inventory limit");
                    status.partial = true;
                    accumulator.partial = true;
                    accumulator.stopped = true;
                    return;
                }
            }
        } catch (IOException exception) {
            error(findings, DIRECTORY_READ_FAILED, portable(realRoot.relativize(directory)),
                    "Skill supporting paths could not be fully enumerated");
            status.partial = true;
            accumulator.partial = true;
            return;
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path path : children) {
            if (directory.equals(packageDirectory) && path.equals(packageDirectory.resolve("SKILL.md"))) {
                continue;
            }
            accumulator.entries++;
            String logical = portable(realRoot.relativize(path));
            final BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                error(findings, SUPPORT_PATH_READ_FAILED, logical,
                        "A supporting path could not be classified safely");
                status.partial = true;
                accumulator.partial = true;
                continue;
            }
            if (attributes.isSymbolicLink()) {
                accumulator.risks.add(CodexSkillInventory.Risk.SYMLINK_SUPPORT_PATH);
                blocking(findings, SUPPORT_PATH_IS_SYMLINK, logical,
                        "A supporting path is a symbolic link and was not followed");
                status.partial = true;
                accumulator.partial = true;
                continue;
            }
            if (attributes.isRegularFile()) {
                accumulator.supportingFiles++;
                accumulator.supportingLogicalPaths.add(logical);
                if (looksExecutable(path)) {
                    accumulator.risks.add(CodexSkillInventory.Risk.EXECUTABLE_SUPPORT_FILE);
                }
                continue;
            }
            if (!attributes.isDirectory()) {
                continue;
            }
            int depth = packageDirectory.relativize(path).getNameCount();
            if (depth >= MAX_PACKAGE_DEPTH) {
                if (hasChildren(path)) {
                    error(findings, PACKAGE_DEPTH_LIMIT_REACHED, logical,
                            "Skill package has content below the inventory depth limit");
                    status.partial = true;
                    accumulator.partial = true;
                }
            } else {
                if (!isAnchoredDirectory(path, realRoot, logical, findings, status)) {
                    accumulator.partial = true;
                    continue;
                }
                walkSupportingDirectory(realRoot, packageDirectory, path, packagePath,
                        findings, status, accumulator);
            }
            if (accumulator.stopped) {
                return;
            }
        }
    }

    private static boolean looksExecutable(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean suffix = EXECUTABLE_SUFFIXES.stream().anyMatch(name::endsWith);
        if (suffix) {
            return true;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }

    private static boolean hasChildren(Path directory) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            return children.iterator().hasNext();
        } catch (IOException exception) {
            return true;
        }
    }

    private static void addDuplicateNameFindings(
            List<NameDeclaration> declarations,
            List<CodexSkillInventory.Finding> findings) {
        Map<String, List<NameDeclaration>> byName = new HashMap<>();
        for (NameDeclaration declaration : declarations) {
            byName.computeIfAbsent(declaration.name, ignored -> new ArrayList<>()).add(declaration);
        }
        for (List<NameDeclaration> duplicates : byName.values()) {
            if (duplicates.size() > 1) {
                duplicates.stream().sorted(Comparator.comparing(NameDeclaration::logicalPath))
                        .forEach(declaration -> error(findings, DUPLICATE_DECLARED_NAME,
                                declaration.logicalPath,
                                "Multiple project Skill packages declare the same name"));
            }
        }
    }

    private static CodexSkillInventory inventory(
            MutableStatus status,
            List<CodexSkillInventory.SkillPackage> packages,
            List<CodexSkillInventory.Reference> references,
            List<CodexSkillInventory.Finding> findings) {
        packages.sort(Comparator.comparing(CodexSkillInventory.SkillPackage::logicalPath));
        references.sort(CodexSkillInventory.referenceOrder());
        findings.sort(CodexSkillInventory.findingOrder());
        return new CodexSkillInventory(
                CodexSkillInventory.CURRENT_SCHEMA_VERSION,
                CodexSkillInventory.REFERENCE_PROFILE_ID,
                status.partial ? CodexSkillInventory.Status.PARTIAL : CodexSkillInventory.Status.COMPLETE,
                false,
                false,
                packages,
                references,
                findings);
    }

    private static String scalar(String raw) {
        if (raw.isEmpty() || raw.equals("|") || raw.equals(">") || raw.startsWith("[")
                || raw.startsWith("{") || raw.startsWith("&") || raw.startsWith("*")) {
            return null;
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            String inner = raw.substring(1, raw.length() - 1);
            return inner.contains("\"") || inner.contains("\\") ? null : inner;
        }
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        if (raw.startsWith("#") || raw.contains(" #")) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return Set.of("null", "~", "true", "false", "yes", "no", "on", "off").contains(lower)
                ? null : raw;
    }

    private static boolean isAnchoredDirectory(
            Path directory,
            Path realRoot,
            String logicalPath,
            List<CodexSkillInventory.Finding> findings,
            MutableStatus status) {
        try {
            Path resolved = directory.toRealPath();
            if (!resolved.startsWith(realRoot)
                    || !resolved.equals(directory.toAbsolutePath().normalize())) {
                blocking(findings, PATH_NOT_ANCHORED, logicalPath,
                        "Directory does not resolve to its anchored project path");
                status.partial = true;
                return false;
            }
            return true;
        } catch (IOException exception) {
            error(findings, DIRECTORY_READ_FAILED, logicalPath,
                    "Directory could not be resolved safely");
            status.partial = true;
            return false;
        }
    }

    private static byte[] readBoundedNoFollow(Path file)
            throws IOException, SkillTooLargeException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(file, options);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int total = 0;
            while (true) {
                int count = channel.read(buffer);
                if (count < 0) {
                    return output.toByteArray();
                }
                total += count;
                if (total > MAX_SKILL_BYTES) {
                    throw new SkillTooLargeException();
                }
                output.write(buffer.array(), 0, count);
                buffer.clear();
            }
        }
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java 21", exception);
        }
    }

    private static String portable(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static void warning(List<CodexSkillInventory.Finding> findings,
            CodexSkillInventory.FindingCode code, String path, String summary) {
        findings.add(new CodexSkillInventory.Finding(
                CodexSkillInventory.Severity.WARNING, code, path, summary));
    }

    private static void error(List<CodexSkillInventory.Finding> findings,
            CodexSkillInventory.FindingCode code, String path, String summary) {
        findings.add(new CodexSkillInventory.Finding(
                CodexSkillInventory.Severity.ERROR, code, path, summary));
    }

    private static void blocking(List<CodexSkillInventory.Finding> findings,
            CodexSkillInventory.FindingCode code, String path, String summary) {
        findings.add(new CodexSkillInventory.Finding(
                CodexSkillInventory.Severity.BLOCKING, code, path, summary));
    }

    private record ParsedFrontmatter(
            String declaredName,
            String publicName,
            boolean descriptionPresent,
            boolean valid,
            String[] lines,
            int bodyStart) {
        private static ParsedFrontmatter invalid() {
            return new ParsedFrontmatter("", "", false, false, new String[0], 0);
        }

        private static ParsedFrontmatter invalid(String[] lines) {
            return new ParsedFrontmatter("", "", false, false, lines, lines.length);
        }
    }

    private record NameDeclaration(String name, String logicalPath) {}

    private record PackageInspection(
            int supportingFileCount,
            Set<CodexSkillInventory.Risk> risks,
            Set<String> supportingLogicalPaths,
            boolean partial) {}

    private static final class SupportAccumulator {
        private final EnumSet<CodexSkillInventory.Risk> risks =
                EnumSet.noneOf(CodexSkillInventory.Risk.class);
        private final Set<String> supportingLogicalPaths = new TreeSet<>();
        private int supportingFiles;
        private int entries;
        private boolean partial;
        private boolean stopped;
    }

    private static final class MutableStatus {
        private boolean partial;
    }

    private static final class SkillTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
