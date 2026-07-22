package dev.stevecreate.agent.core.deployment;

public record BackupRestoreDrillPlan(
        boolean verifyManifestBeforeRestore,
        boolean restoreToDisposableTarget,
        boolean verifyFingerprintAfterRestore) {}
