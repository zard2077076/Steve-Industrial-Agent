package dev.stevecreate.agent.forge1201.electrical;

import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Which blocks near a point can give or take Forge Energy, and which way each one goes.
 *
 * <p>Third in the same family as {@code WarehouseDiscovery} and
 * {@code FluidEndpointDiscovery}: find what is nearby, report it, authorise nothing. The
 * shared rules are shared on purpose — deterministic order by distance, an exclusion
 * list, a bounded radius — because a player who learns one should not have to learn three.
 *
 * <p>What is genuinely different here is direction. An item chest and a fluid tank can
 * both be drawn from and filled; an energy endpoint frequently cannot. A generator only
 * gives, a machine only takes, and a battery does both, and that distinction is not
 * cosmetic — pairing two consumers moves nothing while looking perfectly reasonable in a
 * list of stored/capacity numbers. {@link IEnergyStorage} states it directly through
 * {@code canExtract} and {@code canReceive}, and core already has the vocabulary for it
 * in {@link ElectricalNodeKind}, so this is the first place the two are joined up.
 *
 * <p>Emptiness is deliberately not a filter, following the fluid rule rather than the
 * chest rule. An empty battery is a destination and a full one is a source; dropping
 * either would hide half of every grid.
 */
public final class EnergyEndpointDiscovery {
    /** Beyond this a scan surveys the map rather than a workshop. */
    public static final int MAX_RADIUS = 16;
    /** More endpoints than a player can confirm one at a time. */
    public static final int MAX_RESULTS = 32;

    private EnergyEndpointDiscovery() {}

    /**
     * An energy-handling block found near the scan centre.
     *
     * @param face the side the capability was reached from; energy capabilities commonly
     *     differ per face, so a position alone does not identify an endpoint
     * @param kind which way energy can move, which decides what this endpoint can be
     *     paired with
     */
    public record Endpoint(
            BlockPos3i position,
            ResourceId blockEntityType,
            Direction face,
            ElectricalNodeKind kind,
            long storedFe,
            long capacityFe,
            long maxReceiveFe,
            long maxExtractFe) {
        public Endpoint {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockEntityType, "blockEntityType");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(kind, "kind");
        }

        /** Whether this endpoint can supply energy to something else. */
        public boolean canSupply() {
            return kind == ElectricalNodeKind.GENERATOR || kind == ElectricalNodeKind.STORAGE;
        }

