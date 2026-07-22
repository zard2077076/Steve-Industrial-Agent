package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Current exact scope that must equal the externally supplied approval token. */
public record HumanApprovalRequest(
        WorldEnvironmentType environmentClassification,
        String previewHash,
        String worldIdentity,
        String worldSnapshotFingerprint,
        String runtimeFingerprint,
        long reloadGeneration,
        DeploymentBoundingBox exactRegion,
        ResourceId exactTarget,
        long exactQuantity,
        int exactMutationBudget,
        DeploymentPolicy exactDeploymentPolicy) {
    public HumanApprovalRequest {
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        previewHash = HumanApprovalToken.hash(previewHash, "previewHash");
        worldIdentity = HumanApprovalToken.text(worldIdentity, "worldIdentity");
        worldSnapshotFingerprint = HumanApprovalToken.text(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        runtimeFingerprint = HumanApprovalToken.text(runtimeFingerprint, "runtimeFingerprint");
        if (reloadGeneration < 0) {
            throw new IllegalArgumentException("reloadGeneration cannot be negative");
        }
        Objects.requireNonNull(exactRegion, "exactRegion");
        Objects.requireNonNull(exactTarget, "exactTarget");
        HumanApprovalToken.quantity(exactQuantity, "exactQuantity");
        HumanApprovalToken.mutation(exactMutationBudget, "exactMutationBudget");
        Objects.requireNonNull(exactDeploymentPolicy, "exactDeploymentPolicy");
    }
}
