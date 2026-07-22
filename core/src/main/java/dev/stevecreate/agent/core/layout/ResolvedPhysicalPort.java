package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;
import java.util.Optional;

public record ResolvedPhysicalPort(
        ResourceId logicalPortId,
        ResourceId implementationPortId,
        BlockPos3i position,
        Optional<Direction6> side,
        GenericResourceType resourceType,
        PortMode mode,
        long capacity) {
    public ResolvedPhysicalPort {
        Objects.requireNonNull(logicalPortId, "logicalPortId");
        Objects.requireNonNull(implementationPortId, "implementationPortId");
        Objects.requireNonNull(position, "position");
        side = Objects.requireNonNull(side, "side");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(mode, "mode");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    }
}
