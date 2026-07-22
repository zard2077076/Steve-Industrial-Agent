package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PhysicalMachinePlacement(
        ResourceId logicalNodeId,
        ResourceId implementationId,
        BlockPos3i anchor,
        QuarterTurn orientation,
        List<ResolvedGeometryComponent> components,
        Map<ResourceId, ResolvedPhysicalPort> ports,
        List<BlockPos3i> clearance,
        List<BlockPos3i> rotationalPowerRoute,
        long stressImpact,
        String topologyContract) {
    public PhysicalMachinePlacement {
        Objects.requireNonNull(logicalNodeId, "logicalNodeId");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(orientation, "orientation");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        ports = Map.copyOf(Objects.requireNonNull(ports, "ports"));
        clearance = List.copyOf(Objects.requireNonNull(clearance, "clearance"));
        rotationalPowerRoute = List.copyOf(Objects.requireNonNull(rotationalPowerRoute, "rotationalPowerRoute"));
        if (components.isEmpty() || ports.isEmpty() || rotationalPowerRoute.size() < 2 || stressImpact < 1) {
            throw new IllegalArgumentException("physical machine placement is incomplete");
        }
        Objects.requireNonNull(topologyContract, "topologyContract");
    }
}
