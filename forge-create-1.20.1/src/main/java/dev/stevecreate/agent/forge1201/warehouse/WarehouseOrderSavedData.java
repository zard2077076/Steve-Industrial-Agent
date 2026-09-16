package dev.stevecreate.agent.forge1201.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable allowlisted maintain-stock orders. It stores no inventory contents or path authority. */
public final class WarehouseOrderSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_warehouse_orders";
    public static final int SCHEMA = 1;
    private final Map<ResourceId, ProductionOrder> orders = new LinkedHashMap<>();

    public static WarehouseOrderSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                WarehouseOrderSavedData::load, WarehouseOrderSavedData::new, DATA_NAME);
    }

    public static WarehouseOrderSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported warehouse-order schema " + schema);
        }
        WarehouseOrderSavedData result = new WarehouseOrderSavedData();
        for (Tag value : root.getList("Orders", Tag.TAG_COMPOUND)) {
            try {
                ProductionOrder order = decode((CompoundTag) value);
                result.orders.put(order.orderId(), order);
            } catch (IllegalArgumentException ignored) {
                // A malformed order is never reconstructed into production authority.
            }
        }
        return result;
    }

    public Map<ResourceId, ProductionOrder> orders() { return Map.copyOf(orders); }

    public Optional<ProductionOrder> order(ResourceId orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public void put(ProductionOrder order) {
        orders.put(order.orderId(), order);
        setDirty();
    }

    public void remove(ResourceId orderId) {
        if (orders.remove(orderId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag values = new ListTag();
        orders.values().stream().sorted(Comparator.comparing(value ->
                value.orderId().toString())).map(WarehouseOrderSavedData::encode)
                .forEach(values::add);
        root.put("Orders", values);
        return root;
    }

    private static CompoundTag encode(ProductionOrder order) {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderId", order.orderId().toString());
        tag.putString("OwnerId", order.ownerId().toString());
        tag.putString("WarehouseId", order.warehouseId().toString());
        CompoundTag target = new CompoundTag();
        target.putString("Type", order.target().resourceType().name());
        target.putString("Resource", order.target().resourceId().toString());
        target.putString("Components", order.target().componentSha256());
        tag.put("Target", target);
        tag.putLong("TargetStock", order.targetStock());
        tag.putLong("BatchOutput", order.verifiedBatchOutput());
        tag.putInt("MaxBatches", order.maximumBatchesPerDispatch());
        tag.putString("GraphId", order.approvedProductionGraphId().toString());
        tag.put("RecipeIds", ids(order.approvedRecipeIds()));
        tag.put("AdapterIds", ids(order.approvedAdapterIds()));
        tag.put("SiteIds", ids(new LinkedHashSet<>(order.approvedSiteIds())));
        tag.putInt("MaxRetries", order.maximumRetries());
        tag.putInt("Failures", order.consecutiveFailures());
        tag.putLong("NextTick", order.nextEligibleTick());
        order.inFlightBatchId().ifPresent(value -> tag.putString("BatchId", value.toString()));
        tag.putString("Status", order.status().name());
        tag.putString("StatusCode", order.lastStatusCode());
        tag.putLong("Generation", order.generation());
        return tag;
    }

    private static ProductionOrder decode(CompoundTag tag) {
        CompoundTag target = tag.getCompound("Target");
        return new ProductionOrder(
                ResourceId.parse(tag.getString("OrderId")),
                ResourceId.parse(tag.getString("OwnerId")),
                ResourceId.parse(tag.getString("WarehouseId")),
                new WarehouseResourceKey(
                        GenericResourceType.valueOf(target.getString("Type")),
                        ResourceId.parse(target.getString("Resource")),
                        target.getString("Components")),
                tag.getLong("TargetStock"), tag.getLong("BatchOutput"),
                tag.getInt("MaxBatches"), ResourceId.parse(tag.getString("GraphId")),
                Set.copyOf(readIds(tag.getList("RecipeIds", Tag.TAG_STRING))),
                Set.copyOf(readIds(tag.getList("AdapterIds", Tag.TAG_STRING))),
                readIds(tag.getList("SiteIds", Tag.TAG_STRING)),
                tag.getInt("MaxRetries"), tag.getInt("Failures"), tag.getLong("NextTick"),
                tag.contains("BatchId", Tag.TAG_STRING)
                        ? Optional.of(ResourceId.parse(tag.getString("BatchId")))
                        : Optional.empty(),
                ProductionOrderStatus.valueOf(tag.getString("Status")),
                tag.getString("StatusCode"), tag.getLong("Generation"));
    }

    private static ListTag ids(Set<ResourceId> values) {
        ListTag result = new ListTag();
        values.stream().sorted(Comparator.comparing(ResourceId::toString))
                .map(value -> StringTag.valueOf(value.toString())).forEach(result::add);
        return result;
    }

    private static List<ResourceId> readIds(ListTag values) {
        ArrayList<ResourceId> result = new ArrayList<>();
        for (Tag value : values) result.add(ResourceId.parse(value.getAsString()));
        return List.copyOf(result);
    }
}
