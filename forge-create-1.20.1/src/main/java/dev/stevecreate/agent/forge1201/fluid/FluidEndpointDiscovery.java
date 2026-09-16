package dev.stevecreate.agent.forge1201.fluid;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * Which fluid endpoints are near a point, and what they hold.
 *
 * <p>KI-089's gap: exact transfer between two endpoints works, but nothing finds them, so
 * a player had to name both by hand and connected pipework was invisible. This closes the
 * finding half, and deliberately mirrors {@code WarehouseDiscovery} — it is the same
 * problem, and two discovery mechanisms that behave differently would be two sets of
 * rules for the player to learn.
 *
 * <p>It reports. It does not authorise, connect, or move anything. Transfers still name
 * their source and destination explicitly, and a tank the player never named is never
 * drained. Seeing a tank is not permission to empty it.
 */
public final class FluidEndpointDiscovery {
    /** Beyond this a scan is surveying the map rather than a workshop. */
    public static final int MAX_RADIUS = 16;
    public static final int MAX_RESULTS = 32;

    private FluidEndpointDiscovery() {}

    /**
     * A fluid-handling block found near the scan centre.
     *
     * @param face the side the handler was reached from, which a transfer needs; a
     *     capability can differ per face, so the position alone does not identify it
     */
    public record Endpoint(
            BlockPos3i position,
            ResourceId blockEntityType,
            Direction face,
            Map<ResourceId, Long> contents,
            long totalMillibuckets,
            long capacityMillibuckets) {
        public Endpoint {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockEntityType, "blockEntityType");
            Objects.requireNonNull(face, "face");
            contents = Map.copyOf(Objects.requireNonNull(contents, "contents"));
        }

        /** Whether this endpoint can accept fluid, which a destination must. */
        public boolean hasRoom() {
            return capacityMillibuckets > totalMillibuckets;
        }
    }

    /**
     * Fluid handlers within {@code radius} of {@code centre}.
     *
     * <p>Unlike inventory discovery, empty endpoints are kept: an empty tank is a
     * perfectly good destination, and a transfer needs somewhere to put fluid as much as
     * somewhere to take it from. What is dropped instead is anything with neither
     * contents nor capacity, which can be neither.
     *
     * <p>Ordered by distance then position, so the same world answers the same way
     * twice — a scan that reordered itself between calls would make the player's
     * confirmation mean something different each time.</p>
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
            Endpoint endpoint = describe(entity, cell);
            if (endpoint != null) found.add(endpoint);
        }
        found.sort(Comparator
                .comparingLong((Endpoint value) -> distanceSquared(centre, value.position()))
                .thenComparing(value -> value.position().toString()));
        return found.size() > MAX_RESULTS ? List.copyOf(found.subList(0, MAX_RESULTS))
                : List.copyOf(found);
    }

    /** Everything the discovered endpoints hold together. */
    public static Map<ResourceId, Long> combined(List<Endpoint> endpoints) {
        Map<ResourceId, Long> totals = new LinkedHashMap<>();
        endpoints.forEach(endpoint -> endpoint.contents().forEach((fluid, amount) ->
                totals.merge(fluid, amount, Math::addExact)));
        return Map.copyOf(totals);
    }

    /**
     * The first face that exposes a handler, or null when none does.
     *
     * <p>Faces are tried in a fixed order rather than whichever the world happens to
     * report first, because the face is part of what a transfer is authorised against.</p>
     */
    private static Endpoint describe(BlockEntity entity, BlockPos3i cell) {
        for (Direction face : Direction.values()) {
            IFluidHandler handler =
                    entity.getCapability(ForgeCapabilities.FLUID_HANDLER, face).resolve().orElse(null);
            if (handler == null) continue;
            Map<ResourceId, Long> contents = new LinkedHashMap<>();
            long stored = 0;
            long capacity = 0;
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                capacity = Math.addExact(capacity, handler.getTankCapacity(tank));
                FluidStack stack = handler.getFluidInTank(tank);
                if (stack.isEmpty()) continue;
                ResourceLocation fluidId = net.minecraftforge.registries.ForgeRegistries.FLUIDS
                        .getKey(stack.getFluid());
                if (fluidId == null) continue;
                contents.merge(ResourceId.parse(fluidId.toString()),
                        (long) stack.getAmount(), Math::addExact);
                stored = Math.addExact(stored, stack.getAmount());
            }
            // Neither able to give nor to receive; not an endpoint for any transfer.
            if (capacity == 0 && stored == 0) continue;
            ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            return new Endpoint(cell,
                    ResourceId.parse(type == null ? "minecraft:unknown" : type.toString()),
                    face, contents, stored, capacity);
        }
        return null;
    }

    private static long distanceSquared(BlockPos centre, BlockPos3i cell) {
        long dx = (long) cell.x() - centre.getX();
        long dy = (long) cell.y() - centre.getY();
        long dz = (long) cell.z() - centre.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
