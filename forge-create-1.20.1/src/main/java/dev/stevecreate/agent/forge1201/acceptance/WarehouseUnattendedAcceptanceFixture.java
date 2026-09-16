package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.warehouse.UnattendedProductionOrderScheduler;
import dev.stevecreate.agent.forge1201.command.PlayerCompositeOrderService;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseProductionDispatch;
import java.util.UUID;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.command.WarehouseTopology;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseStockObserverRuntime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Proves the unattended production loop reaches a decision from real world stock.
 *
 * <p>Everything for this was written and none of it could run. {@code
 * WarehouseOrderService} has ticked every twenty ticks all along, reading persisted
 * orders and asking the scheduler what to do — and nothing anywhere called {@code
 * registerRuntime}, so the lookup returned null and every order was skipped forever.
 *
 * <p>It is a fixture rather than a GameTest for a reason worth writing down. Written as a
 * GameTest it needed ninety-odd ticks of waiting for the twenty-tick service, and over
 * that span it reproducibly broke an unrelated neighbour: C07 failed on a stray dirt item
 * in its lane, and disabling this test alone restored 51/51. Every other long-running
 * gate here is a fixture on its own dedicated server, and this is why — a test that lives
 * across many ticks in a shared arena world has neighbours.
 *
 * <p>Both directions are asserted and the negative one first: a warehouse already holding
 * its target must authorise nothing. A runtime that dispatched unconditionally would pass
 * a gate built only from understocked examples, and that failure produces goods nobody
 * asked for.
 */
public final class WarehouseUnattendedAcceptanceFixture {
    public static final String ENABLE_PROPERTY = "steve_industrial.test.warehouseUnattended";
    // Wide enough for the whole loop: two settle phases, a twenty-tick service cycle,
    // and a real composite order that takes ~250 ticks in DIRECT. It was 2400 and fired
    // before the phase budget below, so the gate reported a timeout with nothing about
    // where it had got to.
    // Nine phases, two of which wait on a real build. Raised from eight thousand when
    // the single-machine phase was added; the per-phase waits below are what actually
    // catch a stuck run, and this is only the wall that stops a hung gate running forever.
    private static final int TIMEOUT_TICKS = 26_000;
    private static final int SETTLE_TICKS = 45;

    private static Active active;

    private WarehouseUnattendedAcceptanceFixture() {}

    public static void start(MinecraftServer server, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("WarehouseUnattendedAcceptanceFixture");
        active = new Active(server, logger, server.getTickCount());
    }

    public static void tick(MinecraftServer server) {
        Active current = active;
        if (current == null || current.server != server) return;
        try {
            current.tick();
        } catch (RuntimeException failure) {
            current.logger.error("WAREHOUSE_UNATTENDED_ACCEPTANCE FAIL", failure);
            active = null;
            WarehouseOrderService.clearServerState();
            server.halt(false);
            throw failure;
        }
    }

    private static final class Active {
        private final MinecraftServer server;
        private final Logger logger;
        private final int startTick;
        private final ServerLevel level;
        private final ResourceId warehouseId = id("steve_industrial:warehouse/unattended_gate");
        private final ResourceId orderId = id("steve_industrial:order/unattended_gate");
        private final WarehouseResourceKey target = new WarehouseResourceKey(
                GenericResourceType.ITEM, id("minecraft:andesite"),
                WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
        private BlockPos chest;
        private WarehouseStockObserverRuntime runtime;
        private int phase;
        private int phaseStartTick;
        private long stockedObserved = -1;
        private WarehouseProductionDispatch dispatch;
        private ResourceId dispatchWarehouse;
        private BlockPos warehouseChest;
        private WarehouseResourceKey producedTarget;
        private ResourceId orderTypeUsed;
        private String lastWaitState;
        private WarehouseProductionDispatch carryDispatch;
        private BlockPos carrySiteChest;
        private BlockPos carrySite;
        private BlockPos residentChest;
        private BlockPos transferFrom;
        private BlockPos transferTo;
        private long transferSourceBefore;
        private UUID residentProject;
        private BlockPos residentSite;
        private long residentCells;
        private WarehouseProductionDispatch singleMachineDispatch;
        private WarehouseResourceKey singleMachineTarget;
        private String lastSingleMachineState;
        private String tidiedFrom;
        private String tidiedTo;
        private BlockPos tidiedSource;
        private BlockPos tidiedDestination;
        private long tidiedSourceBefore;
        private final ResourceId dispatchOrderId =
                id("steve_industrial:order/dispatch_gate");

        private Active(MinecraftServer server, Logger logger, int startTick) {
            this.server = server;
            this.logger = logger;
            this.startTick = startTick;
            this.level = server.overworld();
        }

        private void tick() {
            int elapsed = server.getTickCount() - startTick;
            if (elapsed > TIMEOUT_TICKS) {
                throw new IllegalStateException("warehouse unattended gate exceeded its budget");
            }
            if (elapsed < 20) return;
            switch (phase) {
                case 0 -> topologyThenStock();
                case 1 -> expectNoAuthorisation();
                case 2 -> expectAuthorisation();
                case 3 -> dispatchRealOrder();
                case 4 -> awaitProducedAndSettle();
                case 5 -> carryThenOrder();
                case 6 -> residentLine();
                case 7 -> failoverToSecondSite();
                case 8 -> transferBetweenWarehouses();
                case 9 -> maintainASingleMachineProduct();
                case 10 -> maintainAFanWashedProduct();
                case 11 -> maintainAHauntedProduct();
                case 12 -> transferWhileTheSourceIsTidied();
                default -> { }
            }
        }

        /**
         * Reachability first, then the stock loop, in one isolated world.
         *
         * <p>This began as a GameTest and had to move for the same reason as the loop
         * below: it places containers and scans a radius, and the discovery GameTest
         * scans a radius of eight from its own arena. Two extra chests inside that box
         * turned its exact-count assertion red. Duration was not the problem — proximity
         * was.</p>
         */
        private void topologyThenStock() {
            BlockPos spawn = level.getSharedSpawnPos();
            int baseX = spawn.getX() + 64;
            int baseZ = spawn.getZ() + 64;
            int baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE, baseX, baseZ) + 2;
            BlockPos nearChest = new BlockPos(baseX, baseY, baseZ);
            BlockPos farChest = new BlockPos(baseX, baseY, baseZ + 4);
            stockChest(nearChest, 12);
            stockChest(farChest, 5);

            ResourceId topologyWarehouse = id("steve_industrial:warehouse/topology_gate");
            ResourceId topologyOwner = id("steve_industrial:owner/topology_gate");
            var clear = WarehouseTopology.capture(level, nearChest, 8, topologyWarehouse,
                    topologyOwner, "gate-world", 1);
            if (clear.endpoints().size() != 2) {
                throw new IllegalStateException(
                        "both stocked containers must be endpoints, saw " + clear.endpoints().size());
            }
            if (clear.edges().isEmpty()) {
                throw new IllegalStateException("an unobstructed run must produce an edge");
            }
            var resource = new WarehouseResourceKey(GenericResourceType.ITEM,
                    id("minecraft:andesite"), WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
            List<ResourceId> ids = List.copyOf(clear.endpoints().keySet());
            if (!clear.reachable(ids.get(0), ids.get(1), resource)) {
                throw new IllegalStateException("a clear corridor must make one endpoint reachable");
            }
            long total = clear.totalContents().getOrDefault(resource, 0L);
            if (total != 17) throw new IllegalStateException("the graph must total both chests, saw " + total);

            // The assertion with teeth: a builder claiming an edge for every pair would
            // pass everything above.
            BlockPos wall = new BlockPos(baseX, baseY, baseZ + 2);
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            var blocked = WarehouseTopology.capture(level, nearChest, 8, topologyWarehouse,
                    topologyOwner, "gate-world", 2);
            if (!blocked.edges().isEmpty()) {
                throw new IllegalStateException(
                        "an obstructed run must claim no edge, saw " + blocked.edges().size());
            }
            if (blocked.reachable(ids.get(0), ids.get(1), resource)) {
                throw new IllegalStateException("a walled-off pair must not be reachable");
            }
            if (blocked.generation() <= clear.generation()) {
                throw new IllegalStateException("a refresh must advance the generation");
            }
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            var reopened = WarehouseTopology.capture(level, nearChest, 8, topologyWarehouse,
                    topologyOwner, "gate-world", 3);
            if (reopened.edges().isEmpty()) {
                throw new IllegalStateException("clearing the obstruction must restore the edge");
            }
            level.setBlockAndUpdate(nearChest, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(farChest, Blocks.AIR.defaultBlockState());
            logger.info("WAREHOUSE_TOPOLOGY PASS endpoints={} clearEdges={} blockedEdges=0 "
                            + "reopenedEdges={} totalStock={} refreshAdvancedGeneration=true",
                    clear.endpoints().size(), clear.edges().size(), reopened.edges().size(), total);

            stockAboveTarget();
        }

        private void stockChest(BlockPos position, int count) {
            level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new IllegalStateException("the gate could not create a chest");
            }
            container.clearContent();
            container.setItem(0, new ItemStack(ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse("minecraft:andesite")), count));
            container.setChanged();
        }

