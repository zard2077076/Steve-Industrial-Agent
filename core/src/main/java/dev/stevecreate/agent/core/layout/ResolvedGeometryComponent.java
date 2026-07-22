package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

public record ResolvedGeometryComponent(
        ResourceId roleId,
        ResourceId blockId,
        BlockPos3i position,
        Map<String, String> blockState) {
    public ResolvedGeometryComponent {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        blockState = Map.copyOf(Objects.requireNonNull(blockState, "blockState"));
    }
}
