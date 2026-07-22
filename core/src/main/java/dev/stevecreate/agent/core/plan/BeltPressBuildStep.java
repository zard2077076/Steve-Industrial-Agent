package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One validated, bounded C-04 construction step. */
public sealed interface BeltPressBuildStep
        permits BeltPressBuildStep.PlaceBlock, BeltPressBuildStep.ConnectBelt {
    int order();

    BeltPressBuildRole role();

    ResourceId materialId();

    record PlaceBlock(
            int order,
            BeltPressBuildRole role,
            ResourceId materialId,
            BlockPos3i position,
            PlanBlockAxis rotationAxis,
            PlanBlockFacing facing) implements BeltPressBuildStep {

        public PlaceBlock {
            requireOrder(order);
            Objects.requireNonNull(role, "role");
            if (role == BeltPressBuildRole.CONNECT_BELT) {
                throw new IllegalArgumentException("CONNECT_BELT requires a typed belt connection step");
            }
            Objects.requireNonNull(materialId, "materialId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotationAxis, "rotationAxis");
            Objects.requireNonNull(facing, "facing");
        }
    }

    record ConnectBelt(
            int order,
            ResourceId materialId,
            BlockPos3i startPosition,
            BlockPos3i endPosition,
            int expectedSegments) implements BeltPressBuildStep {

        public ConnectBelt {
            requireOrder(order);
            Objects.requireNonNull(materialId, "materialId");
            Objects.requireNonNull(startPosition, "startPosition");
            Objects.requireNonNull(endPosition, "endPosition");
            if (startPosition.equals(endPosition) || expectedSegments < 2 || expectedSegments > 16) {
                throw new IllegalArgumentException("Belt connection must contain between 2 and 16 segments");
            }
        }

        @Override
        public BeltPressBuildRole role() {
            return BeltPressBuildRole.CONNECT_BELT;
        }
    }

    private static void requireOrder(int order) {
        if (order < 1) {
            throw new IllegalArgumentException("Build-step order must be positive");
        }
    }
}
