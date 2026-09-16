package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Exact connection edge including tier, length, capacity and wire collision envelope. */
public record ElectricalWireEdge(
        ResourceId edgeId,
        ResourceId fromNodeId,
        ResourceId toNodeId,
        VoltageTier voltageTier,
        int transferDistance,
        int maximumDistance,
        long capacityPerTick,
        List<BlockPos3i> collisionEnvelope,
        String connectionSha256,
        boolean endpointsConnected,
        boolean collisionFree,
        boolean serverObserved) {
    public static final int MAX_COLLISION_CELLS = 4_096;

    public ElectricalWireEdge {
        Objects.requireNonNull(edgeId, "edgeId");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(toNodeId, "toNodeId");
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("electrical edge cannot connect a node to itself");
        }
        Objects.requireNonNull(voltageTier, "voltageTier");
        if (transferDistance < 1 || maximumDistance < 1 || maximumDistance > 4_096
                || capacityPerTick < 1 || capacityPerTick > 1_000_000_000_000L) {
            throw new IllegalArgumentException("electrical edge distance or capacity is invalid");
        }
        collisionEnvelope = List.copyOf(Objects.requireNonNull(
                collisionEnvelope, "collisionEnvelope"));
        if (collisionEnvelope.size() > MAX_COLLISION_CELLS) {
            throw new IllegalArgumentException("wire collision envelope exceeds its bound");
        }
        Objects.requireNonNull(connectionSha256, "connectionSha256");
        if (!connectionSha256.matches("[0-9a-f]{64}") || !serverObserved) {
            throw new IllegalArgumentException("electrical edge observation is incomplete");
        }
    }
}
