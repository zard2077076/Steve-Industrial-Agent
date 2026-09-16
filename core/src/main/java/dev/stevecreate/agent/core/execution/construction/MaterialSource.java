package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact Phase-I material source; arbitrary/private/player storage is not representable. */
public record MaterialSource(
        ResourceId sourceId,
        MaterialSourceScope scope,
        BlockPos3i accessPosition,
        Set<ResourceId> authorizedSessionIds,
        Map<ResourceId, Long> availableQuantities,
        boolean loaded,
        long generation,
        long updatedTick) {
    public MaterialSource {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(accessPosition, "accessPosition");
        authorizedSessionIds = ConstructionContractValues.sortedIds(
                authorizedSessionIds, "authorizedSessionIds", true);
        Objects.requireNonNull(availableQuantities, "availableQuantities");
        if (availableQuantities.size() > ConstructionContractValues.MAX_IDS) {
            throw new IllegalArgumentException("availableQuantities exceeds the bounded resource count");
        }
        List<Map.Entry<ResourceId, Long>> ordered = availableQuantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList();
        Map<ResourceId, Long> copied = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, Long> entry : ordered) {
            ResourceId resourceId = Objects.requireNonNull(entry.getKey(), "resource id");
            long quantity = Objects.requireNonNull(entry.getValue(), "resource quantity");
            if (quantity < 0) {
                throw new IllegalArgumentException("Material source quantities must be non-negative");
            }
            copied.put(resourceId, quantity);
        }
        availableQuantities = Collections.unmodifiableMap(copied);
        if (generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("Material source generation/tick must be non-negative");
        }
    }

    public boolean authorizes(ResourceId sessionId) {
        return authorizedSessionIds.contains(Objects.requireNonNull(sessionId, "sessionId"));
    }

    public long available(ResourceId resourceId) {
        return availableQuantities.getOrDefault(
                Objects.requireNonNull(resourceId, "resourceId"), 0L);
    }
}