        /** Whether this endpoint can accept energy from something else. */
        public boolean canAccept() {
            return kind == ElectricalNodeKind.CONSUMER || kind == ElectricalNodeKind.STORAGE;
        }
    }

    /**
     * Which node kind a handler behaves as, or empty when it is neither.
     *
     * <p>A handler that can do neither still answers the capability — a meter, a display,
     * a cable segment that only relays. It is not an endpoint for any transfer, and
     * listing it would offer the player something that cannot participate.</p>
     */
    public static Optional<ElectricalNodeKind> classify(IEnergyStorage storage) {
        Objects.requireNonNull(storage, "storage");
        boolean gives = storage.canExtract();
        boolean takes = storage.canReceive();
        if (gives && takes) return Optional.of(ElectricalNodeKind.STORAGE);
        if (gives) return Optional.of(ElectricalNodeKind.GENERATOR);
        if (takes) return Optional.of(ElectricalNodeKind.CONSUMER);
        return Optional.empty();
    }

    /**
     * Energy endpoints within {@code radius} of {@code centre}, nearest first.
     *
     * @param excluded positions to skip, for cells an order already owns
     */
    public static List<Endpoint> endpoints(
            ServerLevel level, BlockPos centre, int radius, List<BlockPos3i> excluded) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(centre, "centre");
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("scan radius must be between 1 and " + MAX_RADIUS);
        }
        List<BlockPos3i> skip = List.copyOf(Objects.requireNonNull(excluded, "excluded"));
        List<Endpoint> found = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(
                centre.offset(-radius, -radius, -radius),
                centre.offset(radius, radius, radius))) {
            // betweenClosed reuses one mutable position; anything kept must be a copy.
            BlockPos immutable = position.immutable();
            BlockPos3i cell = new BlockPos3i(immutable.getX(), immutable.getY(), immutable.getZ());
            if (skip.contains(cell)) continue;
            if (!level.hasChunkAt(immutable)) continue;
            BlockEntity entity = level.getBlockEntity(immutable);
            if (entity == null) continue;
            reachable(entity).ifPresent(found::add);
        }
        found.sort(Comparator
                .comparingLong((Endpoint value) -> distanceSquared(centre, value.position()))
                .thenComparing(value -> value.position().toString()));
        return found.size() > MAX_RESULTS ? List.copyOf(found.subList(0, MAX_RESULTS))
                : List.copyOf(found);
    }

    /** Everything the discovered endpoints hold, and everything they could hold. */
    public static Grid summarise(List<Endpoint> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        long stored = endpoints.stream().mapToLong(Endpoint::storedFe).sum();
        long capacity = endpoints.stream().mapToLong(Endpoint::capacityFe).sum();
        long suppliers = endpoints.stream().filter(Endpoint::canSupply).count();
        long consumers = endpoints.stream().filter(Endpoint::canAccept).count();
        return new Grid(stored, capacity, (int) suppliers, (int) consumers,
                pairable(endpoints));
    }

    /**
     * Whether two <em>different</em> endpoints could move energy between them.
     *
     * <p>Counts alone get this wrong. One battery is both a supplier and a consumer, so
     * "suppliers &gt; 0 and consumers &gt; 0" says yes for a grid of a single block that
     * has nothing to trade with. The fluid executor refuses the same shape outright as
     * ENDPOINTS_IDENTICAL; the question here is asked before any pairing exists, so it
     * has to look for a genuine pair.</p>
     */
    private static boolean pairable(List<Endpoint> endpoints) {
        for (int giver = 0; giver < endpoints.size(); giver++) {
            if (!endpoints.get(giver).canSupply()) continue;
            for (int taker = 0; taker < endpoints.size(); taker++) {
                if (giver != taker && endpoints.get(taker).canAccept()) return true;
            }
        }
        return false;
    }

    /**
     * A read-only picture of what was found.
     *
     * <p>Supplier and consumer counts overlap, because a battery is both. That is
     * deliberate: the question a caller asks is "is there anything that can give" and "is
     * there anything that can take", and a battery answers yes to both.</p>
     */
    public record Grid(
            long storedFe,
            long capacityFe,
            int suppliers,
            int consumers,
            boolean canMoveEnergy) {}

    /**
     * The first face that yields a usable handler.
     *
     * <p>Faces are tried in a fixed order so two scans of an unchanged world agree. A
     * block whose capability differs per face is reported once, at the first face that
     * works — offering the player the same block six times would be noise, and choosing
     * a face by anything world-dependent would make the scan unstable.</p>
     */
    private static Optional<Endpoint> reachable(BlockEntity entity) {
        for (Direction face : Direction.values()) {
            IEnergyStorage storage =
                    entity.getCapability(ForgeCapabilities.ENERGY, face).resolve().orElse(null);
            if (storage == null) continue;
            Optional<ElectricalNodeKind> kind = classify(storage);
            if (kind.isEmpty()) continue;
            ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            BlockPos position = entity.getBlockPos();
            // Measured, not declared. IEnergyStorage has no throughput field, but it
            // will say what it would accept or give if asked to simulate — and a
            // capability that reports zero both ways moves nothing regardless of what
            // its flags claim.
            long maxReceive = storage.receiveEnergy(Integer.MAX_VALUE, true);
            long maxExtract = storage.extractEnergy(Integer.MAX_VALUE, true);
            return Optional.of(new Endpoint(
                    new BlockPos3i(position.getX(), position.getY(), position.getZ()),
                    ResourceId.parse(type == null ? "minecraft:unknown" : type.toString()),
                    face,
                    kind.orElseThrow(),
                    storage.getEnergyStored(),
                    storage.getMaxEnergyStored(),
                    maxReceive,
                    maxExtract));
        }
        return Optional.empty();
    }

    private static long distanceSquared(BlockPos centre, BlockPos3i cell) {
        long dx = (long) cell.x() - centre.getX();
        long dy = (long) cell.y() - centre.getY();
        long dz = (long) cell.z() - centre.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
