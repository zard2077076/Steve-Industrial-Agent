package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** One descriptor-derived construction component, not a runnable placement command. */
public record GeometryComponent(
        ResourceId roleId,
        ResourceId blockId,
        BlockPos3i relativePosition,
        Map<String, String> blockState) {
    public GeometryComponent {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(relativePosition, "relativePosition");
        blockState = Map.copyOf(Objects.requireNonNull(blockState, "blockState"));
    }
}
