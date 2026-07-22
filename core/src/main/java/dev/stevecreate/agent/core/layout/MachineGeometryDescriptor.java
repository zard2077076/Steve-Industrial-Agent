package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure geometry and physical-port contract for one versioned implementation. */
public record MachineGeometryDescriptor(
        ResourceId implementationId,
        MachineFootprint footprint,
        ClearanceVolume clearance,
        Set<QuarterTurn> supportedOrientations,
        List<GeometryComponent> components,
        List<PhysicalPortRule> portRules,
        List<BlockPos3i> rotationalPowerRoute,
        long rotationalPowerCapacity,
        long stressImpact,
        String topologyContract) {
    public MachineGeometryDescriptor {
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(clearance, "clearance");
        supportedOrientations = Set.copyOf(Objects.requireNonNull(
                supportedOrientations, "supportedOrientations"));
        if (supportedOrientations.isEmpty()) throw new IllegalArgumentException("orientations are empty");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        portRules = List.copyOf(Objects.requireNonNull(portRules, "portRules"));
        rotationalPowerRoute = List.copyOf(Objects.requireNonNull(
                rotationalPowerRoute, "rotationalPowerRoute"));
        if (components.isEmpty() || portRules.isEmpty() || rotationalPowerRoute.size() < 2
                || rotationalPowerCapacity < 1 || stressImpact < 1) {
            throw new IllegalArgumentException("geometry contract is incomplete");
        }
        Set<BlockPos3i> componentPositions = new HashSet<>();
        for (GeometryComponent component : components) {
            Objects.requireNonNull(component, "component");
            if (!componentPositions.add(component.relativePosition())
                    || !footprint.cells().contains(component.relativePosition())) {
                throw new IllegalArgumentException("components must uniquely occupy footprint cells");
            }
        }
        if (!componentPositions.equals(footprint.cells())) {
            throw new IllegalArgumentException("footprint must equal component positions");
        }
        Set<ResourceId> portIds = new HashSet<>();
        for (PhysicalPortRule rule : portRules) {
            if (!portIds.add(rule.implementationPortId())) {
                throw new IllegalArgumentException("duplicate physical port rule " + rule.implementationPortId());
            }
        }
        Objects.requireNonNull(topologyContract, "topologyContract");
        if (topologyContract.isBlank()) throw new IllegalArgumentException("topologyContract is blank");
    }

    public PhysicalPortRule port(ResourceId implementationPortId) {
        return portRules.stream().filter(value -> value.implementationPortId().equals(implementationPortId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unknown implementation port geometry: " + implementationPortId));
    }
}
