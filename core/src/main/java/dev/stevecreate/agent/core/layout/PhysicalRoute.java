package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Objects;

public record PhysicalRoute(
        ResourceId id,
        ResourceId sourceLogicalPortId,
        ResourceId targetLogicalPortId,
        GenericResourceType resourceType,
        long requiredAmount,
        long capacity,
        List<BlockPos3i> positions) {
    public PhysicalRoute {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceLogicalPortId, "sourceLogicalPortId");
        Objects.requireNonNull(targetLogicalPortId, "targetLogicalPortId");
        Objects.requireNonNull(resourceType, "resourceType");
        if (requiredAmount < 1 || capacity < 1) throw new IllegalArgumentException("route amounts must be positive");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        if (positions.size() < 2) throw new IllegalArgumentException("route must contain endpoints");
    }

    /**
     * The cells a route actually builds, which is every cell but its two ends.
     *
     * <p>The ends are the ports of the machines being joined; they already exist and
     * belong to those machines. Only the span between them is new construction.
     *
     * <p>This lived as a loop inside the executor and nowhere else, so the layer that
     * prices a build had no way to know how many cells it would lay or what they cost —
     * and the player path refused any plan with routes at all rather than guess. One
     * definition, read by whoever needs it.</p>
     */
    public List<BlockPos3i> interiorPositions() {
        return positions.size() <= 2 ? List.of() : List.copyOf(
                positions.subList(1, positions.size() - 1));
    }
}
