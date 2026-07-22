package dev.stevecreate.agent.core.graph;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** A resource-compatible connection between two graph ports. */
public record MachineEdge(
        ResourceId id,
        ResourceId sourcePortId,
        ResourceId targetPortId,
        GenericResourceType resourceType,
        EdgeMode mode,
        OptionalLong maximumThroughput,
        Map<String, String> connectionRequirements) {
    public MachineEdge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourcePortId, "sourcePortId");
        Objects.requireNonNull(targetPortId, "targetPortId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(mode, "mode");
        maximumThroughput = GraphModelValues.requirePositiveIfPresent(
                maximumThroughput, "maximumThroughput");
        connectionRequirements = GraphModelValues.copyTextMap(
                connectionRequirements, "connectionRequirements");
        if (sourcePortId.equals(targetPortId)) {
            throw new IllegalArgumentException("Machine edge cannot connect a port to itself: " + sourcePortId);
        }
    }
}
