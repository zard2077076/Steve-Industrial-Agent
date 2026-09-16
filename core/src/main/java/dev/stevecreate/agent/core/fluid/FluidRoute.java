package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

public record FluidRoute(
        ResourceId routeId,
        ResourceId fromNodeId,
        ResourceId toNodeId,
        FluidIdentity fluid,
        List<BlockPos3i> positions,
        long requiredMb,
        long capacityMb,
        boolean directional,
        boolean valveOpen,
        boolean collisionFree,
        boolean endpointsConnected,
        String routeSha256,
        boolean serverObserved) {
    public static final int MAX_ROUTE_LENGTH = 4_096;

    public FluidRoute {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(toNodeId, "toNodeId");
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("fluid route cannot loop to the same node");
        }
        Objects.requireNonNull(fluid, "fluid");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        if (positions.size() < 2 || positions.size() > MAX_ROUTE_LENGTH
                || requiredMb < 1 || capacityMb < 1 || capacityMb > 1_000_000_000_000L) {
            throw new IllegalArgumentException("fluid route bounds are invalid");
        }
        Objects.requireNonNull(routeSha256, "routeSha256");
        if (!routeSha256.matches("[0-9a-f]{64}") || !serverObserved) {
            throw new IllegalArgumentException("fluid route observation is incomplete");
        }
    }
}
