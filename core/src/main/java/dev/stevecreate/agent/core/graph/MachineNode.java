package dev.stevecreate.agent.core.graph;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One physical machine, transport component, boundary, or supporting structure role. */
public record MachineNode(
        ResourceId id,
        ResourceId roleId,
        ResourceId implementationId,
        BlockPos3i relativePosition,
        MachineOrientation orientation,
        Set<ResourceId> requiredCapabilities,
        Map<String, String> configuration) {
    public MachineNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(relativePosition, "relativePosition");
        Objects.requireNonNull(orientation, "orientation");
        requiredCapabilities = GraphModelValues.copyIds(requiredCapabilities, "requiredCapabilities");
        configuration = GraphModelValues.copyTextMap(configuration, "configuration");
    }

    public String modNamespace() {
        return implementationId.namespace();
    }
}
