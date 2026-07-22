package dev.stevecreate.agent.core.execution.readiness;

import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.model.BlockPos3i;
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

/** Immutable loader-neutral facts observed immediately before a session may be created. */
public record ExecutionReadinessContext(
        ResourceId requestedSessionId,
        String runtimeFingerprint,
        Set<ResourceId> availableAdapterIds,
        Set<ResourceId> availableImplementationIds,
        ResourceId dimensionId,
        ExecutionWorldClassification worldClassification,
        boolean authoritativeServerThread,
        Set<BlockPos3i> loadedPositions,
        PlacementSnapshot currentSnapshot,
        Map<ResourceId, Long> availableInputResources,
        long rotationalPowerCapacity,
        long stressCapacity,
        int constructionBudget,
        boolean journalWritable,
        boolean rollbackSafe,
        boolean reloadRecoverySafe,
        Set<ResourceId> activeSessionIds) {
    public static final int MAX_CONSTRUCTION_BUDGET = 4_096;

    public ExecutionReadinessContext {
        Objects.requireNonNull(requestedSessionId, "requestedSessionId");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        availableAdapterIds = ids(availableAdapterIds, "availableAdapterIds");
        availableImplementationIds = ids(availableImplementationIds, "availableImplementationIds");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(worldClassification, "worldClassification");
        Objects.requireNonNull(loadedPositions, "loadedPositions");
        if (loadedPositions.size() > PlacementSnapshot.MAX_CELLS
                || loadedPositions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("loadedPositions is outside its bound");
        }
        loadedPositions = Collections.unmodifiableSet(new LinkedHashSet<>(loadedPositions));
        Objects.requireNonNull(currentSnapshot, "currentSnapshot");
        availableInputResources = resources(availableInputResources);
        if (rotationalPowerCapacity < 0 || stressCapacity < 0) {
            throw new IllegalArgumentException("Power and stress capacities cannot be negative");
        }
        if (constructionBudget < 1 || constructionBudget > MAX_CONSTRUCTION_BUDGET) {
            throw new IllegalArgumentException("constructionBudget is outside its bound");
        }
        activeSessionIds = ids(activeSessionIds, "activeSessionIds");
    }

    private static Set<ResourceId> ids(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > 256 || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        sorted.addAll(values);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Map<ResourceId, Long> resources(Map<ResourceId, Long> values) {
        Objects.requireNonNull(values, "availableInputResources");
        if (values.size() > 256) throw new IllegalArgumentException("Too many available inputs");
        TreeMap<ResourceId, Long> sorted = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, "available input resource");
            Objects.requireNonNull(amount, "available input amount");
            if (amount < 0) throw new IllegalArgumentException("Available input amount cannot be negative");
            sorted.put(resource, amount);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
