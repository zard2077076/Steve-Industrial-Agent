package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.industrial.IndustrialCompletionReportV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Disposable player-order gate for IPO-02.
 *
 * <p>This is the gate KI-090 asked for: a Composite that starts the way a player starts
 * it, not the way an acceptance fixture hand-builds it.  The fixture only prepares a
 * marked world, a flat site and one chest holding exactly the material the order
 * quotes.  Everything after that — planning, reservation, physical withdrawal, chest
 * and route installation, the real three-stage wrapper, salvage settlement and the
 * completion report — is the production service's own work.</p>
 *
 * <p>The bill is taken from {@link PlayerCompositeOrderService#preview}, never
 * hand-written, so this fixture cannot quietly seed a material the order does not
 * actually charge for.</p>
 */
public final class CompositePlayerOrderAcceptanceFixture {
    private static final Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CompositePlayerOrderAcceptanceFixture.class);
    private static final int TIMEOUT = 12_000;
    private static final int PROGRESS_INTERVAL = 600;

    private final ServerLevel level;
    private final FakePlayer player;
    private final CompositePlayerOrderSpecV1 spec;
    private final BlockPos origin;
    private final BlockPos source;
    private final BlockPos secondarySource;
    private final UUID projectId;
    private final RefusedStart refusal;
    private BlockPos3i blockedCell;
    private final Map<ResourceId, Long> requirements;
    private final Map<BlockPos, BlockState> baseline = new LinkedHashMap<>();
    private final LinkedHashSet<ChunkPos> chunkTickets = new LinkedHashSet<>();
    private int ticks;

    /**
     * Occupies a cell the layout needs, so the order is refused for the most ordinary
     * reason a player will ever hit: the site is not clear.
     *
     * <p>This is deliberately the pre-distribution refusal. Once the layout contract
     * holds, a post-distribution refusal is not reachable on demand — block types,
     * hopper facing, chest contents and region containment are all guaranteed by the
     * time the wrapper is asked. {@code unwind} still covers that path for causes
     * outside the order's control, but it is not what this gate exercises, and claiming
     * otherwise would be a fabricated result.</p>
     */
    public static RefusedStart startRefused(MinecraftServer server, ResourceId orderType) {
        return new CompositePlayerOrderAcceptanceFixture(server, orderType,
                ExecutionMode.DIRECT, true).refusal;
    }

    /** What the player still had after a refused order. */
    public record RefusedStart(
            String code, Map<ResourceId, Long> chestBefore, Map<ResourceId, Long> chestAfter,
            int installedBlocks) {}

    private CompositePlayerOrderAcceptanceFixture(
            MinecraftServer server, ResourceId orderType, ExecutionMode mode) {
        this(server, orderType, mode, false);
    }

    private CompositePlayerOrderAcceptanceFixture(
            MinecraftServer server, ResourceId orderType, ExecutionMode mode, boolean forceRefusal) {
        level = server.overworld();
        // Resolve the same way a player order does, so the gate can drive a derived
        // chain as readily as a reviewed one.
        CompositeOrderResolver.Resolution resolved =
                CompositeOrderResolver.resolve(level, orderType, 1);
        if (!resolved.success()) {
            throw new IllegalStateException(
                    "composite gate could not resolve " + orderType + ": " + resolved.code());
        }
        spec = resolved.spec();
        BlockPos spawn = level.getSharedSpawnPos();
        int invocation = IndustrialPlayerOrderSavedData.forLevel(level).orders().size();
        int x = spawn.getX() + 96 + Math.floorMod(invocation, 4) * 64;
        int z = spawn.getZ() + 96 + Math.floorDiv(invocation, 4) * 64;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 10;
        origin = new BlockPos(x, y, z);
        // North of every layout, for both shapes. A branch/merge site spreads west and
        // east far enough that the old western offset would put the player's own chest
        // inside the order's authorized region.
        source = origin.offset(0, 0, -8);
        // A second container, so the gate exercises reservation across more than one.
        secondarySource = origin.offset(2, 0, -8);
        forceChunks();
        prepareArena();
        markWorld();

        // Quote and order the identity the caller asked for, never the derived spec's
        // synthesised one — that is not a product and cannot be resolved again.
        PlayerCompositeOrderService.PreviewResult preview =
                PlayerCompositeOrderService.preview(level, orderType, origin);
        if (!preview.success()) {
            throw new IllegalStateException("composite preview failed: " + preview.code());
        }
        requirements = preview.requirements();
        // Named coordinates: a wrapper refusal reports a bare BlockPos, and without
        // this the log gives no way to tell which boundary chest it means.
        LOGGER.info("COMPOSITE_PLAYER_ORDER SITE graph={} derived={} origin={} playerChest={} bill={}",
                orderType, resolved.derived(), origin, source, requirements);
        seedSource();

        player = FakePlayerFactory.get(level, new GameProfile(
                UUID.randomUUID(), "SteveCompositeOrderFixture"));
        player.moveTo(source.getX() - 1.5D, source.getY(), source.getZ() + 0.5D, 0, 0);
        player.getAbilities().mayBuild = true;
        Map<ResourceId, Long> before = forceRefusal ? chestTotals(source) : Map.of();
        if (forceRefusal) occupyASiteCell();
        // Both containers, primary first. The bill is split between them, so a single
        // source here would be short by half and the order would refuse.
        var start = PlayerCompositeOrderService.create(
                player, java.util.List.of(source, secondarySource), origin, orderType, mode);
        if (forceRefusal) {
            if (start.success()) {
                throw new IllegalStateException(
                        "composite refusal fixture expected the wrapper to refuse the start");
            }
            refusal = new RefusedStart(start.code(), before, chestTotals(source),
                    countInstalledBlocks());
            projectId = null;
            return;
        }
        refusal = null;
        if (!start.success()) {
            throw new IllegalStateException("composite order fixture start failed: " + start.code());
        }
        projectId = start.projectId();
        dumpBoundaryChests();
    }

    /**
     * Prints what actually landed in every container around the site once the order has
     * distributed its material. The wrappers assert exact per-chest contents and report
     * only a bare coordinate when one is wrong, so this is the difference between a
     * diagnosis and another guess.
     */
    private Map<ResourceId, Long> chestTotals(BlockPos position) {
        Map<ResourceId, Long> totals = new LinkedHashMap<>();
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) return totals;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) continue;
            totals.merge(ResourceId.parse(itemId.toString()), (long) stack.getCount(), Math::addExact);
        }
        return totals;
    }

    /**
     * Puts one item where the wrapper insists nothing may be waiting, so the start is
     * refused only after distribution has already committed every reserved item.
     */
    private void occupyASiteCell() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(
                spec, new BlockPos3i(origin.getX(), origin.getY(), origin.getZ()));
        BlockPos3i target = layout.routes().stream()
                .filter(route -> route.overflow().isPresent())
                .map(route -> route.overflow().orElseThrow())
                .findFirst().orElseThrow();
        blockedCell = target;
        level.setBlockAndUpdate(new BlockPos(target.x(), target.y(), target.z()),
                Blocks.COBBLESTONE.defaultBlockState());
    }

    /** Owned cells the order actually built on, ignoring the cell this fixture blocked. */
    private int countInstalledBlocks() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(
                spec, new BlockPos3i(origin.getX(), origin.getY(), origin.getZ()));
        int installed = 0;
        for (BlockPos3i cell : layout.ownedCells()) {
            if (cell.equals(blockedCell)) continue;
            if (!level.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z())).isAir()) installed++;
        }
        return installed;
    }

    private void dumpBoundaryChests() {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 11; dz++) {
                    BlockPos position = origin.offset(dx, dy, dz);
                    if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) continue;
                    Map<ResourceId, Long> contents = new LinkedHashMap<>();
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);
                        if (stack.isEmpty()) continue;
                        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        if (itemId == null) continue;
                        contents.merge(ResourceId.parse(itemId.toString()),
                                (long) stack.getCount(), Math::addExact);
                    }
                    if (!contents.isEmpty()) {
                        LOGGER.info("COMPOSITE_PLAYER_ORDER CHEST at={} contents={}",
                                position.toShortString(), contents);
                    }
                }
            }
        }
    }

    public static CompositePlayerOrderAcceptanceFixture start(
            MinecraftServer server, ResourceId orderType, ExecutionMode mode) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("CompositePlayerOrderAcceptanceFixture");
        return new CompositePlayerOrderAcceptanceFixture(server, orderType, mode);
    }

    public UUID projectId() { return projectId; }

    /** The player's own material chest, so a reload gate can re-read it later. */
    public BlockPos3i playerChest() {
        return new BlockPos3i(source.getX(), source.getY(), source.getZ());
    }

    /** Returns null while the order is still running. */
    public Result tick() {
        ticks++;
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(projectId).orElse(null);
        if (order == null) return finish(false, "ORDER_DISAPPEARED", null);
        if (ticks > TIMEOUT) {
            return finish(false, "TIMEOUT:phase=" + order.phase() + ":stage=" + order.stage()
                    + ":nodes=" + nodeStatuses(), null);
        }
        // A stalled composite otherwise looks identical to a slow one for the whole
        // timeout. Name the node it is waiting on while it is still waiting.
        if (ticks % PROGRESS_INTERVAL == 0) {
            LOGGER.info("COMPOSITE_PLAYER_ORDER PROGRESS ticks={} phase={} stage={} nodes={}",
                    ticks, order.phase(), order.stage(), nodeStatuses());
        }
        if (order.phase() == IndustrialLifecyclePhase.PAUSED
                || order.phase() == IndustrialLifecyclePhase.FAILED
                || order.phase() == IndustrialLifecyclePhase.CANCELLED) {
            return finish(false, order.phase() + ":" + order.stage(), null);
        }
        IndustrialCompletionReportV1 report = order.report().orElse(null);
        if (report == null) return null;

        if (!report.materialLedgerBalanced()) {
            return finish(false, "MATERIAL_LEDGER_UNBALANCED", report);
        }
        if (!report.plannedMaterials().equals(requirements)) {
            return finish(false, "REPORT_BILL_DIFFERS_FROM_QUOTE", report);
        }
        long producedTarget = report.outputs().getOrDefault(spec.target(), 0L);
        if (report.outputs().size() != 1 || producedTarget != spec.targetQuantity()) {
            return finish(false, "OUTPUT_NOT_EXACTLY_" + spec.target() + "x"
                    + spec.targetQuantity() + ":" + report.outputs(), report);
        }
        long expectedSalvage = spec.intermediateSalvage().values().stream()
                .mapToLong(Long::longValue).sum();
        if (report.salvageTransferred() != expectedSalvage) {
            return finish(false, "SALVAGE_NOT_SETTLED:" + report.salvageTransferred()
                    + "/" + expectedSalvage, report);
        }
        if (report.duplicateWithdrawals() != 0 || report.duplicateReturns() != 0
                || report.duplicateOutputs() != 0 || report.unaccountedItems() != 0
                || report.privateItemsTouched() != 0) {
            return finish(false, "REPORT_COUNTERS_NOT_CLEAN", report);
        }
        String residual = residualOwnedBlock();
        if (residual != null) return finish(false, "OWNED_INFRASTRUCTURE_REMAINS:" + residual, report);
        return finish(true, "COMPOSITE_PLAYER_ORDER_ACCEPTED", report);
    }

    private String nodeStatuses() {
        PlayerCompositeOrderService.StatusResult status =
                PlayerCompositeOrderService.status(player);
        return status.success() ? status.snapshot().nodeStatuses().toString() : status.code();
    }

    private void prepareArena() {
        for (int dx = -26; dx <= 36; dx++) {
            for (int dz = -10; dz <= 40; dz++) {
                BlockPos floor = origin.offset(dx, -1, dz);
                for (int dy = 0; dy <= 6; dy++) capture(floor.above(dy));
                capture(floor);
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 6; dy++) {
                    level.setBlockAndUpdate(floor.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
        }
        clearArenaItemEntities();
    }

    /**
     * Splits the bill across two chests, alternating by resource.
     *
     * <p>It used to go into one, which is also all an order could draw from — and the
     * method threw outright if the bill did not fit. Spreading it means the run only
     * succeeds if reservation really spans both containers: if multi-source ordering
     * regressed, the primary chest is short by half the bill and the gate fails rather
     * than quietly falling back.</p>
     */
    private void seedSource() {
        ChestBlockEntity primary = chestAt(source);
        ChestBlockEntity secondary = chestAt(secondarySource);
        int[] slots = new int[2];
        int index = 0;
        for (Map.Entry<ResourceId, Long> row : requirements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList()) {
            ChestBlockEntity chest = index % 2 == 0 ? primary : secondary;
            int which = index % 2;
            index++;
            long remaining = row.getValue();
            Item item = item(row.getKey());
            if (item == null) throw new IllegalStateException("unregistered fixture item " + row.getKey());
            while (remaining > 0) {
                int portion = (int) Math.min(remaining, item.getMaxStackSize());
                if (slots[which] >= chest.getContainerSize()) {
                    throw new IllegalStateException("composite fixture bill exceeds one chest");
                }
                chest.setItem(slots[which]++, new ItemStack(item, portion));
                remaining -= portion;
            }
        }
        primary.setChanged();
        secondary.setChanged();
    }

    private ChestBlockEntity chestAt(BlockPos position) {
        level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("composite fixture source chest was not created");
        }
        chest.clearContent();
        return chest;
    }

    private void markWorld() {
        PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(level);
        if (data.marker().isEmpty()) {
            data.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                    "world:composite-player-order-acceptance", UUID.randomUUID().toString(),
                    "overworld-pilot-only", "disposable-dedicated-server-fixture",
                    "fixture:composite-player-order", level.getGameTime()));
        }
    }

    private void forceChunks() {
        for (int chunkX = (origin.getX() - 28) >> 4; chunkX <= (origin.getX() + 38) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 12) >> 4; chunkZ <= (origin.getZ() + 42) >> 4; chunkZ++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    level.setChunkForced(chunkX, chunkZ, true);
                    chunkTickets.add(chunk);
                }
            }
        }
    }

    /**
     * Any block the order installed must be gone.  The service clears its own owned
     * cells, so a residual container here means cleanup silently failed rather than the
     * report being wrong.
     */
    private String residualOwnedBlock() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(
                spec, new BlockPos3i(origin.getX(), origin.getY(), origin.getZ()));
        for (BlockPos3i cell : layout.ownedCells()) {
            BlockPos position = new BlockPos(cell.x(), cell.y(), cell.z());
            BlockState state = level.getBlockState(position);
            if (!state.isAir()) {
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                return cell + ":" + (blockId == null ? "unregistered" : blockId);
            }
        }
        return null;
    }

    private Result finish(boolean success, String code, IndustrialCompletionReportV1 report) {
        try {
            PlayerCompositeOrderService.clearServerState();
            baseline.forEach(level::setBlockAndUpdate);
            clearArenaItemEntities();
        } finally {
            chunkTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
            chunkTickets.clear();
        }
        return new Result(success, code, ticks, report);
    }

    private void clearArenaItemEntities() {
        net.minecraft.world.phys.AABB bounds =
                new net.minecraft.world.phys.AABB(origin).inflate(48.0D, 16.0D, 48.0D);
        level.getEntitiesOfClass(ItemEntity.class, bounds, Entity::isAlive).forEach(Entity::discard);
    }

    private void capture(BlockPos position) {
        baseline.computeIfAbsent(position.immutable(), level::getBlockState);
    }

    private static Item item(ResourceId resource) {
        Item value = ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath(resource.namespace(), resource.path()));
        return value == null || value == net.minecraft.world.item.Items.AIR ? null : value;
    }

    public record Result(
            boolean success, String code, int ticks, IndustrialCompletionReportV1 report) {}
}
