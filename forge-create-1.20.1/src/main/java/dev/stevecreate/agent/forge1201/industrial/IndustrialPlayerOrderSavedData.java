package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.industrial.IndustrialCompletionReportV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable common order envelope. Physical handlers remain versioned adapter-owned. */
public final class IndustrialPlayerOrderSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_player_orders";
    public static final int SCHEMA = 1;
    private final Map<UUID, IndustrialPlayerOrderV1> orders = new LinkedHashMap<>();

    public static IndustrialPlayerOrderSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                IndustrialPlayerOrderSavedData::load, IndustrialPlayerOrderSavedData::new, DATA_NAME);
    }

    public static IndustrialPlayerOrderSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) throw new IllegalStateException("Unsupported order schema " + schema);
        IndustrialPlayerOrderSavedData data = new IndustrialPlayerOrderSavedData();
        for (Tag raw : root.getList("Orders", Tag.TAG_COMPOUND)) {
            try {
                IndustrialPlayerOrderV1 order = decode((CompoundTag) raw);
                data.orders.put(order.orderId(), order);
            } catch (IllegalArgumentException ignored) {
                // A malformed order never becomes execution authority after reload.
            }
        }
        return data;
    }

    public Optional<IndustrialPlayerOrderV1> order(UUID orderId) {
        return Optional.ofNullable(orders.get(Objects.requireNonNull(orderId, "orderId")));
    }

    public Map<UUID, IndustrialPlayerOrderV1> orders() { return Map.copyOf(orders); }

    public void put(IndustrialPlayerOrderV1 order) {
        orders.put(Objects.requireNonNull(order, "order").orderId(), order);
        setDirty();
    }

    public void remove(UUID orderId) {
        if (orders.remove(Objects.requireNonNull(orderId, "orderId")) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag encoded = new ListTag();
        orders.values().stream().sorted(Comparator.comparing(value -> value.orderId().toString()))
                .map(IndustrialPlayerOrderSavedData::encode).forEach(encoded::add);
        root.put("Orders", encoded);
        return root;
    }

    private static CompoundTag encode(IndustrialPlayerOrderV1 order) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OrderId", order.orderId()); tag.putUUID("ProjectId", order.projectId());
        tag.putUUID("OwnerId", order.ownerId()); tag.putString("World", order.worldIdentity());
        tag.putString("Dimension", order.dimension().toString());
        tag.putString("OrderType", order.orderType().toString()); tag.putString("Target", order.target().toString());
        tag.putString("Recipe", order.recipeId().toString());
        tag.putInt("AnchorX", order.anchor().x()); tag.putInt("AnchorY", order.anchor().y()); tag.putInt("AnchorZ", order.anchor().z());
        tag.putString("PlanHash", order.planHash()); tag.putString("Runtime", order.runtimeFingerprint());
        tag.putString("Baseline", order.baselineHash()); tag.putString("Phase", order.phase().name());
        tag.putString("Stage", order.stage()); tag.putLong("CreatedAt", order.createdAt());
        tag.putLong("UpdatedAt", order.updatedAt()); tag.putLong("Generation", order.generation());
        ListTag effects = new ListTag(); order.durableEffects().stream().sorted(Comparator.comparing(ResourceId::toString))
                .forEach(value -> effects.add(net.minecraft.nbt.StringTag.valueOf(value.toString())));
        tag.put("Effects", effects);
        order.report().ifPresent(value -> tag.put("Report", encode(value)));
        return tag;
    }

    private static IndustrialPlayerOrderV1 decode(CompoundTag tag) {
        Set<ResourceId> effects = new java.util.LinkedHashSet<>();
        for (Tag raw : tag.getList("Effects", Tag.TAG_STRING)) effects.add(ResourceId.parse(raw.getAsString()));
        Optional<IndustrialCompletionReportV1> report = tag.contains("Report", Tag.TAG_COMPOUND)
                ? Optional.of(decodeReport(tag.getCompound("Report"))) : Optional.empty();
        return new IndustrialPlayerOrderV1(tag.getUUID("OrderId"), tag.getUUID("ProjectId"), tag.getUUID("OwnerId"),
                tag.getString("World"), ResourceId.parse(tag.getString("Dimension")),
                ResourceId.parse(tag.getString("OrderType")), ResourceId.parse(tag.getString("Target")),
                ResourceId.parse(tag.getString("Recipe")), new BlockPos3i(tag.getInt("AnchorX"), tag.getInt("AnchorY"), tag.getInt("AnchorZ")),
                tag.getString("PlanHash"), tag.getString("Runtime"), tag.getString("Baseline"),
                IndustrialLifecyclePhase.valueOf(tag.getString("Phase")), tag.getString("Stage"), effects,
                tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"), tag.getLong("Generation"), report);
    }

    private static CompoundTag encode(IndustrialCompletionReportV1 report) {
        CompoundTag tag = new CompoundTag();
        map(tag, "Planned", report.plannedMaterials()); map(tag, "Withdrawn", report.withdrawnMaterials());
        map(tag, "Consumed", report.consumedMaterials()); map(tag, "Returned", report.returnedMaterials());
        map(tag, "Energy", report.energyConsumed()); map(tag, "Fluid", report.fluidConsumed());
        map(tag, "Outputs", report.outputs());
        tag.putLong("Salvage", report.salvageTransferred()); tag.putLong("DuplicateWithdrawals", report.duplicateWithdrawals());
        tag.putLong("DuplicateReturns", report.duplicateReturns()); tag.putLong("DuplicateEnergy", report.duplicateEnergySettlements());
        tag.putLong("DuplicateOutputs", report.duplicateOutputs()); tag.putLong("Unaccounted", report.unaccountedItems());
        tag.putLong("Private", report.privateItemsTouched()); tag.putBoolean("Balanced", report.materialLedgerBalanced());
        tag.putBoolean("BaselineRestored", report.baselineRestored()); tag.putString("Baseline", report.baselineHash());
        return tag;
    }

    private static IndustrialCompletionReportV1 decodeReport(CompoundTag tag) {
        return new IndustrialCompletionReportV1(readMap(tag, "Planned"), readMap(tag, "Withdrawn"),
                readMap(tag, "Consumed"), readMap(tag, "Returned"), readMap(tag, "Energy"),
                readMap(tag, "Fluid"), readMap(tag, "Outputs"), tag.getLong("Salvage"),
                tag.getLong("DuplicateWithdrawals"), tag.getLong("DuplicateReturns"), tag.getLong("DuplicateEnergy"),
                tag.getLong("DuplicateOutputs"), tag.getLong("Unaccounted"), tag.getLong("Private"),
                tag.getBoolean("Balanced"), tag.getBoolean("BaselineRestored"), tag.getString("Baseline"));
    }

    private static void map(CompoundTag parent, String name, Map<ResourceId, Long> values) {
        ListTag rows = new ListTag();
        values.forEach((resource, quantity) -> {
            CompoundTag row = new CompoundTag(); row.putString("Resource", resource.toString()); row.putLong("Quantity", quantity); rows.add(row);
        });
        parent.put(name, rows);
    }

    private static Map<ResourceId, Long> readMap(CompoundTag parent, String name) {
        LinkedHashMap<ResourceId, Long> result = new LinkedHashMap<>();
        for (Tag raw : parent.getList(name, Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw; result.put(ResourceId.parse(row.getString("Resource")), row.getLong("Quantity"));
        }
        return result;
    }
}
