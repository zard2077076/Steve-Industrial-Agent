package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public record DeploymentBlockReplacement(
        BlockPos3i position,
        ResourceId existingBlockId,
        ResourceId replacementBlockId) {
    public DeploymentBlockReplacement {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(existingBlockId, "existingBlockId");
        Objects.requireNonNull(replacementBlockId, "replacementBlockId");
    }
}
