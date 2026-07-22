package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Immutable loader-neutral request for a deterministically planned item quantity. */
public record ProductionGoal(
        ResourceId target,
        GenericResourceType targetResourceType,
        long quantity,
        Set<String> allowedModIds,
        Set<String> forbiddenModIds,
        Optional<Integer> maximumProcessingDepth,
        MaterialConstraints materialConstraints,
        List<PlanningStrategyPreference> strategyPreferences,
        Map<ResourceId, Long> ownedResources) {
    public static final long MAX_TARGET_QUANTITY = 1_000_000_000_000L;
    public static final int MAX_MOD_CONSTRAINTS = 64;
    public static final int MAX_PROCESSING_DEPTH = 64;
    public static final int MAX_OWNED_RESOURCES = 256;

    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public ProductionGoal {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetResourceType, "targetResourceType");
        if (targetResourceType != GenericResourceType.ITEM) {
            throw new IllegalArgumentException(
                    "Goal planning currently supports only ITEM targets, not " + targetResourceType);
        }
        if (quantity <= 0 || quantity > MAX_TARGET_QUANTITY) {
            throw new IllegalArgumentException("Target quantity must be positive and bounded");
        }
        allowedModIds = copyModIds(allowedModIds, "allowedModIds");
        forbiddenModIds = copyModIds(forbiddenModIds, "forbiddenModIds");
        Set<String> overlap = new HashSet<>(allowedModIds);
        overlap.retainAll(forbiddenModIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Allowed and forbidden mod constraints conflict: " + overlap);
        }

        maximumProcessingDepth = Objects.requireNonNull(
                maximumProcessingDepth, "maximumProcessingDepth");
        maximumProcessingDepth.ifPresent(depth -> {
            if (depth <= 0 || depth > MAX_PROCESSING_DEPTH) {
                throw new IllegalArgumentException(
                        "Maximum processing depth must be between 1 and " + MAX_PROCESSING_DEPTH);
            }
        });
        materialConstraints = Objects.requireNonNull(materialConstraints, "materialConstraints");

        Objects.requireNonNull(strategyPreferences, "strategyPreferences");
        if (strategyPreferences.size() > PlanningStrategyPreference.values().length) {
            throw new IllegalArgumentException("Too many planning strategy preferences");
        }
        List<PlanningStrategyPreference> preferenceCopy = new ArrayList<>(strategyPreferences.size());
        Set<PlanningStrategyPreference> uniquePreferences = new HashSet<>();
        for (PlanningStrategyPreference preference : strategyPreferences) {
            PlanningStrategyPreference value = Objects.requireNonNull(
                    preference, "strategyPreferences element");
            if (!uniquePreferences.add(value)) {
                throw new IllegalArgumentException("Duplicate planning strategy preference: " + value);
            }
            preferenceCopy.add(value);
        }
        strategyPreferences = List.copyOf(preferenceCopy);
        ownedResources = copyResourceQuantities(ownedResources);
    }

    private static Set<String> copyModIds(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_MOD_CONSTRAINTS) {
            throw new IllegalArgumentException(name + " exceeds the bounded count");
        }
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            String modId = Objects.requireNonNull(value, name + " element");
            if (!MOD_ID.matcher(modId).matches()) {
                throw new IllegalArgumentException("Invalid mod ID: " + modId);
            }
            copy.add(modId);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(copy));
    }

    private static Map<ResourceId, Long> copyResourceQuantities(Map<ResourceId, Long> values) {
        Objects.requireNonNull(values, "ownedResources");
        if (values.size() > MAX_OWNED_RESOURCES) {
            throw new IllegalArgumentException("Owned resources exceed the bounded count");
        }
        TreeMap<ResourceId, Long> copy = new TreeMap<>(ID_ORDER);
        for (Map.Entry<ResourceId, Long> entry : values.entrySet()) {
            ResourceId resource = Objects.requireNonNull(entry.getKey(), "ownedResources key");
            long owned = Objects.requireNonNull(entry.getValue(), "ownedResources value");
            if (owned <= 0 || owned > MaterialConstraints.MAX_RESOURCE_QUANTITY) {
                throw new IllegalArgumentException(
                        "Owned resource quantity must be positive and bounded for " + resource);
            }
            copy.put(resource, owned);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }
}
