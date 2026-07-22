package dev.stevecreate.agent.core.deployment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies and atomically consumes supplied approvals; it never issues or upgrades one. */
public final class HumanApprovalGate {
    private final Set<String> consumedTokenHashes = new HashSet<>();

    /** Performs the complete check without consuming the one-time token. */
    public synchronized HumanApprovalCheck inspect(
            HumanApprovalToken approval,
            HumanApprovalRequest request,
            Instant evaluatedAt) {
        return check(approval, request, evaluatedAt);
    }

    public synchronized HumanApprovalCheck verifyAndConsume(
            HumanApprovalToken approval,
            HumanApprovalRequest request,
            Instant evaluatedAt) {
        HumanApprovalCheck check = check(approval, request, evaluatedAt);
        if (check.accepted()) {
            consumedTokenHashes.add(approval.oneTimeTokenHash());
        }
        return check;
    }

    private HumanApprovalCheck check(
            HumanApprovalToken approval,
            HumanApprovalRequest request,
            Instant evaluatedAt) {
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        List<HumanApprovalFailure> failures = new ArrayList<>();

        if (approval.decision() != HumanApprovalDecision.APPROVED) {
            failures.add(HumanApprovalFailure.APPROVAL_NOT_GRANTED);
        }
        if (!evaluatedAt.isBefore(approval.expiresAt())) {
            failures.add(HumanApprovalFailure.APPROVAL_EXPIRED);
        }
        if (approval.environmentClassification() != request.environmentClassification()) {
            failures.add(HumanApprovalFailure.ENVIRONMENT_MISMATCH);
        }
        if (!approval.worldIdentity().equals(request.worldIdentity())) {
            failures.add(HumanApprovalFailure.WORLD_IDENTITY_MISMATCH);
        }
        if (!approval.worldSnapshotFingerprint().equals(request.worldSnapshotFingerprint())) {
            failures.add(HumanApprovalFailure.WORLD_SNAPSHOT_MISMATCH);
        }
        if (!approval.runtimeFingerprint().equals(request.runtimeFingerprint())) {
            failures.add(HumanApprovalFailure.RUNTIME_FINGERPRINT_MISMATCH);
        }
        if (approval.reloadGeneration() != request.reloadGeneration()) {
            failures.add(HumanApprovalFailure.RELOAD_GENERATION_MISMATCH);
        }
        if (!approval.exactRegion().equals(request.exactRegion())) {
            failures.add(HumanApprovalFailure.REGION_MISMATCH);
        }
        if (!approval.exactTarget().equals(request.exactTarget())) {
            failures.add(HumanApprovalFailure.TARGET_MISMATCH);
        }
        if (approval.exactQuantity() != request.exactQuantity()) {
            failures.add(HumanApprovalFailure.QUANTITY_MISMATCH);
        }
        if (approval.exactMutationBudget() != request.exactMutationBudget()) {
            failures.add(HumanApprovalFailure.MUTATION_BUDGET_MISMATCH);
        }
        if (!approval.exactDeploymentPolicy().equals(request.exactDeploymentPolicy())) {
            failures.add(HumanApprovalFailure.DEPLOYMENT_POLICY_MISMATCH);
        }
        if (!approval.previewHash().equals(request.previewHash())) {
            failures.add(HumanApprovalFailure.PREVIEW_HASH_MISMATCH);
        }
        if (approval.authorizerType() == HumanApprovalAuthorizerType.TEST_ONLY
                && request.environmentClassification() != WorldEnvironmentType.ISOLATED_TEST_WORLD) {
            failures.add(HumanApprovalFailure.TEST_ONLY_SCOPE_FORBIDDEN);
        }
        if (consumedTokenHashes.contains(approval.oneTimeTokenHash())) {
            failures.add(HumanApprovalFailure.TOKEN_ALREADY_CONSUMED);
        }

        return new HumanApprovalCheck(failures, failures.isEmpty());
    }
}
