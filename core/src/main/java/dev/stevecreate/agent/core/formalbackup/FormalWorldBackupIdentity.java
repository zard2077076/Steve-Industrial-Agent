package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Exact completed backup identity; it is evidence, not restore or deployment authority. */
public record FormalWorldBackupIdentity(
        String backupIdentity,
        FormalWorldIdentity worldIdentity,
        String sourceFingerprint,
        String runtimeFingerprint,
        Instant backupTimestamp,
        String manifestHash,
        long fileCount,
        long totalBytes,
        String policyVersion,
        String toolVersion,
        String gitHead,
        FormalBackupCompletionState completionState,
        Path canonicalBackupTarget) {
    public FormalWorldBackupIdentity {
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        if (!backupIdentity.matches("formal-backup:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backupIdentity is invalid");
        }
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        hash(sourceFingerprint, "sourceFingerprint");
        hash(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(backupTimestamp, "backupTimestamp");
        hash(manifestHash, "manifestHash");
        if (fileCount < 1 || fileCount > 1_000_000 || totalBytes < 0) {
            throw new IllegalArgumentException("backup counts are invalid");
        }
        policyVersion = text(policyVersion, "policyVersion");
        toolVersion = text(toolVersion, "toolVersion");
        Objects.requireNonNull(gitHead, "gitHead");
        if (!gitHead.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("gitHead is invalid");
        Objects.requireNonNull(completionState, "completionState");
        Objects.requireNonNull(canonicalBackupTarget, "canonicalBackupTarget");
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
