package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loader-neutral exact material boundary for one bounded transport stage.
 *
 * <p>Entries are canonicalized by resource identity so Direct, Bot and Hybrid task mapping cannot
 * depend on caller map iteration order. Each entry fits one visible carried stack; larger or more
 * complex logistics remain typed unsupported until a separately proven batching contract exists.
 */
public record BoundedMaterialManifest(List<Entry> entries) {
    public static final int MAX_DISTINCT_RESOURCES = 16;
    public static final long MAX_STACK_QUANTITY = 64;

    public BoundedMaterialManifest {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.isEmpty() || entries.size() > MAX_DISTINCT_RESOURCES) {
            throw new IllegalArgumentException(
                    "entries must contain 1-" + MAX_DISTINCT_RESOURCES
                            + " exact material stacks");
        }
        Set<ResourceId> identities = new HashSet<>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!identities.add(entry.resourceId())) {
                throw new IllegalArgumentException(
                        "duplicate material identity: " + entry.resourceId());
            }
        }
        List<Entry> canonical = new ArrayList<>(entries);
        canonical.sort((left, right) ->
                left.resourceId().toString().compareTo(right.resourceId().toString()));
        entries = List.copyOf(canonical);
    }

    public static BoundedMaterialManifest from(Map<ResourceId, Long> quantities) {
        Objects.requireNonNull(quantities, "quantities");
        List<Entry> entries = quantities.entrySet().stream()
                .map(value -> new Entry(value.getKey(), value.getValue()))
                .toList();
        return new BoundedMaterialManifest(entries);
    }

    public long totalQuantity() {
        long total = 0;
        for (Entry entry : entries) {
            total = Math.addExact(total, entry.quantity());
        }
        return total;
    }

    public record Entry(ResourceId resourceId, long quantity) {
        public Entry {
            Objects.requireNonNull(resourceId, "resourceId");
            if (quantity <= 0 || quantity > MAX_STACK_QUANTITY) {
                throw new IllegalArgumentException(
                        "quantity must be in [1," + MAX_STACK_QUANTITY + "]");
            }
        }
    }
}
