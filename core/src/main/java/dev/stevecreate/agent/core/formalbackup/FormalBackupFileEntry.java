package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;

public record FormalBackupFileEntry(
        String relativePath,
        long sizeBytes,
        long lastModifiedEpochMillis,
        String sha256,
        FormalBackupFilePrivacy privacy) {
    public FormalBackupFileEntry {
        relativePath = relative(relativePath);
        if (sizeBytes < 0 || lastModifiedEpochMillis < 0) {
            throw new IllegalArgumentException("backup file metadata is negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is invalid");
        Objects.requireNonNull(privacy, "privacy");
    }

    static String relative(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isBlank() || value.length() > 4_096 || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("relativePath is invalid");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("relativePath is invalid");
            }
        }
        return value;
    }
}
