package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable read-only evidence bundle. It contains no world, inventory or session service. */
public record DeploymentReadinessContext(
        ExecutionReadyPlan executionReadyPlan,
        ResourceId expectedPhysicalPlanId,
        ResourceId expectedUnifiedGraphId,
        WorldEnvironmentDescriptor environment,
        Optional<DeploymentPolicy> deploymentPolicy,
        DeploymentPreview preview,
        DryRunCompletion dryRunCompletion,
        Instant snapshotObservedAt,
        Duration maximumSnapshotAge,
        String currentWorldSnapshotFingerprint,
        String currentRuntimeFingerprint,
        RegionAuthorization regionAuthorization,
        RegionAuthorizationRequest regionRequest,
        Map<RegionAuthorizedOperation, PermissionEvidence> operationPermissions,
        DeploymentRiskAssessment riskAssessment,
        Set<DeploymentRiskCategory> policyHandledHighRisks,
        DeploymentBudget deploymentBudget,
        Optional<BackupPlan> backupPlan,
        Optional<BackupVerification> backupVerification,
        HumanApprovalToken humanApproval,
        HumanApprovalRequest humanApprovalRequest,
        Instant evaluatedAt) {
    public DeploymentReadinessContext {
        Objects.requireNonNull(executionReadyPlan, "executionReadyPlan");
        Objects.requireNonNull(expectedPhysicalPlanId, "expectedPhysicalPlanId");
        Objects.requireNonNull(expectedUnifiedGraphId, "expectedUnifiedGraphId");
        Objects.requireNonNull(environment, "environment");
        deploymentPolicy = Objects.requireNonNull(deploymentPolicy, "deploymentPolicy");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(dryRunCompletion, "dryRunCompletion");
        Objects.requireNonNull(snapshotObservedAt, "snapshotObservedAt");
        Objects.requireNonNull(maximumSnapshotAge, "maximumSnapshotAge");
        if (maximumSnapshotAge.isNegative() || maximumSnapshotAge.isZero()
                || maximumSnapshotAge.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("maximumSnapshotAge is outside its bound");
        }
        currentWorldSnapshotFingerprint = text(
                currentWorldSnapshotFingerprint, "currentWorldSnapshotFingerprint");
        currentRuntimeFingerprint = text(currentRuntimeFingerprint, "currentRuntimeFingerprint");
        Objects.requireNonNull(regionAuthorization, "regionAuthorization");
        Objects.requireNonNull(regionRequest, "regionRequest");
        operationPermissions = Map.copyOf(Objects.requireNonNull(
                operationPermissions, "operationPermissions"));
        Objects.requireNonNull(riskAssessment, "riskAssessment");
        policyHandledHighRisks = Set.copyOf(Objects.requireNonNull(
                policyHandledHighRisks, "policyHandledHighRisks"));
        Objects.requireNonNull(deploymentBudget, "deploymentBudget");
        backupPlan = Objects.requireNonNull(backupPlan, "backupPlan");
        backupVerification = Objects.requireNonNull(backupVerification, "backupVerification");
        Objects.requireNonNull(humanApproval, "humanApproval");
        Objects.requireNonNull(humanApprovalRequest, "humanApprovalRequest");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
