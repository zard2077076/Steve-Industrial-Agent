package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record DeploymentBlockPlacement(
        BlockPos3i position,
        ResourceId blockId,
        Map<String, String> blockState) {
    public DeploymentBlockPlacement {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        blockState = Map.copyOf(new TreeMap<>(Objects.requireNonNull(blockState, "blockState")));
    }
}
