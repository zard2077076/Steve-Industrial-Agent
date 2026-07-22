package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/** A typed quantity requirement at a logical node, before any physical port is selected. */
public record LogicalPortRequirement(
        ResourceId id,
        ResourceId nodeId,
        ResourceId resourceId,
        GenericResourceType resourceType,
        PortMode mode,
        LogicalPortRole role,
        long amount) {
    public LogicalPortRequirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(role, "role");
        if (amount < 1 || amount > ProcessResource.MAX_AMOUNT) {
            throw new IllegalArgumentException(
                    "amount must be between 1 and " + ProcessResource.MAX_AMOUNT);
        }
        PortMode requiredMode = switch (role) {
            case PROCESS_INPUT, TARGET_DEMAND -> PortMode.INPUT;
            case PROCESS_OUTPUT, EXTERNAL_SUPPLY -> PortMode.OUTPUT;
        };
        if (mode != requiredMode) {
            throw new IllegalArgumentException(
                    "Logical port role " + role + " requires mode " + requiredMode);
        }
    }
}
