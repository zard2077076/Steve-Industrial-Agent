package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Prepares only a disposable world for a real Composite player order.
 *
 * <p>This class intentionally never calls {@link PlayerCompositeOrderService#create}.
 * It resolves the live bill, places two named source chests and clears/floors the bounded
 * site so the subsequent {@code /steveagent composite create} command still crosses the
 * real player packet, permission, reservation and execution path. It is registered only
 * in the development runtime and is not a production feature.</p>
 */
public final class CompositeClientAcceptanceFixture {
    /** The two source chests stay outside every reviewed Composite layout. */
    public static final int PRIMARY_SOURCE_Z_OFFSET = -8;
    public static final int SECONDARY_SOURCE_Z_OFFSET = -6;
    /** Bounded marker copied to the client so a runner cannot reuse old source chests. */
    public static final String FIXTURE_MARKER_KEY = "steve_agent_composite_fixture";
    private static final int MAX_SOURCE_SLOTS = 27;

    private CompositeClientAcceptanceFixture() {}

    public static Prepared prepare(
            ServerLevel level, ResourceId orderType, BlockPos origin, String fixtureNonce) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("CompositeClientAcceptanceFixture");
        if (fixtureNonce == null || !fixtureNonce.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("COMPOSITE_FIXTURE_NONCE_INVALID");
        }
        if (PilotWorldMarkerSavedData.forLevel(level).marker().isEmpty()) {
            throw new IllegalStateException("WORLD_NOT_AUTHORIZED: run /industrialagent setup mark-test-world first");
        }
        CompositeOrderResolver.Resolution resolved = CompositeOrderResolver.resolve(
                level, orderType, 1);
        if (!resolved.success()) {
            throw new IllegalArgumentException("COMPOSITE_FIXTURE_ORDER_REFUSED:" + resolved.code());
        }
        CompositePlayerOrderSpecV1 spec = resolved.spec();
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(spec,
                new BlockPos3i(origin.getX(), origin.getY(), origin.getZ()));
        if (!layout.matchesDeclaredInfrastructure()) {
            throw new IllegalStateException("COMPOSITE_FIXTURE_INFRASTRUCTURE_DIVERGED");
        }
        BlockPos primary = source(origin, PRIMARY_SOURCE_Z_OFFSET);
        BlockPos secondary = source(origin, SECONDARY_SOURCE_Z_OFFSET);
        if (layout.ownedCells().stream().map(CompositeClientAcceptanceFixture::block)
                .anyMatch(position -> position.equals(primary) || position.equals(secondary))) {
            throw new IllegalStateException("COMPOSITE_FIXTURE_SOURCE_OVERLAPS_SITE");
        }

        PlayerCompositeOrderService.PreviewResult preview =
                PlayerCompositeOrderService.preview(level, orderType, origin);
        if (!preview.success()) {
            throw new IllegalArgumentException("COMPOSITE_FIXTURE_PREVIEW_REFUSED:" + preview.code());
        }
        if (preview.requirements().size() > MAX_SOURCE_SLOTS * 2) {
            throw new IllegalArgumentException("COMPOSITE_FIXTURE_BILL_EXCEEDS_TWO_CHESTS");
        }

        prepareArena(level, layout, origin);
        ChestBlockEntity first = placeChest(level, primary);
        ChestBlockEntity second = placeChest(level, secondary);
        seed(first, second, preview.requirements());
        long preparedAt = level.getGameTime();
        mark(first, "primary", orderType, spec.target(), spec.targetQuantity(), preparedAt,
                fixtureNonce);
        mark(second, "secondary", orderType, spec.target(), spec.targetQuantity(), preparedAt,
                fixtureNonce);
        return new Prepared(orderType, origin.immutable(), primary.immutable(), secondary.immutable(),
                preview.requirements(), preview.expectedSalvage(), spec.target(),
                spec.targetQuantity(), resolved.derived(), fixtureNonce);
    }

    public static BlockPos primarySource(BlockPos origin) {
        return source(origin, PRIMARY_SOURCE_Z_OFFSET);
    }

    public static BlockPos secondarySource(BlockPos origin) {
        return source(origin, SECONDARY_SOURCE_Z_OFFSET);
    }

    private static BlockPos source(BlockPos origin, int zOffset) {
        return origin.offset(0, 0, zOffset);
    }

    private static void prepareArena(
            ServerLevel level, CompositeSiteLayout layout, BlockPos origin) {
        BlockPos3i minimum = layout.regionMinimum();
        BlockPos3i maximum = layout.regionMaximum();
        for (int x = minimum.x() - 1; x <= maximum.x() + 1; x++) {
            for (int z = minimum.z() - 1; z <= maximum.z() + 1; z++) {
                BlockPos floor = new BlockPos(x, origin.getY() - 1, z);
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int y = origin.getY(); y <= maximum.y() + 1; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static ChestBlockEntity placeChest(ServerLevel level, BlockPos position) {
        level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("COMPOSITE_FIXTURE_SOURCE_CHEST_MISSING:" + position);
        }
        chest.clearContent();
        return chest;
    }

    private static void seed(
            ChestBlockEntity primary, ChestBlockEntity secondary,
            Map<ResourceId, Long> requirements) {
        int primarySlot = 0;
        int secondarySlot = 0;
        int index = 0;
        for (Map.Entry<ResourceId, Long> row : requirements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList()) {
            Item item = item(row.getKey());
            if (item == null) throw new IllegalArgumentException(
                    "COMPOSITE_FIXTURE_ITEM_UNREGISTERED:" + row.getKey());
            ChestBlockEntity chest = index++ % 2 == 0 ? primary : secondary;
            long remaining = row.getValue();
            while (remaining > 0) {
                int slot = chest == primary ? primarySlot++ : secondarySlot++;
                if (slot >= MAX_SOURCE_SLOTS) {
                    throw new IllegalArgumentException("COMPOSITE_FIXTURE_CHEST_SLOT_BUDGET");
                }
                int amount = (int) Math.min(remaining, item.getMaxStackSize());
                chest.setItem(slot, new ItemStack(item, amount));
                remaining -= amount;
            }
        }
        primary.setChanged();
        secondary.setChanged();
    }

    private static void mark(
            ChestBlockEntity chest,
            String role,
            ResourceId orderType,
            ResourceId target,
            long quantity,
            long preparedAt,
            String fixtureNonce) {
        CompoundTag marker = new CompoundTag();
        marker.putString("role", role);
        marker.putString("orderType", orderType.toString());
        marker.putString("target", target.toString());
        marker.putLong("quantity", quantity);
        marker.putLong("preparedAt", preparedAt);
        marker.putString("nonce", fixtureNonce);
        chest.getPersistentData().put(FIXTURE_MARKER_KEY, marker);
        chest.setChanged();
    }

    private static Item item(ResourceId resource) {
        Item value = ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        resource.namespace(), resource.path()));
        return value == null || value == net.minecraft.world.item.Items.AIR ? null : value;
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    public record Prepared(
            ResourceId orderType,
            BlockPos origin,
            BlockPos primarySource,
            BlockPos secondarySource,
            Map<ResourceId, Long> requirements,
            Map<ResourceId, Long> expectedSalvage,
            ResourceId target,
            long targetQuantity,
            boolean derived,
            String fixtureNonce) {
        public Prepared {
            requirements = Map.copyOf(new LinkedHashMap<>(requirements));
            expectedSalvage = Map.copyOf(new LinkedHashMap<>(expectedSalvage));
            if (fixtureNonce == null || !fixtureNonce.matches("[a-f0-9]{32}")) {
                throw new IllegalArgumentException("fixture nonce is invalid");
            }
        }
    }
}
