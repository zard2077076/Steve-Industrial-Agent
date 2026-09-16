package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable physical binding for reviewed IE Alloy Smelter player orders.
 *
 * <p>The common industrial-order envelope owns lifecycle, effects and the final
 * report. This row owns only the exact adapter inputs, locations and restorable
 * baseline needed to reconcile that envelope after a restart.</p>
 */
public final class AlloySmelterOrderSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_ie_alloy_smelter_orders";
    public static final int SCHEMA = 2;
    public static final int MAX_BASELINE_BLOCKS = 32;
    private final Map<UUID, StoredOrder> orders = new LinkedHashMap<>();

    public static AlloySmelterOrderSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                AlloySmelterOrderSavedData::load, AlloySmelterOrderSavedData::new, DATA_NAME);
    }

    public static AlloySmelterOrderSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported Alloy Smelter order schema " + schema);
        }
        AlloySmelterOrderSavedData data = new AlloySmelterOrderSavedData();
        for (Tag raw : root.getList("Orders", Tag.TAG_COMPOUND)) {
            try {
                StoredOrder stored = decode((CompoundTag) raw);
                data.orders.put(stored.orderId(), stored);
            } catch (IllegalArgumentException ignored) {
                // Malformed rows remain non-authoritative and can never execute.
            }
        }
        return data;
    }

    public Map<UUID, StoredOrder> orders() { return Map.copyOf(orders); }

    public Optional<StoredOrder> order(UUID orderId) {
        return Optional.ofNullable(orders.get(Objects.requireNonNull(orderId, "orderId")));
    }

    public void put(StoredOrder stored) {
        orders.put(Objects.requireNonNull(stored, "stored").orderId(), stored);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag rows = new ListTag();
        orders.values().stream().sorted(Comparator.comparing(value -> value.orderId().toString()))
                .map(AlloySmelterOrderSavedData::encode).forEach(rows::add);
        root.put("Orders", rows);
        return root;
    }

    private static CompoundTag encode(StoredOrder order) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OrderId", order.orderId());
        tag.putUUID("PlayerId", order.playerId());
        tag.putString("Dimension", order.dimension().toString());
        putPosition(tag, "Origin", order.machineOrigin());
        putPosition(tag, "Source", order.materialSource());
        putPosition(tag, "Staging", order.staging());
        tag.putString("Recipe", order.recipeId().toString());
        putResource(tag, "First", order.firstInput(), order.firstInputCount());
        putResource(tag, "Second", order.secondInput(), order.secondInputCount());
        putResource(tag, "Fuel", order.fuel(), order.fuelCount());
        putResource(tag, "Output", order.output(), order.outputCount());
        tag.putString("PlanHash", order.planHash());
        tag.putString("Runtime", order.runtimeFingerprint());
        tag.putString("BaselineHash", order.baselineHash());
        order.warehouseBinding().ifPresent(binding -> {
            tag.putString("WarehouseId", binding.warehouseId().toString());
            tag.putString("WarehouseOrderId", binding.productionOrderId().toString());
            tag.putString("WarehouseBatchId", binding.batchId().toString());
            putPosition(tag, "OutputDestination", binding.outputDestination());
        });
        ListTag baseline = new ListTag();
        order.baseline().forEach(block -> {
            CompoundTag row = new CompoundTag();
            putPosition(row, "", block.position());
            row.put("State", block.serializedState());
            baseline.add(row);
        });
        tag.put("Baseline", baseline);
        return tag;
    }

    private static StoredOrder decode(CompoundTag tag) {
        ArrayList<BaselineBlock> baseline = new ArrayList<>();
        for (Tag raw : tag.getList("Baseline", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            baseline.add(new BaselineBlock(readPosition(row, ""), row.getCompound("State")));
        }
        return new StoredOrder(tag.getUUID("OrderId"), tag.getUUID("PlayerId"),
                ResourceId.parse(tag.getString("Dimension")), readPosition(tag, "Origin"),
                readPosition(tag, "Source"), readPosition(tag, "Staging"),
                ResourceId.parse(tag.getString("Recipe")),
                readResource(tag, "First"), tag.getInt("FirstCount"),
                readResource(tag, "Second"), tag.getInt("SecondCount"),
                readResource(tag, "Fuel"), tag.getInt("FuelCount"),
                readResource(tag, "Output"), tag.getInt("OutputCount"),
                tag.getString("PlanHash"), tag.getString("Runtime"),
                tag.getString("BaselineHash"), baseline,
                tag.contains("WarehouseId") ? Optional.of(new WarehouseBinding(
                        ResourceId.parse(tag.getString("WarehouseId")),
                        ResourceId.parse(tag.getString("WarehouseOrderId")),
                        ResourceId.parse(tag.getString("WarehouseBatchId")),
                        readPosition(tag, "OutputDestination"))) : Optional.empty());
    }

    private static void putResource(
            CompoundTag tag, String name, ResourceId resource, int quantity) {
        tag.putString(name, resource.toString());
        tag.putInt(name + "Count", quantity);
    }

    private static ResourceId readResource(CompoundTag tag, String name) {
        return ResourceId.parse(tag.getString(name));
    }

    private static void putPosition(CompoundTag tag, String prefix, BlockPos3i position) {
        tag.putInt(prefix + "X", position.x());
        tag.putInt(prefix + "Y", position.y());
        tag.putInt(prefix + "Z", position.z());
    }

    private static BlockPos3i readPosition(CompoundTag tag, String prefix) {
        return new BlockPos3i(tag.getInt(prefix + "X"), tag.getInt(prefix + "Y"),
                tag.getInt(prefix + "Z"));
    }

    public record StoredOrder(
            UUID orderId,
            UUID playerId,
            ResourceId dimension,
            BlockPos3i machineOrigin,
            BlockPos3i materialSource,
            BlockPos3i staging,
            ResourceId recipeId,
            ResourceId firstInput,
            int firstInputCount,
            ResourceId secondInput,
            int secondInputCount,
            ResourceId fuel,
            int fuelCount,
            ResourceId output,
            int outputCount,
            String planHash,
            String runtimeFingerprint,
            String baselineHash,
            List<BaselineBlock> baseline,
            Optional<WarehouseBinding> warehouseBinding) {
        /** Player-created orders predate and deliberately omit warehouse authority. */
        public StoredOrder(
                UUID orderId,
                UUID playerId,
                ResourceId dimension,
                BlockPos3i machineOrigin,
                BlockPos3i materialSource,
                BlockPos3i staging,
                ResourceId recipeId,
                ResourceId firstInput,
                int firstInputCount,
                ResourceId secondInput,
                int secondInputCount,
                ResourceId fuel,
                int fuelCount,
                ResourceId output,
                int outputCount,
                String planHash,
                String runtimeFingerprint,
                String baselineHash,
                List<BaselineBlock> baseline) {
            this(orderId, playerId, dimension, machineOrigin, materialSource, staging,
                    recipeId, firstInput, firstInputCount, secondInput, secondInputCount,
                    fuel, fuelCount, output, outputCount, planHash, runtimeFingerprint,
                    baselineHash, baseline, Optional.empty());
        }

        public StoredOrder {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(machineOrigin, "machineOrigin");
            Objects.requireNonNull(materialSource, "materialSource");
            Objects.requireNonNull(staging, "staging");
            Objects.requireNonNull(recipeId, "recipeId");
            Objects.requireNonNull(firstInput, "firstInput");
            Objects.requireNonNull(secondInput, "secondInput");
            Objects.requireNonNull(fuel, "fuel");
            Objects.requireNonNull(output, "output");
            if (firstInputCount < 1 || secondInputCount < 1 || fuelCount != 1
                    || outputCount < 1 || outputCount > 64) {
                throw new IllegalArgumentException("Alloy Smelter resource counts are invalid");
            }
            requireHash(planHash, "planHash");
            if (runtimeFingerprint == null || runtimeFingerprint.isBlank()
                    || runtimeFingerprint.length() > 1_024) {
                throw new IllegalArgumentException("Alloy Smelter runtime is invalid");
            }
            requireHash(baselineHash, "baselineHash");
            baseline = List.copyOf(Objects.requireNonNull(baseline, "baseline"));
            warehouseBinding = Objects.requireNonNull(warehouseBinding, "warehouseBinding");
            if (baseline.isEmpty() || baseline.size() > MAX_BASELINE_BLOCKS
                    || baseline.stream().map(BaselineBlock::position).distinct().count()
                            != baseline.size()) {
                throw new IllegalArgumentException("Alloy Smelter baseline is invalid");
            }
        }

        private static void requireHash(String value, String name) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(name + " must be SHA-256");
            }
        }
    }

    /** Exact standing-order authority attached to an unattended physical batch. */
    public record WarehouseBinding(
            ResourceId warehouseId,
            ResourceId productionOrderId,
            ResourceId batchId,
            BlockPos3i outputDestination) {
        public WarehouseBinding {
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(productionOrderId, "productionOrderId");
            Objects.requireNonNull(batchId, "batchId");
            Objects.requireNonNull(outputDestination, "outputDestination");
        }
    }

    public record BaselineBlock(BlockPos3i position, CompoundTag serializedState) {
        public BaselineBlock {
            Objects.requireNonNull(position, "position");
            serializedState = Objects.requireNonNull(serializedState, "serializedState").copy();
            if (serializedState.isEmpty()) {
                throw new IllegalArgumentException("baseline block state is empty");
            }
        }

        @Override public CompoundTag serializedState() { return serializedState.copy(); }
    }
}
