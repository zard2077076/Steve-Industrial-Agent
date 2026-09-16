package dev.stevecreate.agent.forge1201.warehouse;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What a warehouse needs in order to keep producing after the server restarts.
 *
 * <p>The production orders themselves have always been durable. The runtime that acts on
 * them was not: it lived in a static map cleared on shutdown, so a restarted server read
 * its orders, found no runtime, and skipped every one of them forever. A factory that
 * stops when the server restarts is not unattended, and the orders sitting in storage
 * made it look like it was still working.
 *
 * <p>This stores the registration, not the inventory. Where the warehouse is, how far it
 * looks, where it builds, what it builds and with what executor — everything needed to
 * reconstruct the runtime and nothing about what is in the chests, which is read from the
 * world every time precisely so it cannot go stale.
 */
public final class WarehouseRuntimeSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_warehouse_runtimes";
    public static final int SCHEMA = 2;

    private final Map<ResourceId, Registration> registrations = new LinkedHashMap<>();

    /**
     * One warehouse's standing instruction.
     *
     * @param siteChest where a carrying bot sets material down, empty for warehouses that
     *     reserve straight from their own containers with no carry
     */
    public record Registration(
            ResourceId warehouseId,
            BlockPos3i warehouseCentre,
            int radius,
            List<BlockPos3i> siteOrigins,
            ResourceId orderType,
            ExecutionMode mode,
            RuntimeKind runtimeKind,
            Optional<BlockPos3i> siteChest) {
        /** More sites than an operator can reasonably keep clear. */
        public static final int MAX_SITES = 8;

        public Registration {
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(warehouseCentre, "warehouseCentre");
            siteOrigins = List.copyOf(Objects.requireNonNull(siteOrigins, "siteOrigins"));
            Objects.requireNonNull(orderType, "orderType");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(runtimeKind, "runtimeKind");
            Objects.requireNonNull(siteChest, "siteChest");
            if (radius < 1 || radius > 16) {
                throw new IllegalArgumentException("warehouse radius must be between 1 and 16");
            }
            if (siteOrigins.isEmpty() || siteOrigins.size() > MAX_SITES) {
                throw new IllegalArgumentException("a warehouse needs between 1 and "
                        + MAX_SITES + " sites");
            }
        }

        /** A warehouse with one site, which is what most of them are. */
        public Registration(
                ResourceId warehouseId,
                BlockPos3i warehouseCentre,
                int radius,
                List<BlockPos3i> siteOrigins,
                ResourceId orderType,
                ExecutionMode mode,
                Optional<BlockPos3i> siteChest) {
            this(warehouseId, warehouseCentre, radius, siteOrigins, orderType, mode,
                    RuntimeKind.COMPOSITE, siteChest);
        }

        public Registration(
                ResourceId warehouseId,
                BlockPos3i warehouseCentre,
                int radius,
                BlockPos3i siteOrigin,
                ResourceId orderType,
                ExecutionMode mode,
                Optional<BlockPos3i> siteChest) {
            this(warehouseId, warehouseCentre, radius, List.of(siteOrigin), orderType, mode,
                    RuntimeKind.COMPOSITE, siteChest);
        }

        public Registration(
                ResourceId warehouseId,
                BlockPos3i warehouseCentre,
                int radius,
                BlockPos3i siteOrigin,
                ResourceId orderType,
                ExecutionMode mode,
                RuntimeKind runtimeKind,
                Optional<BlockPos3i> siteChest) {
            this(warehouseId, warehouseCentre, radius, List.of(siteOrigin), orderType, mode,
                    runtimeKind, siteChest);
        }

        /** The site a caller should try first. */
        public BlockPos3i siteOrigin() {
            return siteOrigins.get(0);
        }
    }

    public static WarehouseRuntimeSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                WarehouseRuntimeSavedData::load, WarehouseRuntimeSavedData::new, DATA_NAME);
    }

    public static WarehouseRuntimeSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported warehouse-runtime schema " + schema);
        }
        WarehouseRuntimeSavedData result = new WarehouseRuntimeSavedData();
        for (Tag value : root.getList("Registrations", Tag.TAG_COMPOUND)) {
            try {
                Registration registration = decode((CompoundTag) value);
                result.registrations.put(registration.warehouseId(), registration);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // A malformed registration is never rebuilt into production authority.
                // Dropping one costs a warehouse its standing instruction; acting on one
                // that cannot be read would build something nobody described.
            }
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag list = new ListTag();
        registrations.values().forEach(registration -> list.add(encode(registration)));
        root.put("Registrations", list);
        return root;
    }

    public void put(Registration registration) {
        registrations.put(Objects.requireNonNull(registration, "registration").warehouseId(),
                registration);
        setDirty();
    }

    public void remove(ResourceId warehouseId) {
        if (registrations.remove(warehouseId) != null) setDirty();
    }

    public Map<ResourceId, Registration> registrations() {
        return Map.copyOf(registrations);
    }

    private static CompoundTag encode(Registration registration) {
        CompoundTag tag = new CompoundTag();
        tag.putString("WarehouseId", registration.warehouseId().toString());
        putCell(tag, "Centre", registration.warehouseCentre());
        tag.putInt("Radius", registration.radius());
        // Written as a list, and the single-site key is written too so a build that
        // predates alternatives can still read its own warehouses back.
        ListTag sites = new ListTag();
        registration.siteOrigins().forEach(cell -> {
            CompoundTag entry = new CompoundTag();
            putCell(entry, "", cell);
            sites.add(entry);
        });
        tag.put("Sites", sites);
        putCell(tag, "Site", registration.siteOrigin());
        tag.putString("OrderType", registration.orderType().toString());
        tag.putString("Mode", registration.mode().name());
        tag.putString("RuntimeKind", registration.runtimeKind().name());
        registration.siteChest().ifPresent(cell -> putCell(tag, "SiteChest", cell));
        return tag;
    }

    private static Registration decode(CompoundTag tag) {
        List<BlockPos3i> sites = new java.util.ArrayList<>();
        for (Tag entry : tag.getList("Sites", Tag.TAG_COMPOUND)) {
            sites.add(cell((CompoundTag) entry, ""));
        }
        // Older registrations carry only the single-site key.
        if (sites.isEmpty()) sites.add(cell(tag, "Site"));
        return new Registration(
                ResourceId.parse(tag.getString("WarehouseId")),
                cell(tag, "Centre"),
                tag.getInt("Radius"),
                List.copyOf(sites),
                ResourceId.parse(tag.getString("OrderType")),
                ExecutionMode.valueOf(tag.getString("Mode")),
                tag.contains("RuntimeKind")
                        ? RuntimeKind.valueOf(tag.getString("RuntimeKind"))
                        : RuntimeKind.COMPOSITE,
                tag.contains("SiteChestX") ? Optional.of(cell(tag, "SiteChest")) : Optional.empty());
    }

    private static void putCell(CompoundTag tag, String prefix, BlockPos3i cell) {
        tag.putInt(prefix + "X", cell.x());
        tag.putInt(prefix + "Y", cell.y());
        tag.putInt(prefix + "Z", cell.z());
    }

    private static BlockPos3i cell(CompoundTag tag, String prefix) {
        return new BlockPos3i(tag.getInt(prefix + "X"), tag.getInt(prefix + "Y"),
                tag.getInt(prefix + "Z"));
    }

    /** Persisted dispatcher implementation; old schema rows are Composite. */
    public enum RuntimeKind {
        COMPOSITE,
        ALLOY_SMELTER
    }
}
