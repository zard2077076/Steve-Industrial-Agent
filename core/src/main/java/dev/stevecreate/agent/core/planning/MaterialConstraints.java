package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Optional bounded raw-material restrictions for one production goal. */
public record MaterialConstraints(
        Set<ResourceId> forbiddenResources,
        Map<ResourceId, Long> maximumConsumption) {
    public static final int MAX_CONSTRAINTS = 256;
    public static final long MAX_RESOURCE_QUANTITY = 1_000_000_000_000L;

    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public MaterialConstraints {
        Objects.requireNonNull(forbiddenResources, "forbiddenResources");
        Objects.requireNonNull(maximumConsumption, "maximumConsumption");
        if (forbiddenResources.size() > MAX_CONSTRAINTS
                || maximumConsumption.size() > MAX_CONSTRAINTS
                || forbiddenResources.size() + maximumConsumption.size() > MAX_CONSTRAINTS) {
            throw new IllegalArgumentException("Material constraints exceed the bounded count");
        }

        TreeSet<ResourceId> forbidden = new TreeSet<>(ID_ORDER);
        for (ResourceId resource : forbiddenResources) {
            forbidden.add(Objects.requireNonNull(resource, "forbiddenResources element"));
        }
        TreeMap<ResourceId, Long> maximum = new TreeMap<>(ID_ORDER);
        for (Map.Entry<ResourceId, Long> entry : maximumConsumption.entrySet()) {
            ResourceId resource = Objects.requireNonNull(entry.getKey(), "maximumConsumption key");
            long quantity = Objects.requireNonNull(entry.getValue(), "maximumConsumption value");
            if (quantity <= 0 || quantity > MAX_RESOURCE_QUANTITY) {
                throw new IllegalArgumentException(
                        "Material maximum must be positive and bounded for " + resource);
            }
            maximum.put(resource, quantity);
        }
        Set<ResourceId> overlap = new LinkedHashSet<>(forbidden);
        overlap.retainAll(maximum.keySet());
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resource cannot be both forbidden and quantity-limited: " + overlap);
        }
        forbiddenResources = Collections.unmodifiableSet(new LinkedHashSet<>(forbidden));
        maximumConsumption = Collections.unmodifiableMap(new LinkedHashMap<>(maximum));
    }

    public static MaterialConstraints none() {
        return new MaterialConstraints(Set.of(), Map.of());
    }
}
