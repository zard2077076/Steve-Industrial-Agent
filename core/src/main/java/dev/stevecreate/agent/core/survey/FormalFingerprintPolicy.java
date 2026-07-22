package dev.stevecreate.agent.core.survey;

import java.util.Set;

public record FormalFingerprintPolicy(int maxFiles, Set<String> keyRelativePaths, boolean hashAllAllowedContent) {
    public FormalFingerprintPolicy(int maxFiles, Set<String> keyRelativePaths) {
        this(maxFiles, keyRelativePaths, false);
    }

    public FormalFingerprintPolicy {
        if (maxFiles <= 0 || maxFiles > 1_000_000) throw new IllegalArgumentException("maxFiles is invalid");
        keyRelativePaths = Set.copyOf(keyRelativePaths);
        if (keyRelativePaths.size() > 1_024 || keyRelativePaths.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 4_096
                        || value.startsWith("/") || value.contains("..") || value.indexOf('\\') >= 0)) {
            throw new IllegalArgumentException("keyRelativePaths are invalid");
        }
    }
}
