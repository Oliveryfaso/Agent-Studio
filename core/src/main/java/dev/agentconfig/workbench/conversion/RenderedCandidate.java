package dev.agentconfig.workbench.conversion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Ephemeral candidate bytes. This object must never be serialized into a ConversionPlan. */
public final class RenderedCandidate {
    private final String logicalPath;
    private final byte[] bytes;
    private final String sha256;
    private final String rendererProfile;

    RenderedCandidate(String logicalPath, byte[] bytes, String rendererProfile) {
        this.logicalPath = ConversionValidation.logicalPath(logicalPath);
        this.bytes = bytes.clone();
        this.rendererProfile = ConversionValidation.id(rendererProfile, "renderer profile");
        this.sha256 = sha256(this.bytes);
    }

    public String logicalPath() {
        return logicalPath;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public long byteSize() {
        return bytes.length;
    }

    public String sha256() {
        return sha256;
    }

    public String rendererProfile() {
        return rendererProfile;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
