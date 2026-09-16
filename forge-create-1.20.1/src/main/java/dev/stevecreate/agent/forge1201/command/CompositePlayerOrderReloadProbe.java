package dev.stevecreate.agent.forge1201.command;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.player.MaterialLedgerProjection;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.stevecreate.agent.core.industrial.CompositeResumePlanV1;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The read-only handles a reload gate needs from the production order path.
 *
 * <p>The gate has to start a genuine order and then, in a second JVM, inspect the same
 * ledger the service uses.  Those live behind package-private state, so this exposes
 * exactly the three questions the gate asks — what the player's chest holds, how much
 * the ledger has withdrawn, and whether any run is still active — rather than widening
 * the service's own surface.</p>
 */
public final class CompositePlayerOrderReloadProbe {
    public static final ResourceId DEFAULT_ORDER_TYPE =
            ResourceId.parse("steve_industrial:composite/01");

    private CompositePlayerOrderReloadProbe() {}

    /**
     * Starts one real Composite order, then leaves unreserved surplus in the player's
     * chest.
     *
     * <p>The order withdraws exactly what it reserved, which empties a chest seeded to
     * the bill and makes "the chest did not change" a comparison of nothing against
     * nothing.  Surplus added after reservation is material the order has no claim on,
     * so a reloaded process that re-withdrew anything would visibly take from it.</p>
     */
    public static StartedOrder startOrder(MinecraftServer server) {
        ServerLevel level = server.overworld();
        var spec = CompositePlayerOrderCatalogV1.find(DEFAULT_ORDER_TYPE).orElseThrow();
        CompositePlayerOrderAcceptanceFixture prepared =
                CompositePlayerOrderAcceptanceFixture.start(
                        server, DEFAULT_ORDER_TYPE, ExecutionMode.DIRECT);
        BlockPos3i chest = prepared.playerChest();
        addUnreservedSurplus(level, chest);
        return new StartedOrder(prepared.projectId(), spec.orderType(), ExecutionMode.DIRECT,
                chest);
    }

    private static void addUnreservedSurplus(ServerLevel level, BlockPos3i chest) {
        if (!(level.getBlockEntity(new BlockPos(chest.x(), chest.y(), chest.z()))
                instanceof Container container)) {
            throw new IllegalStateException("composite reload probe found no player chest");
        }
        for (SurplusStack surplus : SURPLUS) {
            var item = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.parse(surplus.itemId()));
            if (item == null) {
                throw new IllegalStateException("unknown surplus item " + surplus.itemId());
            }
            int slot = firstEmptySlot(container);
            if (slot < 0) throw new IllegalStateException("player chest has no room for surplus");
            container.setItem(slot, new ItemStack(item, surplus.count()));
        }
        container.setChanged();
    }

    private static int firstEmptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** Deliberately the same identities the order deals in, so a replay would take them. */
    private static final java.util.List<SurplusStack> SURPLUS = java.util.List.of(
            new SurplusStack("minecraft:oak_log", 4),
            new SurplusStack("create:shaft", 2),
            new SurplusStack("minecraft:chest", 3));

    private record SurplusStack(String itemId, int count) {}

    /** Exact item totals in one container, keyed by registry id. */
    public static Map<String, Long> chestContents(ServerLevel level, BlockPos3i position) {
        Map<String, Long> contents = new LinkedHashMap<>();
        if (!(level.getBlockEntity(new BlockPos(position.x(), position.y(), position.z()))
                instanceof Container container)) {
            return contents;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) continue;
            contents.merge(itemId.toString(), (long) stack.getCount(), Math::addExact);
        }
        return contents;
    }

    /**
     * How much the durable ledger has taken out of the player's containers.
     *
     * <p>Uses the same projection the completion report does. Deriving it separately is
     * how the gate and the report could have disagreed about the charge while each
     * looked correct on its own.</p>
     */
    public static long withdrawnTotal(ServerLevel level, UUID projectId) {
        return PlayerMaterialSavedData.forLevel(level).entry(projectId)
                .map(MaterialLedgerProjection::withdrawnTotal).orElse(0L);
    }

    /**
     * Cancels a paused order in a process that never started it, as a player would.
     *
     * <p>Uses the same public entry point the command does, so the gate cannot pass
     * through a path players do not have.</p>
     */
    public static CancelOutcome cancelAfterRestart(MinecraftServer server, UUID projectId) {
        ServerLevel level = server.overworld();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes("steve-reload-cancel".getBytes()),
                "SteveCompositeReloadCancel"));
        PlayerCompositeOrderService.StartResult result =
                PlayerCompositeOrderService.cancelAsOwner(player, projectId);
        return new CancelOutcome(result.success(), result.code());
    }

    /**
     * Marks a disposable world as authorised, for a fixture in another package.
     *
     * <p>The marker's saved data is package-private and stays that way — widening a
     * production class's visibility so a test can reach it is how test needs end up
     * shaping production APIs. This is a narrow entry on a class that already exists to
     * support acceptance, and it refuses to touch a world that is already marked.</p>
     */
    public static void markDisposableWorld(ServerLevel level, String identity) {
        PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(level);
        if (data.marker().isPresent()) return;
        data.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                "world:" + identity, UUID.randomUUID().toString(),
                "overworld-pilot-only", "disposable-dedicated-server-fixture",
                "fixture:" + identity, level.getGameTime()));
    }

    /**
     * The authorised world's identity, for a caller outside this package.
     *
     * <p>Narrow on purpose, like {@link #markDisposableWorld}: the saved data stays
     * package-private rather than being opened up so another package can read one field.
     * Empty when the world was never marked, which every order path already refuses on.</p>
     */
    public static java.util.Optional<String> worldIdentity(ServerLevel level) {
        return PilotWorldMarkerSavedData.forLevel(level).marker()
                .map(PilotWorldMarkerSavedData.Marker::worldIdentity);
    }

    public record CancelOutcome(boolean accepted, String code) {}

    /**
     * Resumes a paused order in a process that never started it.
     *
     * <p>The FakePlayer here is not the one that placed the order, deliberately: after a
     * restart there is no reason the same player object exists, and resumption must work
     * from the persisted envelope rather than from an identity that happens to match.</p>
     */
    public static ResumeOutcome resumeAfterRestart(
            MinecraftServer server, UUID projectId, ExecutionMode mode) {
        ServerLevel level = server.overworld();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes("steve-reload-resume".getBytes()),
                "SteveCompositeReloadResume"));
        PlayerCompositeOrderService.StartResult result =
                PlayerCompositeOrderService.resumeAsOwner(player, projectId, mode);
        return new ResumeOutcome(result.success(), result.code());
    }

    public record ResumeOutcome(boolean accepted, String code) {}

    /** Where the site says an interrupted run can pick up, without acting on it. */
    public static Optional<CompositeResumePlanV1> resumePlan(
            MinecraftServer server, UUID projectId) {
        return PlayerCompositeOrderService.resumePlan(server.overworld(), projectId);
    }

    /** Whether the service is still driving a wrapper for this project in this process. */
    public static boolean hasActiveRun(UUID projectId) {
        return PlayerCompositeOrderService.isActive(projectId);
    }

    public record StartedOrder(
            UUID projectId,
            ResourceId orderType,
            ExecutionMode mode,
            BlockPos3i sourcePosition) {
        public StartedOrder {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(orderType, "orderType");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
        }
    }
}
