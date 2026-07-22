package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Logical processing node that intentionally has no position, orientation or implementation. */
public record UnboundMachineNode(
        ResourceId nodeId,
        ResourceId stepId,
        Set<ResourceId> requiredCapabilities) {
    public UnboundMachineNode {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        if (requiredCapabilities.isEmpty()
                || requiredCapabilities.size() > MachineCapability.MAX_IDS_PER_FIELD) {
            throw new IllegalArgumentException("requiredCapabilities count violates node bounds");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ResourceId value : requiredCapabilities) {
            sorted.add(Objects.requireNonNull(value, "requiredCapabilities element"));
        }
        requiredCapabilities = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
