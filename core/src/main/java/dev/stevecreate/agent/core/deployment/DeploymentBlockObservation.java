package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Adapter-supplied bounded read-only detail; it has no world handle. */
public record DeploymentBlockObservation(
        BlockPos3i position,
        ResourceId blockId,
        boolean protectedBlock,
        boolean blockEntity,
        boolean containerHasContents) {
    public DeploymentBlockObservation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        if (containerHasContents && !blockEntity) {
            throw new IllegalArgumentException("container contents require a block entity observation");
        }
    }
}
