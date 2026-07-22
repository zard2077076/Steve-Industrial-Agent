package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;

public record BackupEvidence(
        BackupVerificationResult verification,
        RestoreDrillResult restoreDrill,
        BackupRetentionPolicy retentionPolicy,
        boolean deploymentAuthorityGranted,
        boolean formalExecutionAllowed) {
    public BackupEvidence {
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(restoreDrill, "restoreDrill");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        if (!verification.identity().equals(restoreDrill.backupIdentity())
                || deploymentAuthorityGranted || formalExecutionAllowed) {
            throw new IllegalArgumentException("backup evidence cannot grant deployment authority");
        }
    }
}
