package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FormalDeploymentApprovalRequest(
        String requestIdentity,
        FormalDeploymentCandidatePackage candidatePackage,
        Optional<ApprovalScope> scope,
        FormalApprovalRequestStatus status,
        ApprovalEvidence evidence,
        List<String> missingAuthorizations,
        Instant createdAt,
        FormalDeploymentApprovalDecision decision,
        boolean automaticallyApproved,
        boolean executionAllowed) {
    public FormalDeploymentApprovalRequest {
        Objects.requireNonNull(requestIdentity, "requestIdentity");
        if (!requestIdentity.matches("formal-approval-request:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestIdentity is invalid");
        }
        Objects.requireNonNull(candidatePackage, "candidatePackage");
        scope = Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidence, "evidence");
        missingAuthorizations = List.copyOf(Objects.requireNonNull(
                missingAuthorizations, "missingAuthorizations"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(decision, "decision");
        if (!evidence.candidatePackageIdentity().equals(candidatePackage.packageIdentity())
                || decision.state() != FormalApprovalDecisionState.ABSENT
                || decision.environment() != FormalApprovalEnvironment.FORMAL_SOURCE
                || automaticallyApproved || executionAllowed) {
            throw new IllegalArgumentException("formal approval request created decision or execution authority");
        }
        if (status == FormalApprovalRequestStatus.AWAITING_USER_SELECTION
                && (scope.isPresent() || evidence.userSelectionPresent()
                        || !missingAuthorizations.contains("USER_SELECTION"))) {
            throw new IllegalArgumentException("awaiting-selection request is inconsistent");
        }
        if (status == FormalApprovalRequestStatus.PENDING_USER_APPROVAL) {
            ApprovalScope exact = scope.orElseThrow(() -> new IllegalArgumentException("selected request lacks scope"));
            if (!evidence.userSelectionPresent()
                    || !exact.candidatePackageIdentity().equals(candidatePackage.packageIdentity())
                    || !exact.worldIdentity().equals(candidatePackage.worldIdentity())
                    || !exact.previewHash().equals(candidatePackage.previewHash().orElse(""))
                    || !exact.backupIdentity().equals(candidatePackage.backupIdentity().backupIdentity())
                    || !exact.worldSnapshotFingerprint().equals(candidatePackage.worldSnapshotFingerprint())
                    || !exact.runtimeFingerprint().equals(candidatePackage.runtimeFingerprint())
                    || !exact.target().equals(candidatePackage.target())
                    || exact.quantity() != candidatePackage.quantity()) {
                throw new IllegalArgumentException("approval scope drifted from the selected candidate");
            }
        }
    }
}
