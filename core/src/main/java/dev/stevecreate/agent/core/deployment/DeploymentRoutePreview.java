package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Objects;

public record DeploymentRoutePreview(
        ResourceId id,
        GenericResourceType resourceType,
        long requiredAmount,
        long capacity,
        List<BlockPos3i> positions) {
    public DeploymentRoutePreview {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resourceType, "resourceType");
        if (requiredAmount < 1 || capacity < 1) throw new IllegalArgumentException("route amounts must be positive");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        if (positions.size() < 2) throw new IllegalArgumentException("route requires at least two positions");
    }
}