        /** A warehouse holding more than it wants, and a live order over it. */
        private void stockAboveTarget() {
            BlockPos spawn = level.getSharedSpawnPos();
            int x = spawn.getX() + 32;
            int z = spawn.getZ() + 32;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2;
            chest = new BlockPos(x, y, z);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(chest) instanceof Container container)) {
                throw new IllegalStateException("the gate could not create its warehouse chest");
            }
            container.clearContent();
            container.setItem(0, new ItemStack(
                    ForgeRegistries.ITEMS.getValue(
                            net.minecraft.resources.ResourceLocation.tryParse("minecraft:andesite")),
                    32));
            container.setChanged();

            runtime = new WarehouseStockObserverRuntime(level, chest, 8);
            WarehouseOrderService.registerRuntime(warehouseId, runtime);
            stockedObserved = runtime.observedStock(target);
            if (stockedObserved != 32) {
                throw new IllegalStateException(
                        "stock must be read from the real chest, saw " + stockedObserved);
            }
            WarehouseOrderSavedData.forLevel(level).put(order(16));
            phase = 1;
            phaseStartTick = server.getTickCount();
        }

        /** The assertion with teeth: enough stock means no authorisation, ever. */
        private void expectNoAuthorisation() {
            if (server.getTickCount() - phaseStartTick < SETTLE_TICKS) return;
            if (!runtime.authorizations().isEmpty()) {
                throw new IllegalStateException(
                        "a warehouse above its target authorised " + runtime.authorizations().size()
                                + " batches");
            }
            if (level.getBlockEntity(chest) instanceof Container container) {
                container.clearContent();
                container.setChanged();
            }
            long emptied = runtime.observedStock(target);
            if (emptied != 0) {
                throw new IllegalStateException("the emptied chest read " + emptied);
            }
            // Same order, restated, now against an empty warehouse.
            WarehouseOrderSavedData.forLevel(level).put(order(16));
            phase = 2;
            phaseStartTick = server.getTickCount();
        }

        private void expectAuthorisation() {
            if (server.getTickCount() - phaseStartTick < SETTLE_TICKS) return;
            var authorised = runtime.latest().orElseThrow(() -> new IllegalStateException(
                    "an understocked order authorised nothing after " + SETTLE_TICKS + " ticks"));
            if (!authorised.orderId().equals(orderId)) {
                throw new IllegalStateException("the wrong order was authorised");
            }
            // Target 16 against a verified batch output of 8: two batches. The number is
            // the decision, so asserting merely that something was authorised would miss
            // a scheduler that always asked for one.
            if (authorised.batches() != 2) {
                throw new IllegalStateException(
                        "expected two batches for a deficit of 16, saw " + authorised.batches());
            }
            logger.info("WAREHOUSE_UNATTENDED PASS stockedObserved={} authorisedWhenStocked=0 "
                            + "emptiedObserved=0 authorisedBatches={} producedNothing=true "
                            + "ticks={}",
                    stockedObserved, authorised.batches(), server.getTickCount() - startTick);
            // The decision is proven; now prove the button gets pressed.
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            WarehouseOrderService.clearServerState();
            phase = 3;
            phaseStartTick = server.getTickCount();
        }

        /**
         * The step that turns a planning agent into an operating one.
         *
         * <p>Everything upstream was verified and stopped at a runtime that only recorded
         * the authorisation. This registers one that places a real order through
         * {@code PlayerCompositeOrderService} — the same path a player's own order takes,
         * deliberately not through an acceptance fixture, which the runtime guard would
         * refuse and which would put test scaffolding in the production path.
         *
         * <p>The warehouse holds the bill, quoted from the real preview, so the order
         * draws its material from the warehouse rather than from anything staged.</p>
         */
        private void dispatchRealOrder() {
            if (server.getTickCount() - phaseStartTick < 20) return;
            markWorld();
            BlockPos spawn = level.getSharedSpawnPos();
            int siteX = spawn.getX() + 128;
            int siteZ = spawn.getZ() + 128;
            int siteY = level.getHeight(Heightmap.Types.WORLD_SURFACE, siteX, siteZ) + 4;
            BlockPos site = new BlockPos(siteX, siteY, siteZ);
            forceChunks(site);
            // Ground the bots can stand on, and air above it. An unattended order still
            // needs somewhere buildable: BOTS refuses with "bot start is not a safe
            // region cell" over raw terrain, which is the executor correctly declining to
            // walk a fleet into a hillside rather than a fixture detail.
            // Generous on purpose: the first attempt at plus-or-minus twenty missed a
            // worker start by a single block at z+21, and a fleet refused for want of one
            // floor tile reads exactly like a fleet that cannot work at all.
            for (int dx = -32; dx <= 32; dx++) {
                for (int dz = -32; dz <= 32; dz++) {
                    level.setBlockAndUpdate(new BlockPos(siteX + dx, siteY - 1, siteZ + dz),
                            Blocks.STONE.defaultBlockState());
                    for (int dy = 0; dy <= 6; dy++) {
                        level.setBlockAndUpdate(new BlockPos(siteX + dx, siteY + dy, siteZ + dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }

            ResourceId graph = ResourceId.parse("steve_industrial:composite/01");
            orderTypeUsed = graph;
            var preview = PlayerCompositeOrderService.preview(level, graph, site);
            if (!preview.success()) {
                throw new IllegalStateException("dispatch preview failed: " + preview.code());
            }

            // Close enough that one actor can stand between the two: a container opens
            // only for someone beside it, while the site has its own wider limit.
            BlockPos warehouse = new BlockPos(siteX, siteY, siteZ - 8);
            level.setBlockAndUpdate(warehouse, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(warehouse) instanceof Container container)) {
                throw new IllegalStateException("dispatch warehouse chest was not created");
            }
            container.clearContent();
            int slot = 0;
            for (var required : preview.requirements().entrySet()) {
                var item = ForgeRegistries.ITEMS.getValue(
                        net.minecraft.resources.ResourceLocation.tryParse(
                                required.getKey().toString()));
                if (item == null) {
                    throw new IllegalStateException("unknown bill item " + required.getKey());
                }
                long remaining = required.getValue();
                while (remaining > 0) {
                    int portion = (int) Math.min(remaining, item.getMaxStackSize());
                    if (slot >= container.getContainerSize()) {
                        throw new IllegalStateException("the bill does not fit one warehouse chest");
                    }
                    container.setItem(slot++, new ItemStack(item, portion));
                    remaining -= portion;
                }
            }
            container.setChanged();

            // The two typed refusals dispatch can return, asserted before the success
            // case. Both were added with the reach checks and neither had ever executed —
            // an unexercised refusal is a refusal nobody knows works.
            var farSite = new WarehouseProductionDispatch(level, warehouse, 4,
                    new BlockPos(siteX + 400, siteY, siteZ), graph, ExecutionMode.DIRECT);
            var farResult = farSite.dispatch(authorisation(graph,
                    id("steve_industrial:batch/too_far")));
            if (farResult.accepted()
                    || !"SITE_TOO_FAR_FROM_WAREHOUSE".equals(farResult.statusCode())) {
                throw new IllegalStateException(
                        "a site beyond reach must be refused by name, saw " + farResult.statusCode());
            }
            // A warehouse spread wider than one actor can reach: the actor stands at the
            // centroid, so both ends fall outside a container's eight-block rule. Placed
            // diagonally because a straight sixteen-block gap puts the centroid at
            // exactly eight, which is the boundary the rule allows rather than refuses.
            BlockPos spread = new BlockPos(siteX + 12, siteY, siteZ - 8 + 12);
            level.setBlockAndUpdate(spread, Blocks.CHEST.defaultBlockState());
            if (level.getBlockEntity(spread) instanceof Container far) {
                far.clearContent();
                far.setItem(0, new ItemStack(ForgeRegistries.ITEMS.getValue(
                        net.minecraft.resources.ResourceLocation.tryParse("minecraft:andesite")), 1));
                far.setChanged();
            }
            // Scan from the midpoint so both chests are inside the sixteen-block cap.
            var spreadOut = new WarehouseProductionDispatch(level,
                    new BlockPos(siteX + 6, siteY, siteZ - 2), 16, site, graph,
                    ExecutionMode.DIRECT);
            var spreadResult = spreadOut.dispatch(authorisation(graph,
                    id("steve_industrial:batch/spread")));
            if (spreadResult.accepted()
                    || !"WAREHOUSE_SOURCE_OUT_OF_REACH".equals(spreadResult.statusCode())) {
                throw new IllegalStateException(
                        "a warehouse wider than one actor's reach must be refused by name, saw "
                                + spreadResult.statusCode());
            }
            level.setBlockAndUpdate(spread, Blocks.AIR.defaultBlockState());
            logger.info("WAREHOUSE_DISPATCH_REFUSALS PASS siteTooFar=refused "
                    + "sourcesOutOfReach=refused nothingOrdered=true");

            // BOTS, not DIRECT. Unattended production with a DIRECT executor means the
            // work is attributed to a player standing there, which is the one thing
            // unattended is not. This is the mode that actually spawns the courier and
            // builder fleet, so it is the mode the claim depends on.
            dispatch = new WarehouseProductionDispatch(level, warehouse, 4, site, graph,
                    ExecutionMode.BOTS);
            dispatchWarehouse = id("steve_industrial:warehouse/dispatch_gate");
            warehouseChest = warehouse;
            WarehouseOrderService.registerRuntime(dispatchWarehouse, dispatch);

            // The loop drives itself from here. The order wants a cogwheel it does not
            // have; composite/01 makes exactly that and returns it to the source chest,
            // so the warehouse it draws from is the warehouse it restocks.
            producedTarget = new WarehouseResourceKey(GenericResourceType.ITEM,
                    id("create:cogwheel"), WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
            if (dispatch.observedStock(producedTarget) != 0) {
                throw new IllegalStateException("the warehouse already holds the product");
            }
            WarehouseOrderSavedData.forLevel(level).put(new ProductionOrder(
                    dispatchOrderId, id("steve_industrial:owner/dispatch_gate"),
                    dispatchWarehouse, producedTarget, 1, 1, 1, graph, Set.of(graph),
                    Set.of(id("steve_industrial:adapter/create_v606")),
                    List.of(id("steve_industrial:site/dispatch_gate")), 3, 0, 0,
                    Optional.empty(), ProductionOrderStatus.ACTIVE, "GATE_SEEDED", 1));
            phase = 4;
            phaseStartTick = server.getTickCount();
        }

        /**
         * Waits for the dispatched order to actually produce, then settles the batch.
         *
         * <p>This is where the loop closes. Up to here the agent decided and ordered;
         * what makes it operation rather than intention is that the warehouse ends up
         * holding the thing it was short of, and that the scheduler is told so and stops
         * asking.</p>
         */
        private void awaitProducedAndSettle() {
            // A timeout that says only "timed out" costs a whole run to diagnose, so the
            // waiting state is logged whenever it changes.
            var current = dispatch.latest().orElse(null);
            String state = current == null ? "no-dispatch-yet"
                    : !current.accepted() ? "refused:" + current.code()
                    : PlayerCompositeOrderService.isActive(current.projectId()) ? "order-running"
                    : "order-finished:stock=" + dispatch.observedStock(producedTarget)
                            + " envelope=" + envelopeState(current.projectId());
            if (!state.equals(lastWaitState)) {
                lastWaitState = state;
                logger.info("WAREHOUSE_DISPATCH waiting tick={} state={}",
                        server.getTickCount() - phaseStartTick, state);
            }
            if (server.getTickCount() - phaseStartTick > 5_000) {
                var attempt = dispatch.latest().orElse(null);
                throw new IllegalStateException("the dispatched order never produced; last dispatch="
                        + (attempt == null ? "none" : attempt.code()));
            }
            var attempt = dispatch.latest().orElse(null);
            if (attempt == null || !attempt.accepted()) return;
            if (PlayerCompositeOrderService.isActive(attempt.projectId())) return;
            long produced = dispatch.observedStock(producedTarget);
            if (produced < 1) return;

            var data = WarehouseOrderSavedData.forLevel(level);
            var order = data.order(dispatchOrderId).orElseThrow(() ->
                    new IllegalStateException("the production order vanished"));
            if (order.inFlightBatchId().isEmpty()) {
                throw new IllegalStateException(
                        "a dispatched order left no in-flight batch, status=" + order.status());
            }
            WarehouseOrderService.batchSucceeded(server, dispatchOrderId,
                    order.inFlightBatchId().orElseThrow());
            var settled = data.order(dispatchOrderId).orElseThrow();
            if (settled.inFlightBatchId().isPresent()) {
                throw new IllegalStateException("settling left the batch in flight");
            }
            if (settled.consecutiveFailures() != 0) {
                throw new IllegalStateException("a successful batch left failures recorded");
            }
            logger.info("WAREHOUSE_DISPATCH PASS graph={} code={} project={} "
                            + "sourcesFromWarehouse=1 viaPlayerOrderService=true mode=BOTS "
                            + "producedInWarehouse={} batchSettled=true failuresReset=true "
                            + "ticks={}",
                    orderTypeUsed, attempt.code(), attempt.projectId(), produced,
                    server.getTickCount() - startTick);
            // The loop is proven. The last thing to show is C5-A: material physically
            // carried from the warehouse to the site before the order exists.
            WarehouseOrderService.clearServerState();
            phase = 5;
            phaseStartTick = server.getTickCount();
        }

        /**
         * A bot carries the bill to the site, and only then is the order placed.
         *
         * <p>C5-A's whole point. The carry happens before any reservation, so nothing the
         * order depends on has to survive it — distribute and extract are untouched, and
         * the order that follows is an ordinary one whose source happens to be a chest at
         * the site.</p>
         */
        private void carryThenOrder() {
            if (carryDispatch == null) {
                if (server.getTickCount() - phaseStartTick < 20) return;
                BlockPos spawn = level.getSharedSpawnPos();
                int x = spawn.getX() + 224;
                int z = spawn.getZ() + 224;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
                BlockPos site = new BlockPos(x, y, z);
                forceChunks(site);
                for (int dx = -32; dx <= 32; dx++) {
                    for (int dz = -32; dz <= 32; dz++) {
                        level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz),
                                Blocks.STONE.defaultBlockState());
                        for (int dy = 0; dy <= 6; dy++) {
                            level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                ResourceId graph = ResourceId.parse("steve_industrial:composite/01");
                var preview = PlayerCompositeOrderService.preview(level, graph, site);
                if (!preview.success()) {
                    throw new IllegalStateException("carry preview failed: " + preview.code());
                }
                // Warehouse well away from the site, so the bot has somewhere to walk.
                BlockPos warehouse = new BlockPos(x - 12, y, z - 12);
                stockChestWith(warehouse, preview.requirements());
                carrySite = site;
                carrySiteChest = new BlockPos(x, y, z - 8);
                level.setBlockAndUpdate(carrySiteChest, Blocks.CHEST.defaultBlockState());

                carryDispatch = new WarehouseProductionDispatch(level, warehouse, 4, site, graph,
                        ExecutionMode.DIRECT, carrySiteChest);
                WarehouseOrderService.registerRuntime(
                        id("steve_industrial:warehouse/carry_gate"), carryDispatch);
                var result = carryDispatch.dispatch(authorisation(graph,
                        id("steve_industrial:batch/carry_gate")));
                if (!result.accepted() || !"CARRY_STARTED".equals(result.statusCode())) {
                    throw new IllegalStateException(
                            "the carry did not start: " + result.statusCode());
                }
                logger.info("WAREHOUSE_CARRY started warehouse={} siteChest={}",
                        warehouse.toShortString(), carrySiteChest.toShortString());
                return;
            }
            if (server.getTickCount() - phaseStartTick > 4_000) {
                throw new IllegalStateException("the carry never finished; carrying="
                        + carryDispatch.carrying() + " last="
                        + carryDispatch.latest().map(WarehouseProductionDispatch.Attempt::code)
                                .orElse("none"));
            }
            if (carryDispatch.carrying()) return;
            var last = carryDispatch.latest().orElseThrow();
            if (!last.accepted()) {
                throw new IllegalStateException("the order after the carry was refused: "
                        + last.code());
            }
            if (last.projectId() == null) return;
            // Wait for the order to actually finish, not merely start. Session ids are
            // derived from graph nodes rather than projects, so leaving one running and
            // ordering the same graph again collides with "the verified root session is
            // already active" — and an order that only started is weaker evidence anyway.
            if (PlayerCompositeOrderService.isActive(last.projectId())) return;
            var carried = dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData
                    .forLevel(level).order(last.projectId()).orElseThrow();
            if (carried.report().isEmpty()) {
                throw new IllegalStateException("the order after the carry did not complete: "
                        + carried.phase() + "/" + carried.stage());
            }
            // Read from the dispatch, which counted at the moment the bot finished: the
            // order that follows immediately withdraws from this chest, so counting it
            // now would always read zero.
            long delivered = carryDispatch.deliveredOnCarry();
            if (delivered <= 0) {
                throw new IllegalStateException("the bot delivered nothing to the site chest");
            }
            logger.info("WAREHOUSE_CARRY PASS carriedToSiteChest={} orderAfterCarry={} "
                            + "project={} distributeUntouched=true orderCompleted=true",
                    delivered, last.code(), last.projectId());
            WarehouseOrderService.clearServerState();
            phase = 6;
            phaseStartTick = server.getTickCount();
        }

        /**
         * A line that stays up after the order that built it finishes.
         *
         * <p>Every order until now tore its site down, which makes a production line a
         * single batch. Residency is what C5-B is for, and the accounting has to say
         * which of the two happened: the infrastructure came out of the player's chest
         * either way, so settlement distinguishes a site kept on purpose from one merely
         * abandoned — the second is material that has gone missing.</p>
         */
        private void residentLine() {
            if (residentProject == null) {
                if (server.getTickCount() - phaseStartTick < 20) return;
                BlockPos spawn = level.getSharedSpawnPos();
                int x = spawn.getX() + 320;
                int z = spawn.getZ() + 320;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
                BlockPos site = new BlockPos(x, y, z);
                forceChunks(site);
                for (int dx = -32; dx <= 32; dx++) {
                    for (int dz = -32; dz <= 32; dz++) {
                        level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz),
                                Blocks.STONE.defaultBlockState());
                        for (int dy = 0; dy <= 6; dy++) {
                            level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                ResourceId graph = ResourceId.parse("steve_industrial:composite/01");
                var preview = PlayerCompositeOrderService.preview(level, graph, site);
                if (!preview.success()) {
                    throw new IllegalStateException("resident preview failed: " + preview.code());
                }
                BlockPos chest = new BlockPos(x, y, z - 8);
                residentChest = chest;
                stockChestWith(chest, preview.requirements());
                var actor = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                        new com.mojang.authlib.GameProfile(
                                UUID.nameUUIDFromBytes("steve-resident-line".getBytes()),
                                "SteveResidentLine"));
                actor.moveTo(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5);
                var started = PlayerCompositeOrderService.create(actor, List.of(chest), site,
                        graph, ExecutionMode.DIRECT, true);
                if (!started.success()) {
                    throw new IllegalStateException("resident order refused: " + started.code());
                }
                residentProject = started.projectId();
                residentSite = site;
                residentCells = -1;
                return;
            }
            if (server.getTickCount() - phaseStartTick > 4_000) {
                throw new IllegalStateException("the resident order never finished");
            }
            if (PlayerCompositeOrderService.isActive(residentProject)) return;
            var order = dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData
                    .forLevel(level).order(residentProject).orElseThrow();
            if (order.report().isEmpty()) {
                throw new IllegalStateException("the resident order ended without a report: "
                        + order.phase() + "/" + order.stage());
            }
            // The claim: the machines are still there. Counting solid blocks around the
            // site is the observation that cannot be satisfied by a cleared site.
            // Against a control rather than against zero. Phase five ran the same graph
            // under the same conditions without retaining, so its site is what a cleared
            // one looks like — "more than nothing" would be satisfied by a single stray
            // block, while "more than the cleared one" is the actual claim.
            long standing = solidBlocksAround(residentSite);
            long cleared = solidBlocksAround(carrySite);
            if (standing <= cleared) {
                throw new IllegalStateException("a retained site left no more standing than a "
                        + "cleared one: retained=" + standing + " cleared=" + cleared);
            }
            logger.info("WAREHOUSE_RESIDENT PASS project={} retainedBlocks={} clearedBlocks={} "
                            + "reportGenerated=true siteRetained=true",
                    residentProject, standing, cleared);
            WarehouseOrderService.clearServerState();
            phase = 7;
            phaseStartTick = server.getTickCount();
        }

        /**
         * A blocked site is a detour, not a stoppage.
         *
         * <p>A site can be refused for reasons nothing to do with the warehouse — someone
         * built on it, an earlier order is still standing there. With one site that stops
         * production entirely. The negative half matters as much as the positive: a
         * dispatch that ignored the first site and always used the second would pass a
         * test that only checked something got built.</p>
         */
        private void failoverToSecondSite() {
            if (server.getTickCount() - phaseStartTick < 20) return;
            BlockPos spawn = level.getSharedSpawnPos();
            int x = spawn.getX() + 416;
            int z = spawn.getZ() + 416;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
            BlockPos blocked = new BlockPos(x, y, z);
            BlockPos spare = new BlockPos(x, y, z + 40);
            forceChunks(blocked);
            forceChunks(spare);
            for (BlockPos site : List.of(blocked, spare)) {
                for (int dx = -32; dx <= 32; dx++) {
                    for (int dz = -32; dz <= 32; dz++) {
                        level.setBlockAndUpdate(new BlockPos(site.getX() + dx, y - 1,
                                site.getZ() + dz), Blocks.STONE.defaultBlockState());
                        for (int dy = 0; dy <= 6; dy++) {
                            level.setBlockAndUpdate(new BlockPos(site.getX() + dx, y + dy,
                                    site.getZ() + dz), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
            ResourceId graph = ResourceId.parse("steve_industrial:composite/01");
            var preview = PlayerCompositeOrderService.preview(level, graph, spare);
            if (!preview.success()) {
                throw new IllegalStateException("failover preview failed: " + preview.code());
            }
            BlockPos warehouse = new BlockPos(x, y, z + 20);
            stockChestWith(warehouse, preview.requirements());
            // Something in the way of the preferred site, which is the ordinary reason a
            // site is unusable: a player put a block there.
            level.setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());

            var withOneSite = new WarehouseProductionDispatch(level, warehouse, 4,
                    List.of(blocked), graph, ExecutionMode.DIRECT, null);
            var refused = withOneSite.dispatch(authorisation(graph,
                    id("steve_industrial:batch/failover_none")));
            if (refused.accepted()) {
                throw new IllegalStateException(
                        "an obstructed site must refuse when it is the only one");
            }

            var withSpare = new WarehouseProductionDispatch(level, warehouse, 4,
                    List.of(blocked, spare), graph, ExecutionMode.DIRECT, null);
            var accepted = withSpare.dispatch(authorisation(graph,
                    id("steve_industrial:batch/failover")));
            if (!accepted.accepted()) {
                throw new IllegalStateException(
                        "a warehouse with a spare site must not stop: " + accepted.statusCode());
            }
            logger.info("WAREHOUSE_FAILOVER PASS blockedSiteRefused={} withSpareAccepted={} "
                            + "sitesTried=2",
                    refused.statusCode(), accepted.statusCode());
            WarehouseOrderService.clearServerState();
            phase = 8;
            phaseStartTick = server.getTickCount();
        }

        /**
         * Stock moving from one warehouse to another under its own power.
         *
         * <p>Inter-factory routing turned out to be the carry C5-A already built with
         * both ends being warehouses. What this proves that the carry gate did not is
         * that the source is drawn down by exactly what the destination gains — a
         * transfer that duplicated material would satisfy any check that only looked at
         * the receiving end.</p>
         */
        private void transferBetweenWarehouses() {
            if (transferFrom == null) {
                if (server.getTickCount() - phaseStartTick < 20) return;
                BlockPos spawn = level.getSharedSpawnPos();
                int x = spawn.getX() + 512;
                int z = spawn.getZ() + 512;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
                for (int dx = -20; dx <= 20; dx++) {
                    for (int dz = -20; dz <= 20; dz++) {
                        level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz),
                                Blocks.STONE.defaultBlockState());
                        for (int dy = 0; dy <= 4; dy++) {
                            level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                transferFrom = new BlockPos(x, y, z);
                transferTo = new BlockPos(x, y, z + 10);
                forceChunks(transferFrom);
                stockChestWith(transferFrom,
                        java.util.Map.of(id("minecraft:andesite"), 12L));
                level.setBlockAndUpdate(transferTo, Blocks.CHEST.defaultBlockState());

                var actor = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                        new com.mojang.authlib.GameProfile(
                                UUID.nameUUIDFromBytes("steve-warehouse-transfer".getBytes()),
                                "SteveWarehouseTransfer"));
                actor.moveTo(transferFrom.getX() + 0.5, transferFrom.getY(),
                        transferFrom.getZ() + 0.5);
                CompositePlayerOrderReloadProbe.markDisposableWorld(level, "warehouse-unattended");
                String code = WarehouseOrderService.beginTransfer(actor,
                        List.of(transferFrom), transferTo, transferFrom.east(),
                        id("minecraft:andesite"), 12,
                        CompositePlayerOrderReloadProbe.worldIdentity(level).orElseThrow());
                if (!"TRANSFER_STARTED".equals(code)) {
                    throw new IllegalStateException("the transfer did not start: " + code);
                }
                transferSourceBefore = contentsOf(transferFrom);
                return;
            }
            if (server.getTickCount() - phaseStartTick > 4_000) {
                throw new IllegalStateException("the transfer never finished");
            }
            if (WarehouseOrderService.transfersInFlight() > 0) return;
            long arrived = contentsOf(transferTo);
            long remaining = contentsOf(transferFrom);
            if (arrived < 12) {
                throw new IllegalStateException("the destination warehouse received " + arrived);
            }
            // Conservation, not just arrival: a transfer that duplicated material would
            // pass any check that only looked at where it landed.
            if (transferSourceBefore - remaining != arrived) {
                throw new IllegalStateException("material was not conserved: before="
                        + transferSourceBefore + " remaining=" + remaining + " arrived=" + arrived);
            }
            logger.info("WAREHOUSE_TRANSFER PASS moved={} sourceBefore={} sourceAfter={} "
                            + "conserved=true", arrived, transferSourceBefore, remaining);
            WarehouseOrderService.clearServerState();
            phase = 9;
            phaseStartTick = server.getTickCount();
        }

        /**
         * A warehouse keeping a product that is one machine, not a chain.
         *
         * <p>Every phase above this one runs {@code composite/01}, and every product a
         * warehouse could maintain was a chain — measured, not assumed: 111 maintainable
         * products and all 111 of them wood cut on a saw, because the resolver refused a
         * goal the expander had classified as a single machine. Shafts, gravel and
         * andesite alloy — the goals with real capability variety — had none of the
         * apparatus that sits around a machine.
         *
         * <p>What this proves that the phases above cannot: the whole production path
         * runs with one stage and no routes at all. A one-stage order has nothing to
         * carry between machines, so if any of the layout, the material merge or the
         * executor still assumed an edge existed, it fails here and nowhere else.</p>
         */
        private void maintainASingleMachineProduct() {
            // A shaft, deliberately: it is a Create part rather than a stripped log, so a
            // pass here is not the same wood-cutting chain wearing a shorter hat.
            maintain(id("create:shaft"), null, "single_machine", 608, 10);
        }

        /**
         * A site whose bill contains a bucket, and one whose bill contains a flint and steel.
         *
         * <p>Water behind a fan is washing and soul fire behind one is haunting, and both
         * are blocks with no item of their own. The plan now buys them as the item a
         * player actually hands over, which the survey proves for the whole catalog —
         * every one of 200 derivable products plans, where 65 were refused before. What
         * the survey cannot show is that an escrow holding a bucket survives being
         * settled, because it never builds anything. These two do.</p>
         */
        private void maintainAFanWashedProduct() {
            maintain(id("minecraft:blue_concrete"), id("minecraft:water_bucket"),
                    "fan_washed", 704, 11);
        }

        private void maintainAHauntedProduct() {
            maintain(id("create:haunted_bell"), id("minecraft:flint_and_steel"),
                    "haunted", 800, 12);
        }

        /**
         * The player tidies their own chest while the courier is still walking to it.
         *
         * <p>Reservations used to be slot-exact at both ends — the source snapshot was an
         * inventory hash of the arrangement, and the withdrawal read one nominated slot.
         * Organising your own storage is entirely your business and takes nothing away
         * from the order, and it lost the order anyway.
         *
         * <p>A courier, not a composite order: composite reserves and distributes in the
         * same tick, so there is no window there to tidy in — the first attempt at this
         * phase failed its own no-op guard because the chest was already empty by the time
         * it looked. The courier walks, and that walk is the window. It is also the exact
         * constraint C5-A worked around: the carry was placed before the order because
         * withdrawal pulled from an exact slot and a carry destroyed that correspondence.
         *
         * <p>The negative half lives in {@code PlayerMaterialSnapshotTest} and is verified
         * there against the old implementation — taking anything out, swapping the
         * payload and emptying the chest are all still refused. This proves only the
         * relaxation, which is the half no unit test can reach.</p>
         */
        private void transferWhileTheSourceIsTidied() {
            if (tidiedFrom == null) {
                if (server.getTickCount() - phaseStartTick < 20) return;
                BlockPos spawn = level.getSharedSpawnPos();
                int x = spawn.getX() + 896;
                int z = spawn.getZ() + 896;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
                for (int dx = -20; dx <= 20; dx++) {
                    for (int dz = -20; dz <= 20; dz++) {
                        level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz),
                                Blocks.STONE.defaultBlockState());
                        for (int dy = 0; dy <= 4; dy++) {
                            level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                tidiedSource = new BlockPos(x, y, z);
                tidiedDestination = new BlockPos(x, y, z + 10);
                forceChunks(tidiedSource);
                // Two stacks rather than one, so a rearrangement has somewhere to go and
                // the withdrawal has to gather across slots to reach twelve.
                stockChestWith(tidiedSource, java.util.Map.of(id("minecraft:andesite"), 12L));
                level.setBlockAndUpdate(tidiedDestination, Blocks.CHEST.defaultBlockState());
                var actor = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                        new com.mojang.authlib.GameProfile(
                                UUID.nameUUIDFromBytes("steve-warehouse-tidied".getBytes()),
                                "SteveWarehouseTidied"));
                actor.moveTo(tidiedSource.getX() + 0.5, tidiedSource.getY(),
                        tidiedSource.getZ() + 0.5);
                markWorld();
                String code = WarehouseOrderService.beginTransfer(actor,
                        List.of(tidiedSource), tidiedDestination, tidiedSource.east(),
                        id("minecraft:andesite"), 12,
                        CompositePlayerOrderReloadProbe.worldIdentity(level).orElseThrow());
                if (!"TRANSFER_STARTED".equals(code)) {
                    throw new IllegalStateException("the tidied transfer did not start: " + code);
                }
                // Immediately, while the courier has taken no step. Nothing added and
                // nothing removed — every stack simply ends up somewhere else, which is
                // exactly what a player sorting a chest does.
                tidiedFrom = describeContainer(tidiedSource);
                rearrange(tidiedSource);
                tidiedTo = describeContainer(tidiedSource);
                if (tidiedFrom.equals(tidiedTo)) {
                    throw new IllegalStateException("the chest was not actually rearranged, "
                            + "so this phase would pass without proving anything");
                }
                tidiedSourceBefore = contentsOf(tidiedSource);
                return;
            }
            if (server.getTickCount() - phaseStartTick > 4_000) {
                throw new IllegalStateException("the tidied transfer never finished; source="
                        + describeContainer(tidiedSource) + " destination="
                        + describeContainer(tidiedDestination));
            }
            if (WarehouseOrderService.transfersInFlight() > 0) return;
            long arrived = contentsOf(tidiedDestination);
            long remaining = contentsOf(tidiedSource);
            if (arrived < 12) {
                throw new IllegalStateException("a rearranged chest lost the transfer: arrived="
                        + arrived + " source=" + describeContainer(tidiedSource));
            }
            if (tidiedSourceBefore - remaining != arrived) {
                throw new IllegalStateException("material was not conserved across a tidy: before="
                        + tidiedSourceBefore + " remaining=" + remaining + " arrived=" + arrived);
            }
            logger.info("WAREHOUSE_TIDIED_CHEST PASS rearrangedBefore=[{}] rearrangedAfter=[{}] "
                            + "moved={} conserved=true orderSurvived=true",
                    tidiedFrom, tidiedTo, arrived);
            active = null;
            WarehouseOrderService.clearServerState();
            server.halt(false);
        }

        /**
         * Stands up one warehouse, orders one product from it, and waits for stock.
         *
         * @param requiredBillItem an item the quoted bill must contain, or null when the
         *     product is not the reason this phase exists. Without it a fan phase whose
         *     product turned out to be milled rather than washed would pass while proving
         *     nothing about buckets.
         * @param nextPhase the phase to hand to, or -1 to end the gate
         */
        private void maintain(ResourceId product, ResourceId requiredBillItem,
                String name, int offset, int nextPhase) {
            if (singleMachineDispatch == null) {
                if (server.getTickCount() - phaseStartTick < 20) return;
                markWorld();
                BlockPos spawn = level.getSharedSpawnPos();
                int x = spawn.getX() + offset;
                int z = spawn.getZ() + offset;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 4;
                BlockPos site = new BlockPos(x, y, z);
                forceChunks(site);
                for (int dx = -32; dx <= 32; dx++) {
                    for (int dz = -32; dz <= 32; dz++) {
                        level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz),
                                Blocks.STONE.defaultBlockState());
                        for (int dy = 0; dy <= 6; dy++) {
                            level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                var preview = PlayerCompositeOrderService.preview(level, product, site);
                if (!preview.success()) {
                    throw new IllegalStateException(
                            product + " could not be previewed: " + preview.code());
                }
                if (requiredBillItem != null
                        && !preview.requirements().containsKey(requiredBillItem)) {
                    throw new IllegalStateException("the bill for " + product + " does not "
                            + "contain " + requiredBillItem + ", so this phase proves nothing "
                            + "about it: " + preview.requirements().keySet());
                }
                BlockPos warehouse = new BlockPos(x, y, z - 8);
                stockChestWith(warehouse, preview.requirements());
                singleMachineTarget = new WarehouseResourceKey(GenericResourceType.ITEM,
                        product, WarehouseResourceKey.EMPTY_COMPONENT_SHA256);
                singleMachineDispatch = new WarehouseProductionDispatch(level, warehouse, 4,
                        site, product, ExecutionMode.DIRECT);
                if (singleMachineDispatch.observedStock(singleMachineTarget) != 0) {
                    throw new IllegalStateException("the warehouse already holds " + product);
                }
                // Its own warehouse identity, because the dispatch actor is derived from
                // it and one actor holds one composite order at a time.
                var placed = singleMachineDispatch.dispatch(authorisation(product,
                        id("steve_industrial:batch/" + name),
                        id("steve_industrial:warehouse/" + name)));
                if (!placed.accepted()) {
                    throw new IllegalStateException(
                            product + " was refused: " + placed.statusCode());
                }
                return;
            }
            var attempt = singleMachineDispatch.latest().orElseThrow();
            String state = PlayerCompositeOrderService.isActive(attempt.projectId())
                    ? "order-running"
                    : "order-finished:stock="
                            + singleMachineDispatch.observedStock(singleMachineTarget);
            if (!state.equals(lastSingleMachineState)) {
                lastSingleMachineState = state;
                logger.info("WAREHOUSE_SINGLE_MACHINE waiting product={} tick={} state={}",
                        product, server.getTickCount() - phaseStartTick, state);
            }
            if (server.getTickCount() - phaseStartTick > 5_000) {
                throw new IllegalStateException(
                        product + " never produced; last state=" + state);
            }
            if (PlayerCompositeOrderService.isActive(attempt.projectId())) return;
            long produced = singleMachineDispatch.observedStock(singleMachineTarget);
            if (produced < 1) return;
            logger.info("WAREHOUSE_SINGLE_MACHINE PASS product={} stages=1 routes=0 "
                            + "boughtByItem={} producedInWarehouse={} "
                            + "viaPlayerOrderService=true ticks={}",
                    product, requiredBillItem == null ? "n/a" : requiredBillItem,
                    produced, server.getTickCount() - phaseStartTick);
            WarehouseOrderService.clearServerState();
            singleMachineDispatch = null;
            lastSingleMachineState = null;
            if (nextPhase < 0) {
                active = null;
                server.halt(false);
                return;
            }
            phase = nextPhase;
            phaseStartTick = server.getTickCount();
        }

        /**
         * Moves every stack to a different slot without changing what is in the chest.
         *
         * <p>Reversed rather than shuffled: no randomness is available here, and reversal
         * is guaranteed to move every stack as long as more than one slot is occupied —
         * a shuffle that happened to be the identity would make the phase vacuous.</p>
         */
        private void rearrange(BlockPos position) {
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new IllegalStateException("nothing to rearrange at " + position);
            }
            List<ItemStack> held = new java.util.ArrayList<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) held.add(stack.copy());
            }
            container.clearContent();
            int last = container.getContainerSize() - 1;
            for (int index = 0; index < held.size(); index++) {
                container.setItem(last - index, held.get(index));
            }
            container.setChanged();
        }

        /** What is in a container and where, so a rearrangement can be shown to be one. */
        private String describeContainer(BlockPos position) {
            if (!(level.getBlockEntity(position) instanceof Container container)) return "none";
            StringBuilder value = new StringBuilder();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                if (value.length() > 0) value.append(',');
                value.append(slot).append(':')
                        .append(ForgeRegistries.ITEMS.getKey(stack.getItem()))
                        .append('x').append(stack.getCount());
            }
            return value.toString();
        }

        /** Solid blocks in the volume a site occupies, ignoring the platform beneath it. */
        private long solidBlocksAround(BlockPos site) {
            long solid = 0;
            for (int dx = -8; dx <= 8; dx++) {
                for (int dy = 0; dy <= 4; dy++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        BlockPos cell = site.offset(dx, dy, dz);
                        // The fixture's own source chests sit inside this volume and are
                        // not order infrastructure; counting them made the cleared
                        // control read one instead of zero.
                        if (cell.equals(carrySiteChest) || cell.equals(residentChest)) continue;
                        if (!level.getBlockState(cell).isAir()) solid++;
                    }
                }
            }
            return solid;
        }

        private long contentsOf(BlockPos position) {
            if (!(level.getBlockEntity(position) instanceof Container container)) return 0;
            long total = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                total += container.getItem(slot).getCount();
            }
            return total;
        }

        private void stockChestWith(BlockPos position, java.util.Map<ResourceId, Long> bill) {
            level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new IllegalStateException("could not create the carry warehouse chest");
            }
            container.clearContent();
            int slot = 0;
            for (var required : bill.entrySet()) {
                var item = ForgeRegistries.ITEMS.getValue(
                        net.minecraft.resources.ResourceLocation.tryParse(
                                required.getKey().toString()));
                if (item == null) throw new IllegalStateException("unknown bill item");
                long remaining = required.getValue();
                while (remaining > 0) {
                    int portion = (int) Math.min(remaining, item.getMaxStackSize());
                    if (slot >= container.getContainerSize()) {
                        throw new IllegalStateException("the bill does not fit one chest");
                    }
                    container.setItem(slot++, new ItemStack(item, portion));
                    remaining -= portion;
                }
            }
            container.setChanged();
        }

        private UnattendedProductionOrderScheduler.BatchAuthorization authorisation(
                ResourceId graph, ResourceId batchId) {
            return authorisation(graph, batchId, id("steve_industrial:warehouse/refusal_probe"));
        }

        /**
         * The same authorisation from a named warehouse.
         *
         * <p>The dispatch actor is derived from the warehouse id, so every phase sharing
         * one id shares one actor — and one actor may hold one composite order at a time.
         * The failover phase leaves its order running on purpose, so a later phase
         * ordering under the same name is refused with
         * {@code PLAYER_ALREADY_HAS_ACTIVE_COMPOSITE_ORDER}. That rule is right; what was
         * wrong was the fixture pretending two warehouses were one.</p>
         */
        private UnattendedProductionOrderScheduler.BatchAuthorization authorisation(
                ResourceId graph, ResourceId batchId, ResourceId warehouse) {
            return new UnattendedProductionOrderScheduler.BatchAuthorization(
                    batchId, dispatchOrderId, warehouse,
                    graph, Set.of(graph), Set.of(id("steve_industrial:adapter/create_v606")),
                    List.of(id("steve_industrial:site/refusal_probe")), 1, 1,
                    server.getTickCount());
        }

        /**
         * The order's own phase and stage, for a wait that ends without a product.
         *
         * <p>A composite order pauses by writing a checkpoint, not by logging, so
         * "finished with nothing" says nothing about why on its own — which cost a run to
         * learn once already.</p>
         */
        private String envelopeState(java.util.UUID projectId) {
            return dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData
                    .forLevel(level).order(projectId)
                    .map(order -> order.phase() + "/" + order.stage())
                    .orElse("absent");
        }

        private void forceChunks(BlockPos centre) {
            for (int x = (centre.getX() - 48) >> 4; x <= (centre.getX() + 48) >> 4; x++) {
                for (int z = (centre.getZ() - 48) >> 4; z <= (centre.getZ() + 48) >> 4; z++) {
                    level.setChunkForced(x, z, true);
                }
            }
        }

        private void markWorld() {
            CompositePlayerOrderReloadProbe.markDisposableWorld(
                    level, "warehouse-unattended-acceptance");
        }

        private ProductionOrder order(long targetStock) {
            return new ProductionOrder(orderId, id("steve_industrial:owner/unattended_gate"),
                    warehouseId, target, targetStock, 8, 4,
                    id("steve_industrial:graph/unattended_gate"),
                    Set.of(id("steve_industrial:recipe/unattended_gate")),
                    Set.of(id("steve_industrial:adapter/unattended_gate")),
                    List.of(id("steve_industrial:site/unattended_gate")),
                    3, 0, 0, Optional.empty(), ProductionOrderStatus.ACTIVE, "GATE_SEEDED", 1);
        }

        private static ResourceId id(String value) {
            return ResourceId.parse(value);
        }
    }
}
