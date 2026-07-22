package dev.stevecreate.agent.core.deployment;

import java.util.Objects;

public record BackupOperationResult(
        BackupOperationStatus status,
        String backupIdentity,
        String manifestHash,
        String detail) {
    public BackupOperationResult {
        Objects.requireNonNull(status, "status");
        backupIdentity = WorldEnvironmentEvidence.text(backupIdentity, "backupIdentity");
        manifestHash = BackupManifest.sha256(manifestHash, "manifestHash");
        detail = WorldEnvironmentEvidence.text(detail, "detail");
    }
}
