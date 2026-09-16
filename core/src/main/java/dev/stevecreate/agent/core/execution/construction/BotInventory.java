package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded Bot-only carried inventory; it has no player inventory bridge. */
public record BotInventory(
        ResourceId workerId,
        int capacity,
        Map<ResourceId, Long> quantities,
        long generation,
        long updatedTick) {
    public static final int MAX_CAPACITY = 2_304;

    public BotInventory {
        Objects.requireNonNull(workerId, "workerId");
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY);
        }
        Objects.requireNonNull(quantities, "quantities");
        if (quantities.size() > ConstructionContractValues.MAX_IDS) {
            throw new IllegalArgumentException("Bot inventory resource count is unbounded");
        }
        List<Map.Entry<ResourceId, Long>> ordered = quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList();
        Map<ResourceId, Long> copied = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<ResourceId, Long> entry : ordered) {
            ResourceId resourceId = Objects.requireNonNull(entry.getKey(), "inventory resource id");
            long quantity = Objects.requireNonNull(entry.getValue(), "inventory quantity");
            if (quantity < 0) {
                throw new IllegalArgumentException("Bot inventory quantities must be non-negative");
            }
            total = Math.addExact(total, quantity);
            if (quantity > 0) copied.put(resourceId, quantity);
        }
        if (total > capacity) {
            throw new IllegalArgumentException("Bot inventory exceeds capacity");
        }
        quantities = Collections.unmodifiableMap(copied);
        if (generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("Bot inventory generation/tick must be non-negative");
        }
    }

    public long quantity(ResourceId resourceId) {
        return quantities.getOrDefault(Objects.requireNonNull(resourceId, "resourceId"), 0L);
    }

    public long usedCapacity() {
        long total = 0;
        for (long value : quantities.values()) total = Math.addExact(total, value);
        return total;
    }

    public long remainingCapacity() {
        return capacity - usedCapacity();
    }
}
