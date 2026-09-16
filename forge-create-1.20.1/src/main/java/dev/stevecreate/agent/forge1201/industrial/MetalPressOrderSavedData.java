package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.industrial.MetalPressCompletionReport;
import dev.stevecreate.agent.core.industrial.MetalPressDurableEffect;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.industrial.MetalPressProductionOrder;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Complete durable IE Metal Press order state, including the exact restorable site baseline. */
public final class MetalPressOrderSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_ie_metal_press_orders";
    public static final int SCHEMA = 1;
    public static final int MAX_BASELINE_BLOCKS = 64;
    private final Map<UUID, StoredOrder> orders = new LinkedHashMap<>();

    public static MetalPressOrderSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                MetalPressOrderSavedData::load, MetalPressOrderSavedData::new, DATA_NAME);
    }

    public static MetalPressOrderSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported Metal Press order schema " + schema);
        }
        MetalPressOrderSavedData data = new MetalPressOrderSavedData();
        for (Tag raw : root.getList("Orders", Tag.TAG_COMPOUND)) {
            try {
                StoredOrder stored = decodeStored((CompoundTag) raw);
                data.orders.put(stored.order().orderId(), stored);
            } catch (IllegalArgumentException ignored) {
                // Never recreate production authority from a malformed row.
            }
        }
        return data;
    }

    public Map<UUID, StoredOrder> orders() { return Map.copyOf(orders); }

    public Optional<StoredOrder> order(UUID orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public void put(StoredOrder stored) {
        orders.put(stored.order().orderId(), stored);
        setDirty();
    }

    public void remove(UUID orderId) {
        if (orders.remove(orderId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag rows = new ListTag();
        orders.values().stream().sorted(Comparator.comparing(value ->
                value.order().orderId().toString())).map(MetalPressOrderSavedData::encodeStored)
                .forEach(rows::add);
        root.put("Orders", rows);
        return root;
    }

    private static CompoundTag encodeStored(StoredOrder stored) {
        CompoundTag tag = encodeOrder(stored.order());
        ListTag baseline = new ListTag();
        stored.baseline().forEach(block -> {
            CompoundTag row = new CompoundTag();
            putPosition(row, block.position());
            row.put("State", block.serializedState().copy());
            baseline.add(row);
        });
        tag.put("Baseline", baseline);
        return tag;
    }

    private static StoredOrder decodeStored(CompoundTag tag) {
        ArrayList<BaselineBlock> baseline = new ArrayList<>();
        for (Tag raw : tag.getList("Baseline", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            baseline.add(new BaselineBlock(readPosition(row), row.getCompound("State")));
        }
        return new StoredOrder(decodeOrder(tag), baseline);
    }

    private static CompoundTag encodeOrder(MetalPressProductionOrder order) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OrderId", order.orderId());
        tag.putUUID("ProjectId", order.projectId());
        tag.putUUID("PlayerId", order.playerId());
        tag.putString("Dimension", order.dimension().toString());
        CompoundTag machine = new CompoundTag();
        putPosition(machine, order.machineOrigin());
        tag.put("MachineOrigin", machine);
        CompoundTag source = new CompoundTag();
        putPosition(source, order.materialSource());
        tag.put("MaterialSource", source);
        tag.putString("RecipeId", order.recipeId().toString());
        tag.putString("PlanHash", order.planHash());
        tag.putString("RuntimeFingerprint", order.runtimeFingerprint());
        tag.putString("BaselineHash", order.baselineHash());
        tag.putLong("ExpectedEnergyFe", order.expectedEnergyFe());
        tag.putString("Stage", order.stage().name());
        ListTag effects = new ListTag();
        order.durableEffects().stream().sorted().forEach(effect -> {
            CompoundTag row = new CompoundTag();
            row.putString("Id", effect.name());
            effects.add(row);
        });
        tag.put("Effects", effects);
        tag.putLong("EnergyAtInputFe", order.energyAtInputFe());
        tag.putLong("MeasuredEnergyFe", order.measuredEnergyConsumedFe());
        tag.putLong("OutputCount", order.exactOutputCount());
        tag.putString("StatusCode", order.statusCode());
        tag.putLong("CreatedAt", order.createdAt());
        tag.putLong("UpdatedAt", order.updatedAt());
        tag.putLong("Generation", order.generation());
        order.report().ifPresent(report -> tag.put("Report", encodeReport(report)));
        return tag;
    }

    private static MetalPressProductionOrder decodeOrder(CompoundTag tag) {
        Set<MetalPressDurableEffect> effects = EnumSet.noneOf(MetalPressDurableEffect.class);
        for (Tag raw : tag.getList("Effects", Tag.TAG_COMPOUND)) {
            effects.add(MetalPressDurableEffect.valueOf(((CompoundTag) raw).getString("Id")));
        }
        return new MetalPressProductionOrder(tag.getUUID("OrderId"), tag.getUUID("ProjectId"),
                tag.getUUID("PlayerId"), ResourceId.parse(tag.getString("Dimension")),
                readPosition(tag.getCompound("MachineOrigin")),
                readPosition(tag.getCompound("MaterialSource")),
                ResourceId.parse(tag.getString("RecipeId")), tag.getString("PlanHash"),
                tag.getString("RuntimeFingerprint"), tag.getString("BaselineHash"),
                tag.getLong("ExpectedEnergyFe"),
                MetalPressOrderStage.valueOf(tag.getString("Stage")), effects,
                tag.getLong("EnergyAtInputFe"),
                tag.getLong("MeasuredEnergyFe"), tag.getLong("OutputCount"),
                tag.getString("StatusCode"), tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"),
                tag.getLong("Generation"), tag.contains("Report", Tag.TAG_COMPOUND)
                        ? Optional.of(decodeReport(tag.getCompound("Report"))) : Optional.empty());
    }

    private static CompoundTag encodeReport(MetalPressCompletionReport report) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Planned", report.plannedMaterialUnits());
        tag.putLong("Withdrawn", report.withdrawnMaterialUnits());
        tag.putLong("Consumed", report.consumedMaterialUnits());
        tag.putLong("Returned", report.returnedMaterialUnits());
        tag.putLong("Energy", report.energyConsumedFe());
        tag.putLong("Output", report.outputCount());
        tag.putInt("DuplicateWithdrawals", report.duplicateWithdrawals());
        tag.putInt("DuplicateEnergy", report.duplicateEnergySettlements());
        tag.putInt("DuplicateOutputs", report.duplicateOutputs());
        tag.putInt("DuplicateReturns", report.duplicateReturns());
        tag.putLong("Unaccounted", report.unaccountedItems());
        tag.putLong("PrivateTouched", report.privateItemsTouched());
        tag.putBoolean("Balanced", report.materialLedgerBalanced());
        tag.putBoolean("BaselineRestored", report.baselineRestored());
        tag.putString("BaselineHash", report.baselineHash());
        return tag;
    }

    private static MetalPressCompletionReport decodeReport(CompoundTag tag) {
        return new MetalPressCompletionReport(tag.getLong("Planned"), tag.getLong("Withdrawn"),
                tag.getLong("Consumed"), tag.getLong("Returned"), tag.getLong("Energy"),
                tag.getLong("Output"), tag.getInt("DuplicateWithdrawals"),
                tag.getInt("DuplicateEnergy"), tag.getInt("DuplicateOutputs"),
                tag.getInt("DuplicateReturns"), tag.getLong("Unaccounted"),
                tag.getLong("PrivateTouched"), tag.getBoolean("Balanced"),
                tag.getBoolean("BaselineRestored"), tag.getString("BaselineHash"));
    }

    private static void putPosition(CompoundTag tag, BlockPos3i position) {
        tag.putInt("X", position.x());
        tag.putInt("Y", position.y());
        tag.putInt("Z", position.z());
    }

    private static BlockPos3i readPosition(CompoundTag tag) {
        return new BlockPos3i(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }

    public record StoredOrder(MetalPressProductionOrder order, List<BaselineBlock> baseline) {
        public StoredOrder {
            if (order == null || baseline == null || baseline.isEmpty()
                    || baseline.size() > MAX_BASELINE_BLOCKS) {
                throw new IllegalArgumentException("Metal Press baseline is empty or unbounded");
            }
            baseline = baseline.stream().sorted(Comparator.comparing(BaselineBlock::position,
                    Comparator.comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y)
                            .thenComparingInt(BlockPos3i::z))).toList();
            if (baseline.stream().map(BaselineBlock::position).distinct().count() != baseline.size()) {
                throw new IllegalArgumentException("Metal Press baseline positions are duplicated");
            }
        }

        public StoredOrder withOrder(MetalPressProductionOrder next) {
            if (!order.orderId().equals(next.orderId())) {
                throw new IllegalArgumentException("cannot replace an order with another identity");
            }
            return new StoredOrder(next, baseline);
        }
    }

    public record BaselineBlock(BlockPos3i position, CompoundTag serializedState) {
        public BaselineBlock {
            if (position == null || serializedState == null || serializedState.isEmpty()) {
                throw new IllegalArgumentException("baseline block is incomplete");
            }
            serializedState = serializedState.copy();
        }

        @Override public CompoundTag serializedState() { return serializedState.copy(); }
    }
}
