package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Which containers near a point hold something, and what.
 *
 * <p>KI-088's gap is that nothing finds inventory: a player had to name every container
 * by hand, and an order was therefore capped at what one chest holds. This closes the
 * finding half.
 *
 * <p>It finds and it reports. It does not select, reserve, or take. Every container it
 * names still has to go through {@code selectStandaloneSource} with its ownership,
 * distance and permission checks, and a container the player never names is never
 * touched. Being able to see a chest is not permission to empty it — that distinction is
 * the whole reason this is a separate, read-only step rather than part of ordering.
 */
public final class WarehouseDiscovery {
    /** Beyond this a scan is a survey of the map rather than of a workshop. */
    public static final int MAX_RADIUS = 16;
    /** More containers than a player can meaningfully confirm one at a time. */
    public static final int MAX_RESULTS = 32;

    private WarehouseDiscovery() {}

    /** A container found near the scan centre, with what it currently holds. */
    public record Candidate(
            BlockPos3i position,
            ResourceId blockEntityType,
            Map<ResourceId, Long> contents,
            long totalItems,
            long totalCapacity) {
        public Candidate {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockEntityType, "blockEntityType");
            contents = Map.copyOf(Objects.requireNonNull(contents, "contents"));
        }
    }

    /**
     * Containers within {@code radius} of {@code centre} that hold at least one item.
     *
     * <p>Empty containers are left out: they cannot supply anything, and listing them
     * would bury the ones that can. Results are ordered by distance and then by position
     * so the same world always answers the same way — a scan that reorders itself
     * between calls would make the player's confirmation mean something different each
     * time.</p>
     *
     * @param excluded positions to skip, for the cells an order already owns
     */
    public static List<Candidate> candidates(
            ServerLevel level, BlockPos centre, int radius, List<BlockPos3i> excluded) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(centre, "centre");
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("scan radius must be between 1 and " + MAX_RADIUS);
        }
        List<BlockPos3i> skip = List.copyOf(Objects.requireNonNull(excluded, "excluded"));
        List<Candidate> found = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(
                centre.offset(-radius, -radius, -radius),
                centre.offset(radius, radius, radius))) {
            // betweenClosed reuses one mutable position; anything kept must be a copy.
            BlockPos immutable = position.immutable();
            BlockPos3i cell = new BlockPos3i(immutable.getX(), immutable.getY(), immutable.getZ());
            if (skip.contains(cell)) continue;
            if (!level.hasChunkAt(immutable)) continue;
            if (!(level.getBlockEntity(immutable) instanceof Container container)) continue;
            Candidate candidate = describe(level, immutable, container);
            if (candidate.contents().isEmpty()) continue;
            found.add(candidate);
        }
        found.sort(Comparator
                .comparingLong((Candidate value) -> distanceSquared(centre, value.position()))
                .thenComparing(value -> value.position().toString()));
        return found.size() > MAX_RESULTS ? List.copyOf(found.subList(0, MAX_RESULTS))
                : List.copyOf(found);
    }

    /** Everything the discovered containers hold together, for judging whether a bill can be met. */
    public static Map<ResourceId, Long> combined(List<Candidate> candidates) {
        Map<ResourceId, Long> totals = new LinkedHashMap<>();
        candidates.forEach(candidate ->
                candidate.contents().forEach((resource, quantity) ->
                        totals.merge(resource, quantity, Math::addExact)));
        return Map.copyOf(totals);
    }

    /**
     * Re-observes one already-authorized container, retaining it even when it is empty.
     *
     * <p>Discovery intentionally hides empty boxes from the player's initial picker. A
     * durable reservation cannot use that rule after withdrawal: taking the last item
     * must not make the bound endpoint disappear on restart. Callers must supply the
     * exact position they already authorized; this method never broadens that authority.</p>
     */
    static Candidate boundCandidate(ServerLevel level, BlockPos position) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (!level.hasChunkAt(position)
                || !(level.getBlockEntity(position) instanceof Container container)) {
            return null;
        }
        return describe(level, position.immutable(), container);
    }

    private static long distanceSquared(BlockPos centre, BlockPos3i cell) {
        long dx = (long) cell.x() - centre.getX();
        long dy = (long) cell.y() - centre.getY();
        long dz = (long) cell.z() - centre.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Map<ResourceId, Long> contents(Container container) {
        Map<ResourceId, Long> contents = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) continue;
            contents.merge(ResourceId.parse(itemId.toString()),
                    (long) stack.getCount(), Math::addExact);
        }
        return contents;
    }

    private static Candidate describe(
            ServerLevel level, BlockPos position, Container container) {
        Map<ResourceId, Long> contents = contents(container);
        ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                level.getBlockEntity(position).getType());
        long capacity = Math.multiplyExact(
                (long) container.getContainerSize(), container.getMaxStackSize());
        return new Candidate(new BlockPos3i(position.getX(), position.getY(), position.getZ()),
                ResourceId.parse(type == null ? "minecraft:chest" : type.toString()),
                contents,
                contents.values().stream().mapToLong(Long::longValue).sum(),
                capacity);
    }
}
