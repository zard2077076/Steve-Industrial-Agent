package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/** Exact warehouse identity; amount units are items, millibuckets, or FE by resource type. */
public record WarehouseResourceKey(
        GenericResourceType resourceType,
        ResourceId resourceId,
        String componentSha256) implements Comparable<WarehouseResourceKey> {
    public static final String EMPTY_COMPONENT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public WarehouseResourceKey {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(componentSha256, "componentSha256");
        if (!componentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("warehouse component hash is invalid");
        }
        if (resourceType != GenericResourceType.ITEM
                && resourceType != GenericResourceType.FLUID
                && resourceType != GenericResourceType.ELECTRICAL_ENERGY) {
            throw new IllegalArgumentException("warehouse resource type has no inventory unit");
        }
    }

    @Override
    public int compareTo(WarehouseResourceKey other) {
        int type = Integer.compare(resourceType.ordinal(), other.resourceType.ordinal());
        if (type != 0) return type;
        int resource = resourceId.toString().compareTo(other.resourceId.toString());
        return resource != 0 ? resource : componentSha256.compareTo(other.componentSha256);
    }
}
