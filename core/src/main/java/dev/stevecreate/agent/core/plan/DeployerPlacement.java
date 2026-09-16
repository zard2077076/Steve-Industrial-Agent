package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public record DeployerPlacement(
        int order,
        DeployerRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis rotationAxis,
        PlanBlockFacing facing) {
    public DeployerPlacement {
        if (order < 1) {
            throw new IllegalArgumentException("order must be positive");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationAxis, "rotationAxis");
        Objects.requireNonNull(facing, "facing");
    }
}
