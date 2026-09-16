package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Server-verified bounded physical logistics path between two bound endpoints. */
public record WarehouseLogisticsEdge(
        ResourceId edgeId,
        ResourceId fromEndpointId,
        ResourceId toEndpointId,
        Set<GenericResourceType> supportedResourceTypes,
        List<BlockPos3i> path,
        long capacityPerTick,
        String pathSha256,
        long generation,
        boolean collisionFree,
        boolean serverVerified) {
    public static final int MAX_PATH_LENGTH = 4_096;

    public WarehouseLogisticsEdge {
        Objects.requireNonNull(edgeId, "edgeId");
        Objects.requireNonNull(fromEndpointId, "fromEndpointId");
        Objects.requireNonNull(toEndpointId, "toEndpointId");
        if (fromEndpointId.equals(toEndpointId)) {
            throw new IllegalArgumentException("warehouse edge cannot loop to the same endpoint");
        }
        supportedResourceTypes = Set.copyOf(Objects.requireNonNull(
                supportedResourceTypes, "supportedResourceTypes"));
        if (supportedResourceTypes.isEmpty()) {
            throw new IllegalArgumentException("warehouse edge has no supported resource type");
        }
        path = List.copyOf(Objects.requireNonNull(path, "path"));
        if (path.size() < 2 || path.size() > MAX_PATH_LENGTH || capacityPerTick < 1
                || capacityPerTick > 1_000_000_000_000L) {
            throw new IllegalArgumentException("warehouse edge path or capacity is invalid");
        }
        Objects.requireNonNull(pathSha256, "pathSha256");
        if (!pathSha256.matches("[0-9a-f]{64}") || generation < 0
                || !collisionFree || !serverVerified) {
            throw new IllegalArgumentException("warehouse edge verification is incomplete");
        }
    }
}
