package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;

public record FormalBackupExcludedEntry(
        String relativePath,
        long sizeBytes,
        long lastModifiedEpochMillis,
        FormalBackupExclusionReason reason) {
    public FormalBackupExcludedEntry {
        relativePath = FormalBackupFileEntry.relative(relativePath);
        if (sizeBytes < 0 || lastModifiedEpochMillis < 0) {
            throw new IllegalArgumentException("excluded file metadata is negative");
        }
        Objects.requireNonNull(reason, "reason");
    }
}
