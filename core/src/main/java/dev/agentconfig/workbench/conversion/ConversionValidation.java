package dev.agentconfig.workbench.conversion;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class ConversionValidation {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ConversionValidation() {}

    static String id(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a stable, portable identifier");
        }
        return value;
    }

    static String sha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String logicalPath(String value) {
        Objects.requireNonNull(value, "logicalPath");
        if (value.isBlank() || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\") || value.endsWith("/")) {
            throw new IllegalArgumentException("logicalPath must be a portable relative file path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("logicalPath must be normalized and remain relative");
            }
        }
        return value;
    }

    static <T> List<T> immutableList(List<T> values, String name) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return copy;
    }
}
