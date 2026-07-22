package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/** One directed logical resource flow with explicit provenance. */
public record LogicalResourceEdge(
        ResourceId id,
        ResourceId sourcePortId,
        ResourceId targetPortId,
        ResourceId resourceId,
        GenericResourceType resourceType,
        long amount,
        LogicalEdgeKind kind) {
    public LogicalResourceEdge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourcePortId, "sourcePortId");
        Objects.requireNonNull(targetPortId, "targetPortId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(kind, "kind");
        if (sourcePortId.equals(targetPortId)) {
            throw new IllegalArgumentException("Logical edge endpoints must be distinct");
        }
        if (amount < 1 || amount > ProcessResource.MAX_AMOUNT) {
            throw new IllegalArgumentException(
                    "amount must be between 1 and " + ProcessResource.MAX_AMOUNT);
        }
    }
}
