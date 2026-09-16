package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/**
 * Loader-neutral C-10 plan for one allowlisted application to a session-owned world workpiece.
 *
 * <p>The geometry is deliberately fixed: a downward Deployer sits two blocks above the
 * workpiece and is driven from its south face. The executor may mutate only these machine
 * positions and the exact workpiece position carried by the policy.</p>
 */
public record OwnedWorkpieceApplicationPlan(
        OwnedWorkpieceApplicationPolicy policy,
        BlockPos3i resourceBufferPosition,
        BlockPos3i drivePosition,
        BlockPos3i deployerPosition) {

    public OwnedWorkpieceApplicationPlan {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(resourceBufferPosition, "resourceBufferPosition");
        Objects.requireNonNull(drivePosition, "drivePosition");
        Objects.requireNonNull(deployerPosition, "deployerPosition");
        BlockPos3i expectedDeployer = policy.workpiecePosition().translate(0, 2, 0);
        BlockPos3i expectedDrive = expectedDeployer.translate(0, 0, 1);
        if (!deployerPosition.equals(expectedDeployer)
                || !drivePosition.equals(expectedDrive)) {
            throw new IllegalArgumentException(
                    "C-10 owned-workpiece geometry must use the fixed downward Deployer");
        }
        if (!policy.contains(deployerPosition)
                || !policy.contains(drivePosition)
                || !policy.contains(resourceBufferPosition)) {
            throw new IllegalArgumentException(
                    "C-10 owned-workpiece machine or resource buffer is outside its verified region");
        }
        if (resourceBufferPosition.equals(policy.workpiecePosition())
                || resourceBufferPosition.equals(deployerPosition)
                || resourceBufferPosition.equals(drivePosition)) {
            throw new IllegalArgumentException(
                    "C-10 owned resource buffer overlaps the mutation boundary");
        }
    }

    public static OwnedWorkpieceApplicationPlan andesiteCasing(
            ResourceId verifiedPhysicalPlanId,
            ResourceId sessionId,
            BlockPos3i workpiecePosition,
            BlockPos3i resourceBufferPosition,
            BlockPos3i regionMinimum,
            BlockPos3i regionMaximum) {
        OwnedWorkpieceApplicationPolicy policy =
                OwnedWorkpieceApplicationPolicy.andesiteCasing(
                        verifiedPhysicalPlanId,
                        sessionId,
                        workpiecePosition,
                        regionMinimum,
                        regionMaximum);
        BlockPos3i deployer = workpiecePosition.translate(0, 2, 0);
        return new OwnedWorkpieceApplicationPlan(
                policy, resourceBufferPosition, deployer.translate(0, 0, 1), deployer);
    }

    public List<BlockPos3i> ownedMutationPositions() {
        return List.of(drivePosition, deployerPosition, policy.workpiecePosition());
    }
}
