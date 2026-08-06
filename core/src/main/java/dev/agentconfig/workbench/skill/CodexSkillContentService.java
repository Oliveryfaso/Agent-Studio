package dev.agentconfig.workbench.skill;

import dev.agentconfig.workbench.skill.CodexSkillInventory.PackageState;
import dev.agentconfig.workbench.skill.CodexSkillInventory.SkillPackage;
import dev.agentconfig.workbench.skilldraft.CodexSkillFormProjection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Safely reads one Skill selected from a fresh project inventory. */
public final class CodexSkillContentService {
    private static final int MAX_BYTES = 128 * 1024;

    public CodexSkillContent read(Path authorizedRoot, String logicalPath) throws IOException {
        if (logicalPath == null || !logicalPath.matches(
                "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")) {
            throw new IllegalArgumentException("logicalPath");
        }
        Path root = authorizedRoot.toAbsolutePath().normalize().toRealPath();
        CodexSkillInventory inventory = new CodexSkillInventoryService().inspect(root);
        List<SkillPackage> matches = inventory.packages().stream()
                .filter(skill -> skill.logicalPath().equals(logicalPath)
                        && skill.state() != PackageState.PARTIAL)
                .toList();
        if (matches.size() != 1) throw new UnavailableException();
        SkillPackage selected = matches.getFirst();
        Path target = root.resolve(logicalPath).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("logicalPath");
        Path cursor = root;
        for (String part : logicalPath.split("/")) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new UnavailableException();
        }
        Path realTarget = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realTarget.equals(target) || !Files.isRegularFile(
                realTarget, LinkOption.NOFOLLOW_LINKS)) throw new UnavailableException();
        byte[] bytes = readBounded(realTarget);
        String sha256 = sha256(bytes);
        if (bytes.length != selected.byteSize() || !sha256.equals(selected.sha256())) {
            throw new ChangedException();
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidUtf8Exception();
        }
        return new CodexSkillContent(logicalPath, content, bytes.length, sha256,
                CodexSkillFormProjection.parse(content));
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int total = 0;
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                total += buffer.remaining();
                if (total > MAX_BYTES) throw new TooLargeException();
                output.write(buffer.array(), buffer.position(), buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static final class UnavailableException extends IOException {
        private static final long serialVersionUID = 1L;
    }
    public static final class ChangedException extends IOException {
        private static final long serialVersionUID = 1L;
    }
    public static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
    public static final class InvalidUtf8Exception extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
