package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.DeploymentBudget;
import dev.stevecreate.agent.core.model.ResourceId;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/** Creates requests only. It has no formal approval-decision or execution path. */
public final class FormalDeploymentApprovalRequestFactory {
    public FormalDeploymentApprovalRequest awaitUserSelection(
            FormalDeploymentCandidatePackage candidate,
            Instant createdAt) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!createdAt.isBefore(candidate.expiresAt())) {
            throw new IllegalArgumentException("candidate expired before approval request creation");
        }
        List<String> missing = candidate.missingAuthorizations().stream().distinct().sorted().toList();
        if (!missing.contains("USER_SELECTION")) throw new IllegalArgumentException("selection blocker is missing");
        ApprovalEvidence evidence = new ApprovalEvidence(candidate.packageIdentity(), false, missing,
                createdAt, "formal-candidate-package-v1");
        String identity = requestIdentity(candidate.packageIdentity(), "AWAITING_USER_SELECTION",
                createdAt, Optional.empty());
        return new FormalDeploymentApprovalRequest(identity, candidate, Optional.empty(),
                FormalApprovalRequestStatus.AWAITING_USER_SELECTION, evidence, missing, createdAt,
                FormalDeploymentApprovalDecision.absentFormal(), false, false);
    }

    public FormalDeploymentApprovalRequest forUserSelectedCandidate(
            FormalDeploymentCandidatePackage candidate,
            String userSelectedCandidateIdentity,
            Instant createdAt) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(userSelectedCandidateIdentity, "userSelectedCandidateIdentity");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!candidate.packageIdentity().equals(userSelectedCandidateIdentity)
                || candidate.artifactAvailability()
                        != FormalDeploymentArtifactAvailability.VERIFIED_DRY_RUN_AVAILABLE
                || !createdAt.isBefore(candidate.expiresAt())
                || candidate.riskAssessment().orElseThrow().approvalBlocked()) {
            throw new IllegalArgumentException("selected candidate lacks exact approvable review evidence");
        }
        DeploymentBudget budget = candidate.materialPowerMutationBudget().orElseThrow();
        ApprovalScope scope = new ApprovalScope(
                candidate.worldIdentity(), candidate.packageIdentity(), candidate.candidateZone().candidateId(),
                userSelectedCandidateIdentity, candidate.previewHash().orElseThrow(), candidate.target(),
                candidate.quantity(), candidate.backupIdentity().backupIdentity(),
                candidate.backupIdentity().sourceFingerprint(), candidate.worldSnapshotFingerprint(),
                candidate.runtimeFingerprint(), budget.maximumAffectedBlocks(), materialHash(budget),
                powerHash(budget), candidate.requiredOperations(), candidate.expiresAt(), true);
        List<String> missing = candidate.missingAuthorizations().stream()
                .filter(value -> !value.equals("USER_SELECTION")).distinct().sorted().toList();
        ApprovalEvidence evidence = new ApprovalEvidence(candidate.packageIdentity(), true, missing,
                createdAt, "explicit-user-selected-candidate-id");
        String identity = requestIdentity(candidate.packageIdentity(), "PENDING_USER_APPROVAL",
                createdAt, Optional.of(scope));
        return new FormalDeploymentApprovalRequest(identity, candidate, Optional.of(scope),
                FormalApprovalRequestStatus.PENDING_USER_APPROVAL, evidence, missing, createdAt,
                FormalDeploymentApprovalDecision.absentFormal(), false, false);
    }

    private static String requestIdentity(
            String candidateIdentity,
            String state,
            Instant createdAt,
            Optional<ApprovalScope> scope) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        update(digest, candidateIdentity);
        update(digest, state);
        update(digest, createdAt.toString());
        scope.ifPresent(value -> {
            update(digest, value.worldIdentity());
            update(digest, value.candidateZoneIdentity());
            update(digest, value.userSelectedCandidateIdentity());
            update(digest, value.previewHash());
            update(digest, value.target().toString());
            update(digest, Long.toString(value.quantity()));
            update(digest, value.backupIdentity());
            update(digest, value.sourceFingerprint());
            update(digest, value.worldSnapshotFingerprint());
            update(digest, value.runtimeFingerprint());
            update(digest, Long.toString(value.maximumAffectedBlocks()));
            update(digest, value.materialBudgetHash());
            update(digest, value.powerBudgetHash());
            value.allowedOperations().forEach(item -> update(digest, item.name()));
            update(digest, value.expiresAt().toString());
        });
        return "formal-approval-request:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String materialHash(DeploymentBudget budget) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        maps(digest, budget.rawMaterialRequirements());
        maps(digest, budget.intermediateProductRequirements());
        maps(digest, budget.constructionBlockRequirements());
        maps(digest, budget.logisticsComponentRequirements());
        maps(digest, budget.inputInventoryRequirements());
        maps(digest, budget.expectedOutput());
        update(digest, budget.resourceSourcePolicy().name());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String powerHash(DeploymentBudget budget) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        maps(digest, budget.powerComponentRequirements());
        update(digest, Long.toString(budget.rotationalStressDemand()));
        update(digest, Long.toString(budget.powerCapacityMargin()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void maps(MessageDigest digest, Map<ResourceId, Long> values) {
        values.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> update(digest, entry.getKey() + "=" + entry.getValue()));
    }

    private static void update(MessageDigest digest, String value) {
        FormalWorldBackupManifest.update(digest, value);
    }
}
