package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One immutable C-06 placement with an explicit dangerous-medium bit. */
public record FanProcessingPlacement(
        int order,
        FanProcessingRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis rotationAxis,
        PlanBlockFacing facing,
        boolean dangerousToBots) {

    public FanProcessingPlacement {
        if (order < 1) throw new IllegalArgumentException("C-06 placement order must be positive");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationAxis, "rotationAxis");
        Objects.requireNonNull(facing, "facing");
        if (role.isKinetic() != (rotationAxis != PlanBlockAxis.NONE)) {
            throw new IllegalArgumentException("C-06 kinetic role and axis disagree");
        }
        if (role == FanProcessingRole.ENCASED_FAN
                && facing == PlanBlockFacing.NONE) {
            throw new IllegalArgumentException("C-06 fan requires a typed airflow direction");
        }
        if (role != FanProcessingRole.PROCESSING_MEDIUM && dangerousToBots) {
            throw new IllegalArgumentException(
                    "Only the C-06 processing medium may be dangerous to Bots");
        }
    }
}
