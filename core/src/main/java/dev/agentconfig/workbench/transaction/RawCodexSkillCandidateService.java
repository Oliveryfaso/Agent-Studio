package dev.agentconfig.workbench.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Validates user-edited SKILL.md bytes without interpreting or executing the body. */
public final class RawCodexSkillCandidateService {
    public static final String VALIDATION_PROFILE = "codex-raw-skill-static-v1";

    public ControlledSkillCandidate validate(String logicalPath, String content) {
        if (logicalPath == null || !logicalPath.matches(
                "\\.agents/skills/[a-z0-9]+(?:-[a-z0-9]+)*/SKILL\\.md")) {
            throw invalid("RAW_LOGICAL_PATH_INVALID");
        }
        String packageName = logicalPath.substring(".agents/skills/".length(),
                logicalPath.length() - "/SKILL.md".length());
        if (packageName.length() > 63) throw invalid("RAW_LOGICAL_PATH_INVALID");
        if (content == null || content.isEmpty()) throw invalid("RAW_CONTENT_REQUIRED");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128 * 1024) throw invalid("RAW_CONTENT_TOO_LARGE");
        if (content.startsWith("\ufeff") || content.contains("\r")
                || content.contains("\u0000") || !content.endsWith("\n")) {
            throw invalid("RAW_TEXT_FORMAT_INVALID");
        }
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\t') {
                throw invalid("RAW_TEXT_FORMAT_INVALID");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= content.length()
                        || !Character.isSurrogatePair(character, content.charAt(index + 1))) {
                    throw invalid("RAW_TEXT_FORMAT_INVALID");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalid("RAW_TEXT_FORMAT_INVALID");
            }
        }
        String expectedName = logicalPath.substring(".agents/skills/".length(),
                logicalPath.length() - "/SKILL.md".length());
        validateFrontmatter(content, expectedName);
        return new ControlledSkillCandidate(logicalPath, bytes, hash(bytes), VALIDATION_PROFILE);
    }

    private static void validateFrontmatter(String content, String expectedName) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 5 || !"---".equals(lines[0])) {
            throw invalid("RAW_FRONTMATTER_REQUIRED");
        }
        int closing = -1;
        int frontmatterBytes = 4;
        for (int index = 1; index < lines.length && index <= 128; index++) {
            frontmatterBytes += lines[index].getBytes(StandardCharsets.UTF_8).length + 1;
            if (frontmatterBytes > 16 * 1024) throw invalid("RAW_FRONTMATTER_TOO_LARGE");
            if ("---".equals(lines[index])) {
                closing = index;
                break;
            }
        }
        if (closing < 2) throw invalid("RAW_FRONTMATTER_REQUIRED");
        String declaredName = null;
        String description = null;
        for (int index = 1; index < closing; index++) {
            String line = lines[index];
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 1) throw invalid("RAW_FRONTMATTER_UNSUPPORTED");
            String rawKey = line.substring(0, colon);
            if (!rawKey.equals(rawKey.strip()) || !rawKey.matches("[A-Za-z0-9_-]+")) {
                throw invalid("RAW_FRONTMATTER_UNSUPPORTED");
            }
            String key = rawKey;
            String suffix = line.substring(colon + 1);
            if ("name".equals(key)) {
                if (declaredName != null) throw invalid("RAW_FRONTMATTER_DUPLICATE_NAME");
                declaredName = nameScalar(suffix, expectedName);
            } else if ("description".equals(key)) {
                if (description != null) throw invalid("RAW_FRONTMATTER_DUPLICATE_DESCRIPTION");
                description = descriptionScalar(suffix);
            }
        }
        if (!expectedName.equals(declaredName)) throw invalid("RAW_FRONTMATTER_NAME_MISMATCH");
        if (description == null || description.isBlank()) {
            throw invalid("RAW_FRONTMATTER_DESCRIPTION_REQUIRED");
        }
        if (description.getBytes(StandardCharsets.UTF_8).length > 1_024
                || description.contains("<") || description.contains(">")) {
            throw invalid("RAW_FRONTMATTER_DESCRIPTION_INVALID");
        }
    }

    private static String nameScalar(String suffix, String expectedName) {
        if (suffix.isEmpty() || !Character.isWhitespace(suffix.charAt(0))) return "";
        String value = suffix.strip();
        if (value.equals(expectedName) || value.equals("'" + expectedName + "'")
                || value.equals("\"" + expectedName + "\"")) return expectedName;
        return "";
    }

    private static String descriptionScalar(String suffix) {
        if (suffix.isEmpty() || !Character.isWhitespace(suffix.charAt(0))) return "";
        String value = suffix.strip();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                && !value.substring(1, value.length() - 1).contains("\\")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.startsWith("#")
                || value.equals("|") || value.equals(">") || value.equals("~")
                || value.equalsIgnoreCase("null") || value.startsWith("*")
                || value.startsWith("&") || value.startsWith("!")
                || value.startsWith("{") || value.startsWith("[")
                || value.matches("(?i:true|false|yes|no|on|off)")) return "";
        if (value.startsWith("'") || value.startsWith("\"")
                || value.endsWith("'") || value.endsWith("\"")) return "";
        return value;
    }

    private static ValidationException invalid(String code) {
        return new ValidationException(code);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        ValidationException(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }
}
