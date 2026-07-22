package dev.stevecreate.agent.core.graph;

import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** A typed input, output, or bidirectional resource boundary owned by one node. */
public record MachinePort(
        ResourceId id,
        ResourceId nodeId,
        GenericResourceType resourceType,
        PortMode mode,
        Optional<Direction6> side,
        OptionalLong capacity,
        Map<String, String> constraints) {
    public MachinePort {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(mode, "mode");
        side = Objects.requireNonNull(side, "side");
        capacity = GraphModelValues.requirePositiveIfPresent(capacity, "capacity");
        constraints = GraphModelValues.copyTextMap(constraints, "constraints");
    }
}
