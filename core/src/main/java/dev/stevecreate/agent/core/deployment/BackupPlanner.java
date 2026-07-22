package dev.stevecreate.agent.core.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class BackupPlanner {
    public BackupPlan planIsolated(
            String backupIdentity,
            String worldIdentity,
            Path sourceWorldRoot,
            Path backupTarget,
            BackupPathPolicy pathPolicy,
            String journalBackupIdentity,
            BackupApprovalState approvalState) throws IOException {
        Objects.requireNonNull(pathPolicy, "pathPolicy");
        if (!pathPolicy.allowsSource(sourceWorldRoot)
                || !pathPolicy.allowsTarget(backupTarget)) {
            throw new IOException("backup paths are outside isolated policy");
        }
        BackupManifest manifest = new BackupManifestBuilder()
                .buildIsolated(sourceWorldRoot, pathPolicy);
        long estimated = manifest.totalBytes();
        long required = Math.addExact(estimated, Math.max(4_096L, estimated / 10));
        long available = Files.getFileStore(pathPolicy.canonicalBackupRoot()).getUsableSpace();
        return new BackupPlan(
                backupIdentity, worldIdentity, WorldEnvironmentType.ISOLATED_TEST_WORLD,
                sourceWorldRoot, backupTarget, pathPolicy, estimated, required, available, manifest,
                BackupTargetStrategy.ISOLATED_BACKUP_ROOT,
                BackupAtomicityStrategy.STAGING_THEN_ATOMIC_RENAME,
                BackupConsistencyStrategy.QUIESCED_WORLD_SNAPSHOT,
                new BackupRestoreDrillPlan(true, true, true),
                new BackupRetentionPolicy(3, Duration.ofDays(7), true),
                BackupFailureHandling.ABORT_KEEP_SOURCE_DELETE_STAGING,
                BackupApprovalRequirement.EXPLICIT_TEST_ONLY, approvalState,
                journalBackupIdentity);
    }
}
