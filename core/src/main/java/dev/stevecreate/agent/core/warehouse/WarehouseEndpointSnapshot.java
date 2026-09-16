package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Server-observed bounded endpoint bound into a player's logical warehouse. */
public record WarehouseEndpointSnapshot(
        ResourceId endpointId,
        ResourceId warehouseId,
        ResourceId ownerId,
        String worldIdentity,
        ResourceId dimension,
        BlockPos3i position,
        Optional<Direction6> accessFace,
        ResourceId blockEntityType,
        WarehouseEndpointType endpointType,
        Map<WarehouseResourceKey, Long> contents,
        long totalCapacity,
        String snapshotSha256,
        long generation,
        long expiresAtEpochMillis,
        boolean permissionVerified) {
    public static final int MAX_RESOURCE_ROWS = 4_096;

    public WarehouseEndpointSnapshot {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (worldIdentity.isBlank() || worldIdentity.length() > 2_048) {
            throw new IllegalArgumentException("warehouse world identity is blank or unbounded");
        }
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        accessFace = Objects.requireNonNull(accessFace, "accessFace");
        Objects.requireNonNull(blockEntityType, "blockEntityType");
        Objects.requireNonNull(endpointType, "endpointType");
        Objects.requireNonNull(contents, "contents");
        if (contents.size() > MAX_RESOURCE_ROWS) {
            throw new IllegalArgumentException("warehouse endpoint contents exceed their bound");
        }
        TreeMap<WarehouseResourceKey, Long> sorted = new TreeMap<>();
        contents.forEach((resource, quantity) -> {
            Objects.requireNonNull(resource, "warehouse resource");
            if (quantity == null || quantity < 1 || quantity > 1_000_000_000_000L) {
                throw new IllegalArgumentException("warehouse quantity is outside its bound");
            }
            sorted.put(resource, quantity);
        });
        contents = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        long stored = contents.values().stream().mapToLong(Long::longValue)
                .reduce(0, Math::addExact);
        if (totalCapacity < stored || totalCapacity > 1_000_000_000_000L) {
            throw new IllegalArgumentException("warehouse endpoint capacity is invalid");
        }
        Objects.requireNonNull(snapshotSha256, "snapshotSha256");
        if (!snapshotSha256.matches("[0-9a-f]{64}") || generation < 0
                || expiresAtEpochMillis < 1 || !permissionVerified) {
            throw new IllegalArgumentException("warehouse endpoint authority is incomplete");
        }
        GenericResourceType expected = switch (endpointType) {
            case ITEM_CONTAINER -> GenericResourceType.ITEM;
            case FLUID_TANK -> GenericResourceType.FLUID;
            case ENERGY_STORAGE -> GenericResourceType.ELECTRICAL_ENERGY;
        };
        if (contents.keySet().stream().anyMatch(key -> key.resourceType() != expected)) {
            throw new IllegalArgumentException("endpoint contains a resource of the wrong type");
        }
    }
}
