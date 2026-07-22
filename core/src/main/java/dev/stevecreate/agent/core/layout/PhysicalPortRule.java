package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;
import java.util.Optional;

/** Orientation-neutral port rule resolved by rotating its position and side. */
public record PhysicalPortRule(
        ResourceId implementationPortId,
        BlockPos3i relativePosition,
        Optional<Direction6> side,
        GenericResourceType resourceType,
        PortMode mode,
        long capacity) {
    public PhysicalPortRule {
        Objects.requireNonNull(implementationPortId, "implementationPortId");
        Objects.requireNonNull(relativePosition, "relativePosition");
        side = Objects.requireNonNull(side, "side");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(mode, "mode");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    }
}
