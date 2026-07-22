package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A loader-neutral logical process or resource-boundary node.
 *
 * <p>It intentionally has no implementation identity, position, orientation, or world state.</p>
 */
public record LogicalGraphNode(
        ResourceId id,
        LogicalNodeKind kind,
        Optional<ResourceId> stepId,
        Set<ResourceId> requiredCapabilities,
        Set<CapabilityResourceRequirement> requiredResources) {
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public LogicalGraphNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        stepId = Objects.requireNonNull(stepId, "stepId");
        requiredCapabilities = copyCapabilities(requiredCapabilities);
        requiredResources = copyRequirements(requiredResources);

        if (kind == LogicalNodeKind.PROCESS) {
            if (stepId.isEmpty() || requiredCapabilities.isEmpty()) {
                throw new IllegalArgumentException(
                        "Process nodes require a step id and at least one capability");
            }
        } else if (stepId.isPresent()
                || !requiredCapabilities.isEmpty()
                || !requiredResources.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource-boundary nodes cannot declare process requirements");
        }
    }

    private static Set<ResourceId> copyCapabilities(Set<ResourceId> values) {
        Objects.requireNonNull(values, "requiredCapabilities");
        if (values.size() > MachineCapability.MAX_IDS_PER_FIELD) {
            throw new IllegalArgumentException("requiredCapabilities exceeds its bound");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(ID_ORDER);
        for (ResourceId value : values) {
            sorted.add(Objects.requireNonNull(value, "requiredCapabilities element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<CapabilityResourceRequirement> copyRequirements(
            Set<CapabilityResourceRequirement> values) {
        Objects.requireNonNull(values, "requiredResources");
        if (values.size() > GenericResourceType.values().length) {
            throw new IllegalArgumentException("requiredResources exceeds its bound");
        }
        List<CapabilityResourceRequirement> sorted = new ArrayList<>(values.size());
        Set<GenericResourceType> types = new HashSet<>();
        for (CapabilityResourceRequirement value : values) {
            CapabilityResourceRequirement requirement = Objects.requireNonNull(
                    value, "requiredResources element");
            if (!types.add(requirement.resourceType())) {
                throw new IllegalArgumentException(
                        "Duplicate required resource type: " + requirement.resourceType());
            }
            sorted.add(requirement);
        }
        sorted.sort(Comparator.comparing(value -> value.resourceType().ordinal()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
