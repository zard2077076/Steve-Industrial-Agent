package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.Objects;

public record BackupPlan(
        String backupIdentity,
        String worldIdentity,
        WorldEnvironmentType environmentClassification,
        Path sourceWorldRoot,
        Path backupTarget,
        BackupPathPolicy pathPolicy,
        long estimatedBackupSizeBytes,
        long requiredAvailableSpaceBytes,
        long observedAvailableSpaceBytes,
        BackupManifest manifest,
        BackupTargetStrategy targetStrategy,
        BackupAtomicityStrategy atomicityStrategy,
        BackupConsistencyStrategy consistencyStrategy,
        BackupRestoreDrillPlan restoreDrillPlan,
        BackupRetentionPolicy retentionPolicy,
        BackupFailureHandling failureHandling,
        BackupApprovalRequirement approvalRequirement,
        BackupApprovalState approvalState,
        String journalBackupIdentity) {
    public BackupPlan {
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        if (!backupIdentity.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("backupIdentity is unsafe");
        }
        worldIdentity = WorldEnvironmentEvidence.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        sourceWorldRoot = normalized(sourceWorldRoot, "sourceWorldRoot");
        backupTarget = normalized(backupTarget, "backupTarget");
        Objects.requireNonNull(pathPolicy, "pathPolicy");
        if (estimatedBackupSizeBytes < 0 || requiredAvailableSpaceBytes < 0
                || observedAvailableSpaceBytes < 0) {
            throw new IllegalArgumentException("backup size cannot be negative");
        }
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(targetStrategy, "targetStrategy");
        Objects.requireNonNull(atomicityStrategy, "atomicityStrategy");
        Objects.requireNonNull(consistencyStrategy, "consistencyStrategy");
        Objects.requireNonNull(restoreDrillPlan, "restoreDrillPlan");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        Objects.requireNonNull(failureHandling, "failureHandling");
        Objects.requireNonNull(approvalRequirement, "approvalRequirement");
        Objects.requireNonNull(approvalState, "approvalState");
        journalBackupIdentity = WorldEnvironmentEvidence.text(
                journalBackupIdentity, "journalBackupIdentity");
    }

    private static Path normalized(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
