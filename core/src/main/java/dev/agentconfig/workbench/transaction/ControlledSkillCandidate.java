package dev.agentconfig.workbench.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Final, validated bytes accepted by the single-Skill transaction boundary. */
public final class ControlledSkillCandidate {
    private final String logicalPath;
    private final byte[] bytes;
    private final String sha256;
    private final String validationProfileId;

    ControlledSkillCandidate(
            String logicalPath, byte[] bytes, String sha256, String validationProfileId) {
        if (logicalPath == null || !logicalPath.matches(
                "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")) {
            throw new IllegalArgumentException("logicalPath");
        }
        String name = logicalPath.substring(".agents/skills/".length(),
                logicalPath.length() - "/SKILL.md".length());
        if (name.length() > 63) throw new IllegalArgumentException("logicalPath");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 1 || bytes.length > 128 * 1024) {
            throw new IllegalArgumentException("candidate byte budget");
        }
        this.logicalPath = logicalPath;
        this.bytes = bytes.clone();
        this.sha256 = required(sha256, "sha256");
        if (!this.sha256.equals(hash(this.bytes))) {
            throw new IllegalArgumentException("candidate hash");
        }
        this.validationProfileId = required(validationProfileId, "validationProfileId");
    }

    public String logicalPath() { return logicalPath; }
    public byte[] bytes() { return bytes.clone(); }
    public String content() { return new String(bytes, StandardCharsets.UTF_8); }
    public String sha256() { return sha256; }
    public String validationProfileId() { return validationProfileId; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
