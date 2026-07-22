package dev.stevecreate.agent.core.survey;

import java.util.Objects;

public record FormalFileFingerprintEntry(
        String relativePath,
        long sizeBytes,
        long lastModifiedEpochMillis,
        String sha256) {
    public static final String NOT_HASHED = "not-hashed";

    public FormalFileFingerprintEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank() || relativePath.length() > 16_384 || relativePath.startsWith("/")
                || relativePath.contains("..") || relativePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relativePath is invalid");
        }
        if (sizeBytes < 0 || lastModifiedEpochMillis < 0) {
            throw new IllegalArgumentException("file metadata must be non-negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (!NOT_HASHED.equals(sha256) && !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 is invalid");
        }
    }
}
