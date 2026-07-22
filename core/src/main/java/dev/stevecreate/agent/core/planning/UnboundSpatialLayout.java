package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit declaration that candidate nodes have not received any spatial binding. */
public record UnboundSpatialLayout(List<ResourceId> nodeIds) {
    public UnboundSpatialLayout {
        Objects.requireNonNull(nodeIds, "nodeIds");
        if (nodeIds.size() > ProcessDependencyGraph.MAX_STEPS) {
            throw new IllegalArgumentException("nodeIds exceeds the candidate bound");
        }
        nodeIds = List.copyOf(nodeIds);
        Set<ResourceId> unique = new HashSet<>();
        for (ResourceId value : nodeIds) {
            if (!unique.add(Objects.requireNonNull(value, "nodeIds element"))) {
                throw new IllegalArgumentException("Duplicate unbound node ID: " + value);
            }
        }
    }

    public boolean isBound() {
        return false;
    }
}
