package dev.stevecreate.agent.core.deployment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BackupVerifier {
    public BackupVerification verify(BackupPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<BackupVerificationFailure> failures = new ArrayList<>();
        if (plan.environmentClassification() != WorldEnvironmentType.ISOLATED_TEST_WORLD) {
            failures.add(BackupVerificationFailure.ENVIRONMENT_NOT_ISOLATED);
            return new BackupVerification(failures, false);
        }
        try {
            if (!plan.pathPolicy().allowsSource(plan.sourceWorldRoot())) {
                failures.add(BackupVerificationFailure.SOURCE_OUTSIDE_ISOLATED_ROOT);
            }
        } catch (IOException exception) {
            failures.add(BackupVerificationFailure.SOURCE_OUTSIDE_ISOLATED_ROOT);
        }
        try {
            if (!plan.pathPolicy().allowsTarget(plan.backupTarget())) {
                failures.add(BackupVerificationFailure.TARGET_OUTSIDE_BACKUP_ROOT);
            }
        } catch (IOException exception) {
            failures.add(BackupVerificationFailure.TARGET_OUTSIDE_BACKUP_ROOT);
        }
        if (plan.sourceWorldRoot().equals(plan.backupTarget())) {
            failures.add(BackupVerificationFailure.SOURCE_TARGET_COLLISION);
        }
        if (plan.manifest().state() != BackupManifestState.COMPLETE) {
            failures.add(BackupVerificationFailure.MANIFEST_INCOMPLETE);
        }
        if (!plan.manifest().hasValidHash()) {
            failures.add(BackupVerificationFailure.MANIFEST_HASH_INVALID);
        }
        if (plan.estimatedBackupSizeBytes() != plan.manifest().totalBytes()) {
            failures.add(BackupVerificationFailure.ESTIMATED_SIZE_MISMATCH);
        }
        if (plan.observedAvailableSpaceBytes() < plan.requiredAvailableSpaceBytes()) {
            failures.add(BackupVerificationFailure.INSUFFICIENT_SPACE);
        }
        if (plan.atomicityStrategy() != BackupAtomicityStrategy.STAGING_THEN_ATOMIC_RENAME) {
            failures.add(BackupVerificationFailure.ATOMICITY_STRATEGY_UNSAFE);
        }
        if (plan.consistencyStrategy() != BackupConsistencyStrategy.QUIESCED_WORLD_SNAPSHOT) {
            failures.add(BackupVerificationFailure.CONSISTENCY_STRATEGY_UNSAFE);
        }
        if (!plan.restoreDrillPlan().verifyManifestBeforeRestore()
                || !plan.restoreDrillPlan().restoreToDisposableTarget()
                || !plan.restoreDrillPlan().verifyFingerprintAfterRestore()) {
            failures.add(BackupVerificationFailure.RESTORE_DRILL_INCOMPLETE);
        }
        if (plan.failureHandling() != BackupFailureHandling.ABORT_KEEP_SOURCE_DELETE_STAGING) {
            failures.add(BackupVerificationFailure.FAILURE_HANDLING_UNSAFE);
        }
        if (plan.approvalRequirement() != BackupApprovalRequirement.EXPLICIT_TEST_ONLY
                || plan.approvalState() != BackupApprovalState.APPROVED_TEST_ONLY) {
            failures.add(BackupVerificationFailure.APPROVAL_MISSING);
        }
        if (!plan.backupIdentity().equals(plan.journalBackupIdentity())) {
            failures.add(BackupVerificationFailure.JOURNAL_IDENTITY_MISMATCH);
        }
        return new BackupVerification(failures, failures.isEmpty());
    }
}
