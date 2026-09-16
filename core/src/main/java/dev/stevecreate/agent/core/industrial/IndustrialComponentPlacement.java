package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** One orientation-resolved component in an industrial production plan. */
public record IndustrialComponentPlacement(
        ResourceId roleId,
        ResourceId blockId,
        BlockPos3i position,
        Map<String, String> requiredBlockState,
        boolean replaceableByTag) {
    public IndustrialComponentPlacement {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        requiredBlockState = Map.copyOf(Objects.requireNonNull(
                requiredBlockState, "requiredBlockState"));
    }
}
