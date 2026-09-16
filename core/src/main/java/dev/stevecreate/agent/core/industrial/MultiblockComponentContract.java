package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** One exact relative component in an adapter-owned multiblock definition. */
public record MultiblockComponentContract(
        ResourceId roleId,
        ResourceId blockId,
        BlockPos3i relativePosition,
        Map<String, String> requiredBlockState,
        boolean replaceableByTag) {
    public MultiblockComponentContract {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(relativePosition, "relativePosition");
        requiredBlockState = Map.copyOf(Objects.requireNonNull(
                requiredBlockState, "requiredBlockState"));
    }
}
