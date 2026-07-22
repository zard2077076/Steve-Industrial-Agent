package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.util.Objects;

public record FormalBackupCompletionMarker(
        FormalBackupCompletionState state,
        String backupIdentity,
        String planIdentity,
        String worldIdentity,
        String sourceFingerprint,
        String runtimeFingerprint,
        String manifestHash,
        long fileCount,
        long totalBytes,
        Instant completedAt,
        String policyVersion,
        String toolVersion,
        String gitHead) {
    public FormalBackupCompletionMarker {
        Objects.requireNonNull(state, "state");
        hashIdentity(backupIdentity, "formal-backup:", "backupIdentity");
        hashIdentity(planIdentity, "backup-plan:", "planIdentity");
        hashIdentity(worldIdentity, "world:", "worldIdentity");
        hash(sourceFingerprint, "sourceFingerprint");
        hash(runtimeFingerprint, "runtimeFingerprint");
        hash(manifestHash, "manifestHash");
        if (fileCount < 1 || fileCount > 1_000_000 || totalBytes < 0) {
            throw new IllegalArgumentException("completion counts are invalid");
        }
        Objects.requireNonNull(completedAt, "completedAt");
        policyVersion = text(policyVersion, "policyVersion");
        toolVersion = text(toolVersion, "toolVersion");
        Objects.requireNonNull(gitHead, "gitHead");
        if (!gitHead.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("gitHead is invalid");
    }

    private static void hashIdentity(String value, String prefix, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches(java.util.regex.Pattern.quote(prefix) + "[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 4_096) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
