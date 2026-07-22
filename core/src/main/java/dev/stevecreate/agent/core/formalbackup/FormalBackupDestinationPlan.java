package dev.stevecreate.agent.core.formalbackup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Destination approval evidence only; it cannot copy, publish, restore or execute. */
public record FormalBackupDestinationPlan(
        String planIdentity,
        String worldIdentity,
        String sourceFingerprint,
        String runtimeFingerprint,
        Path canonicalSourceRoot,
        Path canonicalBackupRoot,
        Path plannedTarget,
        Path plannedStaging,
        long sourceFileCount,
        long estimatedSourceBytes,
        long safetyMarginBytes,
        long requiredSpaceBytes,
        long observedUsableSpaceBytes,
        Instant requestedAt,
        String policyVersion,
        String toolVersion,
        String gitHead,
        boolean gitIgnoredOutput,
        boolean outsideFormalPlatform,
        boolean overwriteAllowed,
        boolean cloudUploadAllowed,
        boolean backupCreationExecuted,
        boolean formalWorldWriteAllowed) {
    public FormalBackupDestinationPlan {
        Objects.requireNonNull(planIdentity, "planIdentity");
        if (!planIdentity.matches("backup-plan:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("planIdentity is invalid");
        }
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (!worldIdentity.matches("world:[0-9a-f]{64}")) throw new IllegalArgumentException("worldIdentity is invalid");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (!sourceFingerprint.matches("[0-9a-f]{64}") || !runtimeFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("source/runtime fingerprint is invalid");
        }
        Objects.requireNonNull(canonicalSourceRoot, "canonicalSourceRoot");
        Objects.requireNonNull(canonicalBackupRoot, "canonicalBackupRoot");
        Objects.requireNonNull(plannedTarget, "plannedTarget");
        Objects.requireNonNull(plannedStaging, "plannedStaging");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(toolVersion, "toolVersion");
        Objects.requireNonNull(gitHead, "gitHead");
        if (!gitHead.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("gitHead is invalid");
        if (sourceFileCount < 1 || estimatedSourceBytes < 1 || safetyMarginBytes < 1
                || requiredSpaceBytes != Math.addExact(estimatedSourceBytes, safetyMarginBytes)
                || observedUsableSpaceBytes < requiredSpaceBytes
                || !gitIgnoredOutput || !outsideFormalPlatform || overwriteAllowed || cloudUploadAllowed
                || backupCreationExecuted || formalWorldWriteAllowed) {
            throw new IllegalArgumentException("formal backup destination plan violates its safety contract");
        }
    }
}
