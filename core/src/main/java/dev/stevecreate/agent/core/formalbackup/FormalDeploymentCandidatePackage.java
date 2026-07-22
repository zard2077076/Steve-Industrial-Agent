package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.deployment.DeploymentBudget;
import dev.stevecreate.agent.core.deployment.DeploymentRiskAssessment;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.RollbackPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.CandidateIndustrialZone;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Review-only formal candidate. Missing real preview evidence remains explicit, never fabricated. */
public record FormalDeploymentCandidatePackage(
        String packageIdentity,
        String worldIdentity,
        CandidateIndustrialZone candidateZone,
        DeploymentBoundingBox exactBoundingBox,
        ResourceId target,
        long quantity,
        Optional<String> previewHash,
        Optional<ResourceId> verifiedPhysicalPlanIdentity,
        String runtimeFingerprint,
        String worldSnapshotFingerprint,
        FormalWorldBackupIdentity backupIdentity,
        Optional<DeploymentRiskAssessment> riskAssessment,
        Optional<DeploymentBudget> materialPowerMutationBudget,
        List<String> protectedAndUnknownFindings,
        PermissionDecision claimPermission,
        List<RegionAuthorizedOperation> requiredOperations,
        List<String> missingAuthorizations,
        RollbackPolicy rollbackPolicy,
        Instant expiresAt,
        String humanSummary,
        FormalDeploymentArtifactAvailability artifactAvailability,
        FormalDeploymentCandidateStatus status,
        boolean automaticallySelected,
        boolean approvalCreated,
        boolean executionAllowed) {
    public FormalDeploymentCandidatePackage {
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        if (!packageIdentity.matches("formal-candidate:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("packageIdentity is invalid");
        }
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(candidateZone, "candidateZone");
        Objects.requireNonNull(exactBoundingBox, "exactBoundingBox");
        Objects.requireNonNull(target, "target");
        previewHash = Objects.requireNonNull(previewHash, "previewHash");
        verifiedPhysicalPlanIdentity = Objects.requireNonNull(
                verifiedPhysicalPlanIdentity, "verifiedPhysicalPlanIdentity");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(worldSnapshotFingerprint, "worldSnapshotFingerprint");
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        riskAssessment = Objects.requireNonNull(riskAssessment, "riskAssessment");
        materialPowerMutationBudget = Objects.requireNonNull(
                materialPowerMutationBudget, "materialPowerMutationBudget");
        protectedAndUnknownFindings = List.copyOf(
                Objects.requireNonNull(protectedAndUnknownFindings, "protectedAndUnknownFindings"));
        Objects.requireNonNull(claimPermission, "claimPermission");
        requiredOperations = List.copyOf(Objects.requireNonNull(requiredOperations, "requiredOperations"));
        missingAuthorizations = List.copyOf(Objects.requireNonNull(missingAuthorizations, "missingAuthorizations"));
        Objects.requireNonNull(rollbackPolicy, "rollbackPolicy");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(humanSummary, "humanSummary");
        Objects.requireNonNull(artifactAvailability, "artifactAvailability");
        Objects.requireNonNull(status, "status");
        boolean artifactsPresent = previewHash.isPresent() && verifiedPhysicalPlanIdentity.isPresent()
                && riskAssessment.isPresent() && materialPowerMutationBudget.isPresent();
        boolean artifactsAbsent = previewHash.isEmpty() && verifiedPhysicalPlanIdentity.isEmpty()
                && riskAssessment.isEmpty() && materialPowerMutationBudget.isEmpty();
        if (!worldIdentity.equals(backupIdentity.worldIdentity().value())
                || !exactBoundingBox.equals(candidateZone.boundingBox())
                || quantity < 1 || protectedAndUnknownFindings.size() > 4_096
                || requiredOperations.isEmpty() || missingAuthorizations.isEmpty()
                || automaticallySelected || approvalCreated || executionAllowed
                || status != FormalDeploymentCandidateStatus.PENDING_USER_SELECTION
                || claimPermission != PermissionDecision.UNKNOWN) {
            throw new IllegalArgumentException("formal candidate package violates its pending-only scope");
        }
        if (artifactAvailability == FormalDeploymentArtifactAvailability.VERIFIED_DRY_RUN_AVAILABLE
                && !artifactsPresent) {
            throw new IllegalArgumentException("available dry-run evidence is incomplete");
        }
        if (artifactAvailability == FormalDeploymentArtifactAvailability.BLOCKED_PREVIEW_UNAVAILABLE
                && (!artifactsAbsent
                || !missingAuthorizations.contains("FORMAL_PREVIEW_UNAVAILABLE")
                || !missingAuthorizations.contains("VERIFIED_PHYSICAL_PLAN_UNAVAILABLE"))) {
            throw new IllegalArgumentException("blocked preview evidence is not explicit");
        }
    }
}
