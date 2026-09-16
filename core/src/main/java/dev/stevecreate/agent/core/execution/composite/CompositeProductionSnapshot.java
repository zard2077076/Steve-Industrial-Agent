package dev.stevecreate.agent.core.execution.composite;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Exact loader-neutral recovery state; it contains no execution authority. */
public record CompositeProductionSnapshot(
        ResourceId graphId,
        String graphFingerprint,
        Map<ResourceId, CompositeProductionCoordinator.NodeStatus> nodeStatuses,
        Map<ResourceId, Long> bufferQuantities,
        Map<ResourceId, ResourceId> bufferResources,
        long generation) {
    public CompositeProductionSnapshot {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(graphFingerprint, "graphFingerprint");
        if (!graphFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Composite graph fingerprint must be lowercase SHA-256");
        }
        nodeStatuses = Map.copyOf(Objects.requireNonNull(nodeStatuses, "nodeStatuses"));
        bufferQuantities = Map.copyOf(Objects.requireNonNull(bufferQuantities, "bufferQuantities"));
        bufferResources = Map.copyOf(Objects.requireNonNull(bufferResources, "bufferResources"));
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
    }
}
