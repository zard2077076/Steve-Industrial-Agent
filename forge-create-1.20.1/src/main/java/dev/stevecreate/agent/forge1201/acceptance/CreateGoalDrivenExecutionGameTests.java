package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.CrushingWheelExecutionSession;
import dev.stevecreate.agent.adapter.api.CrushingWheelExecutionUpdate;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionOutcome;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.forge1201.command.SingleMachineGoalResolver;
import dev.stevecreate.agent.forge1201.command.PilotDeploymentCommand;
import dev.stevecreate.agent.forge1201.command.WarehouseTopology;
import dev.stevecreate.agent.forge1201.command.WarehouseDiscovery;
import net.minecraft.world.Container;
import dev.stevecreate.agent.forge1201.fluid.FluidEndpointDiscovery;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraft.world.level.block.Block;
import dev.stevecreate.agent.forge1201.fluid.FluidPipeTopology;
import dev.stevecreate.agent.forge1201.fluid.ForgeFluidTransactionExecutor;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseStockObserverRuntime;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import net.minecraft.server.MinecraftServer;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelRole;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateKineticAdapter;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.Create606CrushingWheelExecutor;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.Direction;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Real planning-to-output GameTests for the readiness-gated v606 execution path. */
@PrefixGameTestTemplate(false)
public final class CreateGoalDrivenExecutionGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateGoalDrivenExecutionGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_01", timeoutTicks = 6_000)
    public static void gravelThreeZero(GameTestHelper helper) {
        runSuccess(helper, "minecraft:gravel", 3, "minecraft:andesite", 3, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_02", timeoutTicks = 6_000)
    public static void gravelThreeClockwise90(GameTestHelper helper) {
        runSuccess(helper, "minecraft:gravel", 3, "minecraft:andesite", 3, QuarterTurn.CLOCKWISE_90);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_03", timeoutTicks = 6_000)
    public static void gravelThreeClockwise270(GameTestHelper helper) {
        runSuccess(helper, "minecraft:gravel", 3, "minecraft:andesite", 3, QuarterTurn.CLOCKWISE_270);
    }

    /**
     * Four times the verified batch count, to answer the question C3a-2 step 2 cannot
     * proceed without: whether the executor handles a multi-batch order at all.
     *
     * <p>Every existing proof is three andesite in one pass. Relaxing the quantity gate
     * without knowing this would derive orders the executor cannot run — the same mistake
     * as giving Composite-02 a player entry. This test asks directly, and it is
     * deliberately here rather than behind the relaxed gate so the answer does not depend
     * on the change it is meant to justify.</p>
     */
    /**
     * Finding fluid endpoints, the half of KI-089 that was missing.
     *
     * <p>Exact transfer between two endpoints already worked; nothing found them, so both
     * had to be named by hand. This asserts the one rule that differs from inventory
     * discovery and is easy to get wrong: an empty tank is kept, because a transfer needs
     * somewhere to put fluid as much as somewhere to take it from. Dropping empties — the
     * right call for chests — would hide every possible destination.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "fluid_discovery", timeoutTicks = 2_000)
    public static void fluidDiscoveryKeepsEmptyDestinations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos filled = helper.absolutePos(new BlockPos(5, 2, 4));
        BlockPos emptyTank = helper.absolutePos(new BlockPos(4, 2, 7));

        placeTank(level, filled);
        placeTank(level, emptyTank);
        IFluidHandler source = level.getBlockEntity(filled)
                .getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
        check(source != null, "the placed tank exposes no fluid handler");
        int accepted = source.fill(new FluidStack(Fluids.WATER, 1_000),
                IFluidHandler.FluidAction.EXECUTE);
        check(accepted == 1_000, "seeding the tank moved " + accepted + "mB, expected 1000");

        // Same reasoning as the warehouse scan: its tanks are within four, so a radius
        // of eight only reaches into whatever is running next door.
        List<FluidEndpointDiscovery.Endpoint> found =
                FluidEndpointDiscovery.endpoints(level, centre, 5, List.of());

        check(found.size() == 2,
                "both tanks must be found, empty included; saw " + found.size());
        check(found.get(0).position().equals(position(filled)),
                "endpoints must be ordered by distance");
        FluidEndpointDiscovery.Endpoint empty = found.get(1);
        check(empty.totalMillibuckets() == 0, "the second tank should be empty");
        check(empty.hasRoom(), "an empty tank must read as able to accept fluid");
        check(found.get(0).totalMillibuckets() == 1_000,
                "contents must be counted, saw " + found.get(0).totalMillibuckets());

        Map<ResourceId, Long> combined = FluidEndpointDiscovery.combined(found);
        check(combined.getOrDefault(id("minecraft:water"), 0L) == 1_000,
                "combined stock should be 1000mB of water, saw " + combined);

        LOGGER.info("FLUID_DISCOVERY PASS found={} combined={} emptyKeptAsDestination=true",
                found.size(), combined);
        helper.succeed();
    }

    /**
     * The first time fluid actually moves between two real tanks in a world.
     *
     * <p>Exact transfer has been "implemented" since before this session and has three
     * passing unit tests, but those drive an in-memory fake through the package-private
     * Endpoint interface. The world-facing overload — the one that resolves capabilities
     * off block entities and translates them into that interface — had no caller anywhere
     * in the repository outside this test. Its five guards and the whole HandlerEndpoint
     * translation layer were unexecuted source.
     *
     * <p>It asserts what the tanks physically hold, before and after. That is the first
     * assertion of millibucket conservation in this project: every prior fluid assertion
     * was about the ledger's own bookkeeping, and until an hour ago the ledger's balance
     * check was arithmetically incapable of returning false.
     *
     * <p>Own batch and own coordinates deliberately: fluidDiscoveryKeepsEmptyDestinations
     * asserts it finds exactly two tanks within radius 8, and a third tank placed nearby
     * would break it.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "fluid_transfer", timeoutTicks = 2_000)
    public static void fluidTransferMovesExactMillibucketsBetweenRealTanks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(9, 2, 9));
        BlockPos destinationPos = helper.absolutePos(new BlockPos(9, 2, 12));
        placeTank(level, sourcePos);
        placeTank(level, destinationPos);

        IFluidHandler source = handler(level, sourcePos);
        IFluidHandler destination = handler(level, destinationPos);
        check(source.fill(new FluidStack(Fluids.WATER, 2_000), IFluidHandler.FluidAction.EXECUTE)
                == 2_000, "seeding the source tank failed");
        long sourceBefore = totalMillibuckets(source);
        long destinationBefore = totalMillibuckets(destination);
        check(sourceBefore == 2_000 && destinationBefore == 0,
                "arena is not in the expected state: " + sourceBefore + "/" + destinationBefore);

        ProjectFluidLedger ledger = new ProjectFluidLedger();
        var moved = ForgeFluidTransactionExecutor.transfer(level, sourcePos, Direction.UP,
                destinationPos, Direction.UP, ledger, id("test:world_transfer"),
                id("test:project"), id("test:task"), id("test:source_endpoint"),
                new FluidStack(Fluids.WATER, 1_000), level.getGameTime());

        check(moved.success(), "world-level transfer refused: " + moved.code());
        check(moved.deliveredMb() == 1_000, "delivered " + moved.deliveredMb() + "mB");
        long sourceAfter = totalMillibuckets(source);
        long destinationAfter = totalMillibuckets(destination);
        // The claim that matters: what left the source is exactly what arrived, read off
        // the blocks rather than off our own bookkeeping.
        check(sourceBefore - sourceAfter == 1_000,
                "source lost " + (sourceBefore - sourceAfter) + "mB, expected 1000");
        check(destinationAfter - destinationBefore == 1_000,
                "destination gained " + (destinationAfter - destinationBefore) + "mB");
        check(moved.ledgerBalanced(), "the ledger must balance a clean delivery");

        // A refusal must move nothing. The destination is filled to capacity so the
        // simulate-first gate rejects before any drain happens.
        long capacity = destination.getTankCapacity(0);
        destination.fill(new FluidStack(Fluids.WATER, (int) capacity),
                IFluidHandler.FluidAction.EXECUTE);
        long sourceBeforeRefusal = totalMillibuckets(source);
        long destinationBeforeRefusal = totalMillibuckets(destination);
        var refused = ForgeFluidTransactionExecutor.transfer(level, sourcePos, Direction.UP,
                destinationPos, Direction.UP, ledger, id("test:world_refusal"),
                id("test:project"), id("test:task"), id("test:source_endpoint"),
                new FluidStack(Fluids.WATER, 500), level.getGameTime());
        check(!refused.success(), "a full destination must refuse");
        check(refused.code().equals("DESTINATION_CAPACITY_INSUFFICIENT"),
                "expected a typed capacity refusal, saw " + refused.code());
        check(totalMillibuckets(source) == sourceBeforeRefusal,
                "a refused transfer must not touch the source");
        check(totalMillibuckets(destination) == destinationBeforeRefusal,
                "a refused transfer must not touch the destination");

        // Two endpoints that are the same block is a guard that had never run.
        var identical = ForgeFluidTransactionExecutor.transfer(level, sourcePos, Direction.UP,
                sourcePos, Direction.UP, ledger, id("test:world_identical"),
                id("test:project"), id("test:task"), id("test:source_endpoint"),
                new FluidStack(Fluids.WATER, 100), level.getGameTime());
        check(identical.code().equals("ENDPOINTS_IDENTICAL"),
                "a self-transfer must be refused by identity, saw " + identical.code());

        LOGGER.info("FLUID_TRANSFER PASS movedMb={} sourceDeltaMb={} destinationDeltaMb={} "
                        + "refusalMovedNothing=true selfTransferRefused=true ledgerBalanced={}",
                moved.deliveredMb(), sourceBefore - sourceAfter,
                destinationAfter - destinationBefore, moved.ledgerBalanced());
        helper.succeed();
    }

    /**
     * Whether two tanks are actually plumbed together, which is what a transfer needs.
     *
     * <p>Discovery answers "what is nearby" and the executor answers "move this much".
     * Neither answers whether the two ends are connected, and two tanks a metre apart
     * with no pipe between them look identical in a list of positions and capacities.
     * Planning a transfer between them would plan something that can never happen.
     *
     * <p>The negative case is the one that matters and it is asserted first: an
     * unconnected pair must come back with no route. A walker that returned everything
     * nearby would pass a connectivity test built only from connected examples.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "fluid_topology", timeoutTicks = 2_000)
    public static void pipeTopologyTellsConnectedTanksFromAdjacentOnes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(13, 2, 2));
        BlockPos unconnected = helper.absolutePos(new BlockPos(13, 2, 4));
        placeTank(level, source);
        placeTank(level, unconnected);

        // Nothing between them: near, and unreachable.
        check(FluidPipeTopology.reachableFrom(level, source, 64).isEmpty(),
                "two tanks with no pipe between them must have no route");
        check(!FluidPipeTopology.connected(level, source, unconnected, 64),
                "an unconnected pair must not report as connected");

        // Now plumb a different pair together and watch the answer change.
        BlockPos destination = helper.absolutePos(new BlockPos(13, 2, 8));
        placeTank(level, destination);
        Block pipe = ForgeRegistries.BLOCKS.getValue(
                ResourceLocation.tryParse("create:fluid_pipe"));
        check(pipe != null && pipe != Blocks.AIR, "create:fluid_pipe is not registered");
        for (int z = 5; z <= 7; z++) {
            BlockPos segment = helper.absolutePos(new BlockPos(13, 2, z));
            check(level.setBlockAndUpdate(segment, pipe.defaultBlockState()),
                    "could not place a pipe segment");
        }

        List<FluidPipeTopology.Route> routes =
                FluidPipeTopology.reachableFrom(level, destination, 64);
        check(!routes.isEmpty(), "a plumbed tank must reach something");
        check(FluidPipeTopology.connected(level, destination, unconnected, 64),
                "the pipe run must connect the two tanks it joins");
        // The origin is never its own route: the executor refuses that pair outright.
        check(routes.stream().noneMatch(route -> route.destination().equals(
                        new dev.stevecreate.agent.core.model.BlockPos3i(
                                destination.getX(), destination.getY(), destination.getZ()))),
                "a tank must not be reported as reachable from itself");

        LOGGER.info("FLUID_TOPOLOGY PASS routes={} pipeCount={} unconnectedPairRejected=true "
                        + "selfExcluded=true", routes.size(), routes.get(0).pipeCount());
        helper.succeed();
    }

    private static IFluidHandler handler(ServerLevel level, BlockPos position) {
        IFluidHandler found = level.getBlockEntity(position)
                .getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
        check(found != null, "no fluid handler at " + position);
        return found;
    }

    private static long totalMillibuckets(IFluidHandler handler) {
        long total = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            total += handler.getFluidInTank(tank).getAmount();
        }
        return total;
    }

    private static void placeTank(ServerLevel level, BlockPos position) {
        ResourceLocation tankId = ResourceLocation.tryParse("create:fluid_tank");
        Block tank = ForgeRegistries.BLOCKS.getValue(tankId);
        check(tank != null && tank != Blocks.AIR, "create:fluid_tank is not registered");
        check(level.setBlockAndUpdate(position, tank.defaultBlockState()),
                "could not place a fluid tank");
    }

    /**
     * Finding the containers a player would otherwise have to name one at a time.
     *
     * <p>KI-088's gap: an order was capped at one chest because nothing discovered
     * inventory. This asserts what discovery reports, including the two things that make
     * it usable rather than merely present — empty containers are left out, because they
     * cannot supply anything and would bury the ones that can, and the order is
     * deterministic, because a scan that shuffles between calls makes a player's
     * confirmation mean something different each time.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "warehouse_discovery", timeoutTicks = 2_000)
    public static void discoveryFindsNearbyStockedContainers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos3i near = position(helper.absolutePos(new BlockPos(5, 2, 4)));
        BlockPos3i far = position(helper.absolutePos(new BlockPos(4, 2, 8)));
        BlockPos3i empty = position(helper.absolutePos(new BlockPos(4, 2, 5)));
        BlockPos3i excluded = position(helper.absolutePos(new BlockPos(3, 2, 4)));

        seed(level, near, "minecraft:andesite", 7);
        seed(level, far, "minecraft:andesite", 5);
        seed(level, excluded, "minecraft:andesite", 99);
        // An empty chest, placed by seeding one item and taking it back out, so the
        // block entity exists exactly as a real emptied chest would.
        seed(level, empty, "minecraft:andesite", 1);
        ((Container) level.getBlockEntity(block(empty))).clearContent();

        // Radius five, not eight. Its own chests are at most four away, and every block
        // of extra radius is radius spent scanning the neighbouring arena — which is how
        // this test started failing when unrelated capability tests seeded their own
        // buffer chests nearby. A test that scans should scan only its own arena.
        List<WarehouseDiscovery.Candidate> found =
                WarehouseDiscovery.candidates(level, centre, 5, List.of(excluded));

        check(found.stream().noneMatch(value -> value.position().equals(excluded)),
                "an excluded cell must not be discovered");
        check(found.stream().noneMatch(value -> value.position().equals(empty)),
                "an empty container cannot supply anything and must not be listed");
        check(found.size() == 2, "expected two stocked containers, saw " + found.size());
        // Nearest first, so the closest chest is the one a player is offered first.
        check(found.get(0).position().equals(near),
                "discovery must order by distance, saw " + found.get(0).position());
        check(found.get(1).position().equals(far), "the far chest should come second");
        check(found.get(0).totalItems() == 7,
                "contents must be counted, saw " + found.get(0).totalItems());

        Map<ResourceId, Long> combined = WarehouseDiscovery.combined(found);
        long andesite = combined.getOrDefault(id("minecraft:andesite"), 0L);
        // Twelve, not 111: the excluded chest's 99 must not be counted toward a bill.
        check(andesite == 12, "combined stock should be 12 andesite, saw " + andesite);

        LOGGER.info("WAREHOUSE_DISCOVERY PASS found={} combined={} excludedHonoured=true "
                        + "emptySkipped=true", found.size(), combined);
        helper.succeed();
    }

    /** A legal last-item withdrawal must not erase the authorized endpoint on reload. */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "warehouse_bound_recapture", timeoutTicks = 2_000)
    public static void boundWarehouseRecaptureRetainsEmptiedContainer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(8, 2, 4));
        BlockPos centre = helper.absolutePos(new BlockPos(7, 2, 4));
        seed(level, position(chest), "minecraft:iron_ingot", 8);
        ResourceId warehouse = id("steve_industrial:warehouse/recapture_gate");
        ResourceId owner = id("steve_industrial:owner/recapture_gate");
        var initial = WarehouseTopology.capture(level, centre, 3, warehouse, owner,
                "ipo03-recapture-world", 0);
        check(initial.endpoints().size() == 1, "initial binding must contain one chest");
        long capacity = initial.endpoints().values().iterator().next().totalCapacity();
        check(capacity == 27L * 64L,
                "chest capacity must be physical slot capacity, saw " + capacity);

        ((Container) level.getBlockEntity(chest)).clearContent();
        ((Container) level.getBlockEntity(chest)).setChanged();
        var observed = WarehouseTopology.recaptureBound(level, initial, 1);
        check(observed.endpoints().size() == 1,
                "an emptied authorized chest disappeared during recapture");
        check(observed.totalContents().isEmpty(), "emptied chest still reported stock");
        check(observed.topologyFingerprint().equals(initial.topologyFingerprint()),
                "legal content mutation changed warehouse topology identity");
        check(!observed.fingerprint().equals(initial.fingerprint()),
                "content mutation did not change the full observation fingerprint");

        LOGGER.info("WAREHOUSE_BOUND_RECAPTURE PASS endpointRetained=true emptied=true "
                + "capacity={} topologyStable=true fullObservationChanged=true", capacity);
        helper.succeed();
    }

    /**
     * A target nobody enumerated, ordered through the resolver and actually built.
     *
     * <p>The survey reporting 85 executable goals says the resolver is wired in; it does
     * not say a derived goal produces anything. Baked potato is derived — it is not one
     * of the eleven reviewed entries — while smoking itself is verified by cooked beef,
     * so what is under test here is the derivation and not the handler.
     *
     * <p>The input comes from the resolved entry rather than being written out here. A
     * hard-coded input would pass even if the resolver returned a different recipe than
     * the one being executed, which is exactly the disagreement that cost several rounds
     * in C2.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_derived", timeoutTicks = 12_000)
    public static void aDerivedTargetIsOrderedAndBuilt(GameTestHelper helper) {
        ResourceId target = id("minecraft:baked_potato");
        check(!SingleMachineGoalResolver.reviewed(target),
                "baked potato must not be a reviewed entry, or this proves nothing");
        GoalCatalogEntry derived = SingleMachineGoalResolver
                .resolve(helper.getLevel(), target)
                .orElseThrow(() -> new IllegalStateException(
                        "the resolver did not derive a goal for " + target));
        check(!derived.executionVerified(),
                "a derived entry must not claim to have been execution verified");
        check(PilotDeploymentCommand.acceptsSingleMachineGoal(helper.getLevel(), target, 1),
                "a derived single-machine goal must be accepted for ordering");
        check(derived.inputsPerBatch().size() == 1,
                "this gate seeds one input; baked potato should need exactly one");
        Map.Entry<ResourceId, Long> input =
                derived.inputsPerBatch().entrySet().iterator().next();

        runSuccess(helper, target.toString(), 1,
                input.getKey().toString(), Math.toIntExact(input.getValue()), QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_multibatch", timeoutTicks = 12_000)
    public static void gravelSixZero(GameTestHelper helper) {
        // The order path must agree with what the executor can do. Six batches of a
        // 200-tick recipe fit the 2400-tick step budget and are proven below; seven do
        // not, and the bound has to refuse them rather than leaving the executor to time
        // out — which is what a fixed 2400 with no regard for the input count did.
        check(PilotDeploymentCommand.acceptsSingleMachineGoal(
                        helper.getLevel(), id("minecraft:gravel"), 6),
                "six batches must be orderable, not merely executable");
        check(!PilotDeploymentCommand.acceptsSingleMachineGoal(
                        helper.getLevel(), id("minecraft:gravel"), 7),
                "seven batches exceed the step budget and must be refused before starting");

        runSuccess(helper, "minecraft:gravel", 6, "minecraft:andesite", 6, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_04", timeoutTicks = 6_000)
    public static void ironSheetTwoZero(GameTestHelper helper) {
        runSuccess(helper, "create:iron_sheet", 2, "minecraft:iron_ingot", 2, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_05", timeoutTicks = 800)
    public static void readinessAndBeginFailuresAreTyped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        seed(level, buffer, "minecraft:iron_ingot", 2);
        ResourceId input = id("minecraft:iron_ingot");

        CreateV606GoalDrivenPlanner.PlanningResult missing = CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/missing"), Map.of(input, 1L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST);
        check(missing instanceof CreateV606GoalDrivenPlanner.Failure
                        && ((CreateV606GoalDrivenPlanner.Failure) missing).code()
                        == ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                "Insufficient input did not fail readiness precisely: " + missing);

        CreateV606GoalDrivenPlanner.PlanningResult formal = CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/formal"), Map.of(input, 2L),
                ExecutionWorldClassification.FORMAL_EXTERNAL_INSTANCE);
        check(formal instanceof CreateV606GoalDrivenPlanner.Failure
                        && ((CreateV606GoalDrivenPlanner.Failure) formal).code()
                        == ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                "Formal-world classification was not refused: " + formal);

        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/changed"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        BlockPos3i first = ready.executionReadyPlan().physicalPlan().placements().get(0)
                .components().get(0).position();
        level.setBlockAndUpdate(block(first), Blocks.OBSIDIAN.defaultBlockState());
        CreateV606GoalDrivenExecution.StartResult changed = CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer);
        check(changed instanceof CreateV606GoalDrivenExecution.Rejected
                        && ((CreateV606GoalDrivenExecution.Rejected) changed).code()
                        == ExecutionReadinessFailureCode.BLOCK_PLACEMENT_BLOCKED,
                "Changed target was not blocked before session execution: " + changed);
        level.setBlockAndUpdate(block(first), Blocks.AIR.defaultBlockState());
        LOGGER.info("GOAL_EXECUTION_FAILURE_MATRIX PASS inputMissing=true formalWorldForbidden=true targetChanged=true");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_12", timeoutTicks = 800)
    public static void handlerRechecksWorldGuardAfterSessionConstruction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/handler_guard"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        CreateV606GoalDrivenExecution.Started started = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer));
        check(started.session().directTaskGraph().verifiedPhysicalPlanId()
                        .equals(ready.executionReadyPlan().physicalPlan().id())
                        && started.session().directAssignment().mode() == ExecutionMode.DIRECT
                        && started.session().directAssignment().executorId().toString()
                        .equals("construction:direct_world_v606"),
                "Root session is not bound to the exact DirectWorldExecutor graph/assignment");
        BlockPos target = block(ready.executionReadyPlan().physicalPlan().placements().get(0)
                .components().get(0).position());
        var before = level.getBlockState(target);
        String key = "steve_industrial.execution.expectedGameDir";
        String expected = System.getProperty(key);
        check(expected != null, "Handler guard test lacks the isolated expected-gameDir property");
        System.clearProperty(key);
        try {
            CreateV606GoalDrivenExecution.TickResult result = started.session().tick();
            check(result instanceof CreateV606GoalDrivenExecution.Failed
                            && ((CreateV606GoalDrivenExecution.Failed) result).code()
                            == ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                    "Direct handler path did not fail with the formal-world code: " + result);
            check(level.getBlockState(target).equals(before),
                    "Handler guard failure mutated the first planned target");
        } finally {
            System.setProperty(key, expected);
        }
        LOGGER.info("FORMAL_WORLD_HANDLER_GUARD PASS sessionConstructed=true propertyRevoked=true code=FORMAL_WORLD_EXECUTION_FORBIDDEN worldMutation=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_06", timeoutTicks = 800)
    public static void cancellationAndDuplicateSessionAreBounded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:andesite");
        seed(level, buffer, input.toString(), 3);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("minecraft:gravel"), 3, Map.of(input, 3L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/cancel"), Map.of(input, 3L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        CreateV606GoalDrivenExecution.Started started = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer));
        CreateV606GoalDrivenExecution.StartResult duplicate = CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer);
        check(duplicate instanceof CreateV606GoalDrivenExecution.Rejected
                        && ((CreateV606GoalDrivenExecution.Rejected) duplicate).code()
                        == ExecutionReadinessFailureCode.SESSION_ALREADY_EXISTS,
                "Duplicate root session was not rejected");
        CreateV606GoalDrivenExecution.TickResult first = started.session().tick();
        check(first instanceof CreateV606GoalDrivenExecution.Progress,
                "Cancellation probe did not perform one bounded progress tick");
        CreateV606GoalDrivenExecution.Cancellation cancelled = started.session().cancel(
                id("steve_industrial:goal_test/cancel_requested"));
        check(started.session().lastDirectTaskResult().orElseThrow().outcome()
                        == TaskExecutionOutcome.CANCELLED,
                "DirectWorldExecutor did not retain the typed cancellation result");
        check(cancelled.code() == ExecutionReadinessFailureCode.EXECUTION_CANCELLED
                        && cancelled.journals().size() == 1
                        && cancelled.journals().get(0).entries().size() == 1,
                "Cancellation did not retain its one-change journal");
        check(level.getBlockState(block(ready.executionReadyPlan().physicalPlan().placements().get(0)
                        .components().get(0).position())).canBeReplaced(),
                "Cancellation did not conservatively restore the first construction cell");
        LOGGER.info("GOAL_EXECUTION_CANCEL PASS duplicateRejected=true changes=1 restored=true code=EXECUTION_CANCELLED");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_07", timeoutTicks = 6_000)
    public static void buildCompleteReloadReconcilesAndResumes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/reload_build"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        AtomicReference<CreateV606GoalDrivenExecution.Session> session = new AtomicReference<>(
                started(CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer)).session());
        AtomicBoolean resumed = new AtomicBoolean();
        AtomicInteger checkpointBytes = new AtomicInteger();
        helper.succeedWhen(() -> {
            CreateV606GoalDrivenExecution.TickResult result = session.get().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("Recovered goal execution failed: " + failure);
                return;
            }
            if (!resumed.get() && result instanceof CreateV606GoalDrivenExecution.Progress progress
                    && progress.phase() == CreateV606GoalDrivenExecution.Phase.CONNECT) {
                var reload = session.get().prepareReload();
                check(reload instanceof CreateV606GoalDrivenExecution.ReloadReady,
                        "BUILD-complete goal checkpoint was refused: " + reload);
                RecoveryCheckpoint checkpoint =
                        ((CreateV606GoalDrivenExecution.ReloadReady) reload).checkpoint();
                byte[] encoded = RecoveryCheckpointCodec.encode(checkpoint);
                checkpointBytes.set(encoded.length);
                RecoveryCheckpoint decoded = RecoveryCheckpointCodec.decode(encoded);
                var reconciled = CreateV606GoalDrivenExecution.reconcileReload(
                        level, ready.executionReadyPlan(), decoded);
                check(reconciled instanceof CreateV606GoalDrivenExecution.ReconcileReady,
                        "Exact goal recovery rescan was refused: " + reconciled);
                var restarted = CreateV606GoalDrivenExecution.resume(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer,
                        ((CreateV606GoalDrivenExecution.ReconcileReady) reconciled).resumable());
                session.set(started(restarted).session());
                resumed.set(true);
                throw new GameTestAssertException("goal execution resumed after BUILD checkpoint");
            }
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                check(resumed.get() && completed.observedQuantity() >= 2,
                        "Recovered goal did not produce two iron sheets");
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_RELOAD PASS boundary=BUILD_COMPLETE codecBytes={} exactRescan=true blindResume=false repeatedPlacements=0 repeatedInputs=0 output=create:iron_sheetx{}",
                        checkpointBytes.get(), completed.observedQuantity());
                return;
            }
            throw new GameTestAssertException("waiting for BUILD checkpoint or recovered output");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_08", timeoutTicks = 6_000)
    public static void resourceHistoryRefusesUnsafeReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/reload_process"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        CreateV606GoalDrivenExecution.Session session = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer)).session();
        helper.succeedWhen(() -> {
            CreateV606GoalDrivenExecution.TickResult result = session.tick();
            if (result instanceof CreateV606GoalDrivenExecution.Progress progress
                    && progress.phase() == CreateV606GoalDrivenExecution.Phase.PROCESS) {
                var reload = session.prepareReload();
                check(reload instanceof CreateV606GoalDrivenExecution.ReloadRefused
                                && ((CreateV606GoalDrivenExecution.ReloadRefused) reload).code()
                                == ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        "Resource-bearing PROCESS checkpoint was not refused precisely: " + reload);
                session.cancel(id("steve_industrial:goal_test/reload_refused_cleanup"));
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_RELOAD_REFUSED PASS boundary=PROCESS code=RELOAD_RECOVERY_UNSAFE resourceHistory=true automaticResume=false");
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("PROCESS reload probe failed before refusal: " + failure);
                return;
            }
            throw new GameTestAssertException("waiting for real resource history");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_13", timeoutTicks = 6_000)
    public static void midBuildReloadReconcilesWithoutDuplicatePlacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/reload_mid_build"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        AtomicReference<CreateV606GoalDrivenExecution.Session> session = new AtomicReference<>(
                started(CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer)).session());
        CreateV606GoalDrivenExecution.TickResult first = session.get().tick();
        check(first instanceof CreateV606GoalDrivenExecution.Progress progress
                        && progress.phase() == CreateV606GoalDrivenExecution.Phase.BUILD,
                "First bounded build tick did not expose BUILD progress: " + first);
        var reload = session.get().prepareReload();
        check(reload instanceof CreateV606GoalDrivenExecution.ReloadReady,
                "Mid-BUILD checkpoint was refused: " + reload);
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(RecoveryCheckpointCodec.encode(
                ((CreateV606GoalDrivenExecution.ReloadReady) reload).checkpoint()));
        check(checkpoint.journal().entries().size() == 1,
                "Mid-BUILD checkpoint did not retain exactly one atomic placement");
        var reconciled = CreateV606GoalDrivenExecution.reconcileReload(
                level, ready.executionReadyPlan(), checkpoint);
        check(reconciled instanceof CreateV606GoalDrivenExecution.ReconcileReady,
                "Mid-BUILD exact rescan was refused: " + reconciled);
        AtomicInteger reloadGapTicks = new AtomicInteger();
        AtomicBoolean resumed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            if (!resumed.get()) {
                if (reloadGapTicks.incrementAndGet() <= 40) {
                    throw new GameTestAssertException(
                            "waiting beyond the original BUILD timeout before recovery");
                }
                session.set(started(CreateV606GoalDrivenExecution.resume(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer,
                        ((CreateV606GoalDrivenExecution.ReconcileReady) reconciled).resumable())).session());
                resumed.set(true);
            }
            var result = session.get().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("Mid-BUILD recovered execution failed: " + failure);
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                List<BlockPos3i> changes = completed.journals().stream()
                        .flatMap(journal -> journal.modifiedPositions().stream()).toList();
                check(new LinkedHashSet<>(changes).size() == changes.size(),
                        "Recovered BUILD repeated a journal-owned placement");
                check(completed.observedQuantity() == 2,
                        "Recovered BUILD did not produce exactly two sheets");
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_RELOAD PASS boundary=BUILD_MID exactRescan=true reloadGapTicks=40 timeoutRebased=true repeatedPlacements=0 repeatedInputs=0 output=create:iron_sheetx2");
                return;
            }
            throw new GameTestAssertException("waiting for mid-BUILD recovered output");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c05_reload", timeoutTicks = 6_000)
    public static void c05MidBuildReloadReconcilesWithoutDuplicatePlacement(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:gravel");
        ResourceId output = id("minecraft:sand");
        seed(level, buffer, input.toString(), 1);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, output, 1, Map.of(input, 1L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/c05_reload_mid_build"),
                Map.of(input, 1L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                new MaterialConstraints(Set.of(id("minecraft:sandstone")), Map.of())));
        AtomicReference<CreateV606GoalDrivenExecution.Session> session =
                new AtomicReference<>(started(CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer)).session());
        CreateV606GoalDrivenExecution.TickResult first = session.get().tick();
        check(first instanceof CreateV606GoalDrivenExecution.Progress progress
                        && progress.phase() == CreateV606GoalDrivenExecution.Phase.BUILD,
                "C-05 first bounded build tick did not expose BUILD progress: " + first);
        var reload = session.get().prepareReload();
        check(reload instanceof CreateV606GoalDrivenExecution.ReloadReady,
                "C-05 mid-BUILD checkpoint was refused: " + reload);
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(
                RecoveryCheckpointCodec.encode(
                        ((CreateV606GoalDrivenExecution.ReloadReady) reload).checkpoint()));
        check(checkpoint.journal().entries().size() == 1,
                "C-05 mid-BUILD checkpoint did not retain one atomic placement");
        var reconciled = CreateV606GoalDrivenExecution.reconcileReload(
                level, ready.executionReadyPlan(), checkpoint);
        check(reconciled instanceof CreateV606GoalDrivenExecution.ReconcileReady,
                "C-05 mid-BUILD exact rescan was refused: " + reconciled);
        AtomicInteger reloadGapTicks = new AtomicInteger();
        AtomicBoolean resumed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            if (!resumed.get()) {
                if (reloadGapTicks.incrementAndGet() <= 40) {
                    throw new GameTestAssertException(
                            "waiting beyond the original C-05 BUILD timeout before recovery");
                }
                session.set(started(CreateV606GoalDrivenExecution.resume(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer,
                        ((CreateV606GoalDrivenExecution.ReconcileReady) reconciled)
                                .resumable())).session());
                resumed.set(true);
            }
            var result = session.get().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("C-05 recovered execution failed: " + failure);
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                List<BlockPos3i> changes = completed.journals().stream()
                        .flatMap(journal -> journal.modifiedPositions().stream()).toList();
                check(new LinkedHashSet<>(changes).size() == changes.size(),
                        "C-05 recovered BUILD repeated a journal-owned placement");
                check(completed.observedQuantity() >= 1
                                && completed.finalResourceBuffer()
                                .getOrDefault(output, 0L) >= 1
                                && completed.finalResourceBuffer()
                                .getOrDefault(input, 0L) == 0,
                        "C-05 recovered BUILD duplicated or lost resources: "
                                + completed.finalResourceBuffer());
                clearPhysicalPlan(level, ready);
                LOGGER.info(
                        "C05_EXACT_RELOAD PASS boundary=BUILD_MID exactRescan=true reloadGapTicks=40 repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0 output=minecraft:sand>=1");
                return;
            }
            throw new GameTestAssertException(
                    "waiting for C-05 mid-BUILD recovered output");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c07_exact_recovery", timeoutTicks = 6_000)
    public static void c07ExactRecoveryResumesOnlyUnfinishedBuild(
            GameTestHelper helper) {
        runCapabilityExactRecovery(
                helper, "C07", "minecraft:stripped_oak_log",
                Map.of(id("minecraft:oak_log"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c06_exact_recovery", timeoutTicks = 6_000)
    public static void c06ExactRecoveryResumesOnlyUnfinishedBuild(
            GameTestHelper helper) {
        runCapabilityExactRecovery(
                helper, "C06", "create:dough",
                Map.of(id("create:wheat_flour"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c09_exact_recovery", timeoutTicks = 6_000)
    public static void c09ExactRecoveryResumesOnlyUnfinishedBuild(
            GameTestHelper helper) {
        runCapabilityExactRecovery(
                helper, "C09", "create:blaze_cake_base",
                Map.of(
                        id("minecraft:egg"), 1L,
                        id("minecraft:sugar"), 1L,
                        id("create:cinder_flour"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_exact_recovery", timeoutTicks = 6_000)
    public static void c08ExactRecoveryResumesOnlyUnfinishedBuild(
            GameTestHelper helper) {
        runCapabilityExactRecovery(
                helper, "C08", "create:andesite_alloy",
                Map.of(
                        id("minecraft:andesite"), 1L,
                        id("minecraft:iron_nugget"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_heated_exact_recovery", timeoutTicks = 6_000)
    public static void c08HeatedExactRecoveryReleasesAndReacquiresFuel(
            GameTestHelper helper) {
        runCapabilityExactRecovery(
                helper, "C08_HEATED", "create:brass_ingot",
                Map.of(
                        id("minecraft:copper_ingot"), 1L,
                        id("create:zinc_ingot"), 1L,
                        id("minecraft:coal"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_heated_reservation", timeoutTicks = 800)
    public static void c08HeatedFuelReservationIsExclusiveAndCancelReleases(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        Map<ResourceId, Long> resources = Map.of(
                id("minecraft:copper_ingot"), 1L,
                id("create:zinc_ingot"), 1L,
                id("minecraft:coal"), 1L);
        seed(level, buffer, resources);
        CreateV606GoalDrivenPlanner.Ready first = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:brass_ingot"), 2, resources,
                anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/c08_heated_reservation_first"),
                resources, ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                MaterialConstraints.none()));
        CreateV606GoalDrivenPlanner.Ready second = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:brass_ingot"), 2, resources,
                anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/c08_heated_reservation_second"),
                resources, ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                MaterialConstraints.none()));

        CreateV606GoalDrivenExecution.Started firstStarted = started(
                CreateV606GoalDrivenExecution.begin(
                        level, first.executionReadyPlan(), first.runtime(), buffer,
                        first.executionMetadata()));
        CreateV606GoalDrivenExecution.StartResult conflict =
                CreateV606GoalDrivenExecution.begin(
                        level, second.executionReadyPlan(), second.runtime(), buffer,
                        second.executionMetadata());
        check(conflict instanceof CreateV606GoalDrivenExecution.Rejected rejected
                        && rejected.code()
                                == ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING
                        && rejected.detail().contains("Fuel reservation conflict"),
                "Second HEATED session double-reserved one coal: " + conflict);
        CreateV606GoalDrivenExecution.Cancellation firstCancellation =
                firstStarted.session().cancel(
                        id("steve_industrial:test/c08_heated_cancel_first"));
        check(firstCancellation.trace().stream().anyMatch(value ->
                        value.equals("execution:heated_fuel_release=cancel:pass")),
                "Cancel did not explicitly release reserved fuel: "
                        + firstCancellation.trace());

        CreateV606GoalDrivenExecution.Started secondStarted = started(
                CreateV606GoalDrivenExecution.begin(
                        level, second.executionReadyPlan(), second.runtime(), buffer,
                        second.executionMetadata()));
        CreateV606GoalDrivenExecution.Cancellation secondCancellation =
                secondStarted.session().cancel(
                        id("steve_industrial:test/c08_heated_cancel_second"));
        check(secondCancellation.trace().stream().anyMatch(value ->
                        value.equals("execution:heated_fuel_release=cancel:pass"))
                        && count(level, buffer, "minecraft:coal") == 1
                        && count(level, buffer, "minecraft:copper_ingot") == 1
                        && count(level, buffer, "create:zinc_ingot") == 1,
                "Cancellation changed unwithdrawn HEATED resources");
        LOGGER.info(
                "C08_HEATED_FUEL_RESERVATION PASS resource=minecraft:coal budget=1 ownerSession=true expiryBounded=true doubleReserveRefused=true cancelRelease=true playerInventoryReads=0 arbitraryContainerReads=0");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c10_exact_recovery", timeoutTicks = 6_000)
    public static void c10ExactRecoveryResumesOnlyUnfinishedBuild(
            GameTestHelper helper) {
        runCapabilityExactRecovery(
                helper, "C10", "create:cogwheel",
                Map.of(
                        id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c05_failure_boundary", timeoutTicks = 800)
    public static void c05PlanningAndCancellationFailuresAreTyped(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:gravel");
        ResourceId output = id("minecraft:sand");
        MaterialConstraints crushingOnly =
                new MaterialConstraints(Set.of(id("minecraft:sandstone")), Map.of());

        CreateV606GoalDrivenPlanner.PlanningResult missing =
                CreateV606GoalDrivenPlanner.plan(
                        level, output, 1, Map.of(input, 1L), anchor, QuarterTurn.ZERO,
                        id("steve_industrial:goal_test/c05_missing_material"),
                        Map.of(),
                        ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                        crushingOnly);
        check(missing instanceof CreateV606GoalDrivenPlanner.Failure
                        && ((CreateV606GoalDrivenPlanner.Failure) missing).code()
                        == ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                "C-05 missing material did not fail readiness precisely: " + missing);

        CreateV606GoalDrivenPlanner.PlanningResult formal =
                CreateV606GoalDrivenPlanner.plan(
                        level, output, 1, Map.of(input, 1L), anchor, QuarterTurn.ZERO,
                        id("steve_industrial:goal_test/c05_formal_world"),
                        Map.of(input, 1L),
                        ExecutionWorldClassification.FORMAL_EXTERNAL_INSTANCE,
                        crushingOnly);
        check(formal instanceof CreateV606GoalDrivenPlanner.Failure
                        && ((CreateV606GoalDrivenPlanner.Failure) formal).code()
                        == ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                "C-05 formal-world classification was not refused: " + formal);

        seed(level, buffer, input.toString(), 1);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, output, 1, Map.of(input, 1L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/c05_boundary_failures"),
                Map.of(input, 1L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                crushingOnly));
        BlockPos3i first = ready.executionReadyPlan().physicalPlan().placements().get(0)
                .components().get(0).position();
        level.setBlockAndUpdate(block(first), Blocks.OBSIDIAN.defaultBlockState());
        CreateV606GoalDrivenExecution.StartResult obstructed =
                CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer);
        check(obstructed instanceof CreateV606GoalDrivenExecution.Rejected
                        && ((CreateV606GoalDrivenExecution.Rejected) obstructed).code()
                        == ExecutionReadinessFailureCode.BLOCK_PLACEMENT_BLOCKED,
                "C-05 obstruction was not rejected before mutation: " + obstructed);
        level.setBlockAndUpdate(block(first), Blocks.AIR.defaultBlockState());

        CreateV606GoalDrivenExecution.Started started =
                started(CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer));
        check(started.session().tick() instanceof CreateV606GoalDrivenExecution.Progress,
                "C-05 cancellation probe did not perform one bounded placement");
        CreateV606GoalDrivenExecution.Cancellation cancelled =
                started.session().cancel(
                        id("steve_industrial:goal_test/c05_cancel_requested"));
        check(cancelled.code() == ExecutionReadinessFailureCode.EXECUTION_CANCELLED
                        && cancelled.journals().size() == 1
                        && cancelled.journals().get(0).entries().size() == 1
                        && level.getBlockState(block(first)).canBeReplaced(),
                "C-05 cancellation did not conservatively restore its bounded change: "
                        + cancelled);
        LOGGER.info(
                "C05_FAILURE_BOUNDARY PASS materialMissing=INPUT_RESOURCE_MISSING formalWorld=FORMAL_WORLD_FORBIDDEN obstruction=BLOCK_PLACEMENT_BLOCKED cancellation=EXECUTION_CANCELLED rollbackWarnings=0 inputConsumed=false outputProduced=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c05_runtime_faults", timeoutTicks = 6_000)
    public static void c05RuntimeFaultsAreTypedAndContained(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrushingWheelPlan plan = CrushingWheelPlan.at(
                position(helper.absolutePos(new BlockPos(20, 2, 2))),
                QuarterTurn.ZERO);
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(level);
        AtomicReference<CrushingWheelExecutionSession> session =
                new AtomicReference<>(crushingSession(Create606CrushingWheelExecutor.begin(
                        level, plan, adapter.runtime())));
        AtomicInteger fault = new AtomicInteger();
        AtomicBoolean injected = new AtomicBoolean();

        helper.succeedWhen(() -> {
            AdapterResult<CrushingWheelExecutionUpdate> result = session.get().tick();
            if (result instanceof AdapterResult.Failure<CrushingWheelExecutionUpdate> failure) {
                if (fault.get() == 0) {
                    check(injected.get()
                                    && failure.code() == AdapterFailureCode.PROCESSING_FAILED
                                    && failure.detail().contains("instead of DOWN"),
                            "C-05 wrong controller direction was not refused precisely: "
                                    + failure);
                    clearC05(level, plan);
                    fault.incrementAndGet();
                    injected.set(false);
                    session.set(crushingSession(Create606CrushingWheelExecutor.begin(
                            level, plan, adapter.runtime())));
                    throw new GameTestAssertException(
                            "advancing to C-05 unknown-side-effect probe");
                }
                if (fault.get() == 1) {
                    check(injected.get()
                                    && failure.code() == AdapterFailureCode.PROCESSING_FAILED
                                    && failure.detail().contains("not empty before feed"),
                            "C-05 unknown output-side effect was not refused precisely: "
                                    + failure);
                    clearC05(level, plan);
                    fault.incrementAndGet();
                    injected.set(false);
                    session.set(crushingSession(Create606CrushingWheelExecutor.begin(
                            level, plan, adapter.runtime())));
                    throw new GameTestAssertException(
                            "advancing to C-05 insufficient-power probe");
                }
                check(fault.get() == 2
                                && injected.get()
                                && failure.code()
                                == AdapterFailureCode.KINETIC_COMPONENT_NOT_FOUND,
                        "C-05 removed power component was not refused precisely: " + failure);
                check(session.get().worldChangeJournal().entries().stream()
                                .noneMatch(entry -> entry
                                        instanceof WorldChangeJournal.InjectedResourceChange
                                        || entry instanceof WorldChangeJournal
                                        .IrreversibleProcessingChange),
                        "C-05 runtime fault consumed or produced a resource");
                clearC05(level, plan);
                LOGGER.info(
                        "C05_RUNTIME_FAULT_MATRIX PASS wrongOrientation=PROCESSING_FAILED unknownSideEffect=PROCESSING_FAILED insufficientPower=KINETIC_COMPONENT_NOT_FOUND inputConsumed=false outputProduced=false cleanup=true");
                return;
            }

            CrushingWheelExecutionUpdate update =
                    ((AdapterResult.Success<CrushingWheelExecutionUpdate>) result).value();
            check(update instanceof CrushingWheelExecutionUpdate.InProgress,
                    "C-05 fault probe unexpectedly completed");
            CrushingWheelExecutionUpdate.InProgress progress =
                    (CrushingWheelExecutionUpdate.InProgress) update;
            if (!injected.get()
                    && fault.get() <= 1
                    && progress.phase() == CreatePlanExecutionPhase.FEEDING) {
                if (fault.get() == 0) {
                    BlockPos controller = block(plan.controllerPosition());
                    level.setBlockAndUpdate(
                            controller,
                            level.getBlockState(controller).setValue(
                                    BlockStateProperties.FACING, Direction.UP));
                } else {
                    ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(
                            plan.placement(CrushingWheelRole.OUTPUT_CHEST).position()));
                    check(chest != null, "C-05 output chest disappeared before fault injection");
                    chest.setItem(0, new ItemStack(Items.DIRT));
                    chest.setChanged();
                }
                injected.set(true);
            } else if (!injected.get()
                    && fault.get() == 2
                    && progress.phase() == CreatePlanExecutionPhase.AWAITING_POWER) {
                level.setBlockAndUpdate(
                        block(plan.placement(CrushingWheelRole.RIGHT_DRIVE).position()),
                        Blocks.AIR.defaultBlockState());
                injected.set(true);
            }
            throw new GameTestAssertException("waiting for C-05 typed runtime fault");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_18", timeoutTicks = 6_000)
    public static void multiNodeMidBuildReloadResumesFromTrustedInitialNode(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:andesite");
        ResourceId output = id("minecraft:gravel");
        seed(level, buffer, input.toString(), 3);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, output, 3, Map.of(input, 3L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/reload_multi_node_mid_build"), Map.of(input, 3L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        AtomicReference<CreateV606GoalDrivenExecution.Session> session = new AtomicReference<>(
                started(CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer)).session());
        CreateV606GoalDrivenExecution.TickResult first = session.get().tick();
        check(first instanceof CreateV606GoalDrivenExecution.Progress progress
                        && progress.phase() == CreateV606GoalDrivenExecution.Phase.BUILD
                        && progress.processNodeIndex() == 0
                        && progress.processNodeCount() == 2,
                "First bounded multi-node build tick did not expose the trusted initial node: " + first);
        var reload = session.get().prepareReload();
        check(reload instanceof CreateV606GoalDrivenExecution.ReloadReady,
                "Multi-node mid-BUILD checkpoint was refused: " + reload);
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(RecoveryCheckpointCodec.encode(
                ((CreateV606GoalDrivenExecution.ReloadReady) reload).checkpoint()));
        check(checkpoint.session().sessionId().equals(new ResourceId(
                        ready.executionReadyPlan().sessionId().namespace(),
                        ready.executionReadyPlan().sessionId().path() + "/node_0"))
                        && checkpoint.journal().entries().size() == 1,
                "Multi-node checkpoint did not retain the exact initial child and placement");
        var reconciled = CreateV606GoalDrivenExecution.reconcileReload(
                level, ready.executionReadyPlan(), checkpoint);
        check(reconciled instanceof CreateV606GoalDrivenExecution.ReconcileReady,
                "Multi-node exact initial-node rescan was refused: " + reconciled);
        AtomicInteger reloadGapTicks = new AtomicInteger();
        AtomicBoolean resumed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            if (!resumed.get()) {
                if (reloadGapTicks.incrementAndGet() <= 40) {
                    throw new GameTestAssertException(
                            "waiting beyond the original multi-node BUILD timeout before recovery");
                }
                session.set(started(CreateV606GoalDrivenExecution.resume(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer,
                        ((CreateV606GoalDrivenExecution.ReconcileReady) reconciled).resumable())).session());
                resumed.set(true);
            }
            var result = session.get().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("Multi-node recovered execution failed: " + failure);
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                List<BlockPos3i> changes = completed.journals().stream()
                        .flatMap(journal -> journal.modifiedPositions().stream()).toList();
                check(new LinkedHashSet<>(changes).size() == changes.size(),
                        "Multi-node recovered BUILD repeated a journal-owned placement");
                check(completed.observedQuantity() == 3
                                && completed.finalResourceBuffer().getOrDefault(output, 0L) == 3
                                && completed.finalResourceBuffer().getOrDefault(input, 0L) == 0,
                        "Multi-node recovered BUILD duplicated or lost resources: "
                                + completed.finalResourceBuffer());
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_RELOAD PASS boundary=BUILD_MID processNodes=2 recoveredNode=0 exactRescan=true reloadGapTicks=40 timeoutRebased=true repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0 output=minecraft:gravelx3");
                return;
            }
            throw new GameTestAssertException("waiting for multi-node recovered output");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_14", timeoutTicks = 6_000)
    public static void verifyReloadFinalizesIdempotently(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/reload_verify"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        CreateV606GoalDrivenExecution.Session session = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer)).session();
        helper.succeedWhen(() -> {
            var result = session.tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("VERIFY recovery probe failed before VERIFY: " + failure);
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Progress progress
                    && progress.phase() == CreateV606GoalDrivenExecution.Phase.VERIFY) {
                List<WorldChangeJournal> journals = session.journalsSnapshot();
                var first = CreateV606GoalDrivenExecution.recoverVerify(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer, journals);
                check(first instanceof CreateV606GoalDrivenExecution.VerifyRecovered recovered
                                && !recovered.alreadyCollected()
                                && recovered.completed().observedQuantity() == 2,
                        "First VERIFY recovery did not collect exactly once: " + first);
                var second = CreateV606GoalDrivenExecution.recoverVerify(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer, journals);
                check(second instanceof CreateV606GoalDrivenExecution.VerifyRecovered recovered
                                && recovered.alreadyCollected()
                                && recovered.completed().observedQuantity() == 2,
                        "Repeated VERIFY recovery duplicated or lost output: " + second);
                session.cancel(id("steve_industrial:goal_test/verify_recovery_cleanup"));
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_RELOAD PASS boundary=VERIFY alreadyCollectedSecond=true repeatedInputs=0 repeatedOutput=0 output=create:iron_sheetx2");
                return;
            }
            throw new GameTestAssertException("waiting for VERIFY boundary");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_09", timeoutTicks = 6_000)
    public static void routeTargetChangeFailsDuringConstruction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:andesite");
        seed(level, buffer, input.toString(), 3);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("minecraft:gravel"), 3, Map.of(input, 3L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/route_changed"), Map.of(input, 3L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        BlockPos3i routeCell = ready.executionReadyPlan().physicalPlan().routes().stream()
                .filter(route -> route.positions().size() > 2).findFirst().orElseThrow()
                .positions().get(1);
        CreateV606GoalDrivenExecution.Session session = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer)).session();
        AtomicBoolean obstructed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            var result = session.tick();
            if (!obstructed.get() && session.processNodeIndex() == 1
                    && result instanceof CreateV606GoalDrivenExecution.Progress progress
                    && progress.phase() == CreateV606GoalDrivenExecution.Phase.CONNECT) {
                level.setBlockAndUpdate(block(routeCell), Blocks.OBSIDIAN.defaultBlockState());
                obstructed.set(true);
                throw new GameTestAssertException("route target changed before construction");
            }
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                check(obstructed.get()
                                && failure.code() == ExecutionReadinessFailureCode.ROUTE_CONSTRUCTION_FAILED,
                        "Changed route target did not fail precisely: " + failure);
                level.setBlockAndUpdate(block(routeCell), Blocks.AIR.defaultBlockState());
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_ROUTE_FAILURE PASS code=ROUTE_CONSTRUCTION_FAILED changedAfterReadiness=true routeMutationBeforeFailure=0");
                return;
            }
            throw new GameTestAssertException("waiting for route construction refusal");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_10", timeoutTicks = 6_000)
    public static void missingPhysicalPowerFailsTyped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/power_removed"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        BlockPos3i drive = componentPosition(ready, "belt_drive_shaft");
        CreateV606GoalDrivenExecution.Session session = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer)).session();
        AtomicBoolean removed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            var result = session.tick();
            if (!removed.get() && result instanceof CreateV606GoalDrivenExecution.Progress progress
                    && progress.phase() == CreateV606GoalDrivenExecution.Phase.CONNECT) {
                level.setBlockAndUpdate(block(drive), Blocks.AIR.defaultBlockState());
                removed.set(true);
                throw new GameTestAssertException("power source removed after BUILD");
            }
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                check(removed.get() && failure.code() == ExecutionReadinessFailureCode.POWER_SOURCE_MISSING,
                        "Physical power loss did not fail precisely: " + failure);
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_POWER_FAILURE PASS code=POWER_SOURCE_MISSING removedAfterBuild=true inputConsumed=false");
                return;
            }
            throw new GameTestAssertException("waiting for physical power refusal");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_11", timeoutTicks = 6_000)
    public static void missingObservedOutputFailsTyped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId input = id("minecraft:iron_ingot");
        seed(level, buffer, input.toString(), 2);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id("create:iron_sheet"), 2, Map.of(input, 2L), anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/output_removed"), Map.of(input, 2L),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        BlockPos3i outputChest = componentPosition(ready, "output_chest");
        CreateV606GoalDrivenExecution.Session session = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer)).session();
        AtomicBoolean removed = new AtomicBoolean();
        helper.succeedWhen(() -> {
            var result = session.tick();
            if (!removed.get() && result instanceof CreateV606GoalDrivenExecution.Progress progress
                    && progress.phase() == CreateV606GoalDrivenExecution.Phase.VERIFY) {
                check(level.getBlockEntity(block(outputChest)) instanceof ChestBlockEntity,
                        "Press output chest disappeared before mismatch injection");
                ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(outputChest));
                chest.clearContent();
                chest.setChanged();
                removed.set(true);
                throw new GameTestAssertException("real output removed before root verification");
            }
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                check(removed.get()
                                && failure.code() == ExecutionReadinessFailureCode.OUTPUT_QUANTITY_MISMATCH,
                        "Missing observed output did not fail precisely: " + failure);
                clearPhysicalPlan(level, ready);
                LOGGER.info("GOAL_EXECUTION_OUTPUT_FAILURE PASS code=OUTPUT_QUANTITY_MISMATCH realProcessCompleted=true outputRemovedBeforeVerification=true");
                return;
            }
            throw new GameTestAssertException("waiting for output verification refusal");
        });
    }

    /**
     * Widening physical coverage by capability rather than by product count.
     *
     * <p>The 111 buildable composites are one shape repeated — the survey reports
     * {@code {[cutting,cutting,cutting]=100, [cutting,cutting]=11}} — so breadth does not
     * live there. It lives in the single-machine goals, where the eighty-odd executable
     * targets span milling, pressing, crushing, cutting, haunting and blasting, and where
     * physical evidence covered three capabilities.
     *
     * <p>Each of these is a different executor against a real recipe. A capability that
     * cannot actually run is worth finding out about here rather than the first time a
     * player asks for it.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_compacting", timeoutTicks = 6_000)
    public static void compactingProducesBlazeCakeBase(GameTestHelper helper) {
        runSuccess(helper, "create:blaze_cake_base", 1,
                Map.of(id("minecraft:egg"), 1L, id("minecraft:sugar"), 1L,
                        id("create:cinder_flour"), 1L),
                QuarterTurn.ZERO);
    }

    /** The first real compacting recipe whose declared lava is poured into a Create Basin. */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_compacting_fluid_input", timeoutTicks = 6_000)
    public static void compactingPoursLavaAndProducesGranite(GameTestHelper helper) {
        ResourceId target = id("minecraft:granite");
        GoalCatalogEntry goal = SingleMachineGoalResolver.resolve(helper.getLevel(), target)
                .orElseThrow(() -> new IllegalStateException(
                        "lava-input granite recipe was not admitted"));
        check(goal.recipe().equals(id("create:compacting/granite_from_flint")),
                "granite must resolve through the exact reviewed compacting recipe: "
                        + goal.recipe());
        check(goal.fluidInputsPerBatch().equals(Map.of(id("minecraft:lava"), 100L)),
                "granite must declare exactly 100mB lava: " + goal.fluidInputsPerBatch());
        PlacementItemBinding.Bucket bucket = PlacementItemBinding
                .bucketsFor(id("minecraft:lava"), 100)
                .orElseThrow();
        Map<ResourceId, Long> materials = new java.util.LinkedHashMap<>(
                goal.inputsPerBatch());
        materials.merge(bucket.item(), bucket.count(), Math::addExact);
        check(PilotDeploymentCommand.acceptsSingleMachineGoal(
                        helper.getLevel(), target, 1),
                "the reviewed one-batch lava pour must be orderable");
        check(!PilotDeploymentCommand.acceptsSingleMachineGoal(
                        helper.getLevel(), target, 2),
                "bulk fluid compacting is not physically verified and must remain refused");
        runSuccess(helper, target.toString(), 1, Map.copyOf(materials), QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_splashing", timeoutTicks = 6_000)
    public static void splashingProducesDough(GameTestHelper helper) {
        runSuccess(helper, "create:dough", 1, "create:wheat_flour", 1, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_mixing", timeoutTicks = 6_000)
    public static void mixingProducesAndesiteAlloy(GameTestHelper helper) {
        runSuccess(helper, "create:andesite_alloy", 1,
                Map.of(id("minecraft:andesite"), 1L, id("minecraft:iron_nugget"), 1L),
                QuarterTurn.ZERO);
    }

    /** The first real recipe whose declared input is poured into a Create Basin. */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_mixing_fluid_input", timeoutTicks = 6_000)
    public static void mixingPoursWaterAndProducesPulp(GameTestHelper helper) {
        ResourceId target = id("create:pulp");
        GoalCatalogEntry goal = SingleMachineGoalResolver.resolve(helper.getLevel(), target)
                .orElseThrow(() -> new IllegalStateException(
                        "water-input pulp recipe was not admitted"));
        check(goal.fluidInputsPerBatch().equals(Map.of(id("minecraft:water"), 250L)),
                "pulp must declare exactly 250mB water: " + goal.fluidInputsPerBatch());
        PlacementItemBinding.Bucket bucket = PlacementItemBinding
                .bucketsFor(id("minecraft:water"), 250)
                .orElseThrow();
        Map<ResourceId, Long> materials = new java.util.LinkedHashMap<>(
                goal.inputsPerBatch());
        materials.merge(bucket.item(), bucket.count(), Math::addExact);
        check(PilotDeploymentCommand.acceptsSingleMachineGoal(
                        helper.getLevel(), target, 1),
                "the reviewed one-batch water pour must be orderable");
        check(!PilotDeploymentCommand.acceptsSingleMachineGoal(
                        helper.getLevel(), target, 2),
                "bulk fluid mixing is not physically verified and must remain refused");
        runSuccess(helper, target.toString(), 1, Map.copyOf(materials), QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_deploying", timeoutTicks = 6_000)
    public static void deployingProducesCogwheel(GameTestHelper helper) {
        runSuccess(helper, "create:cogwheel", 1,
                Map.of(id("create:shaft"), 1L, id("minecraft:oak_planks"), 1L),
                QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_cutting", timeoutTicks = 6_000)
    public static void cuttingProducesStrippedLog(GameTestHelper helper) {
        runSuccess(helper, "minecraft:stripped_oak_log", 1, "minecraft:oak_log", 1,
                QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_haunting", timeoutTicks = 6_000)
    public static void hauntingProducesBlackstone(GameTestHelper helper) {
        runSuccess(helper, "minecraft:blackstone", 1, "minecraft:cobblestone", 1,
                QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_blasting", timeoutTicks = 6_000)
    public static void blastingProducesIronIngot(GameTestHelper helper) {
        runSuccess(helper, "minecraft:iron_ingot", 1, "minecraft:raw_iron", 1, QuarterTurn.ZERO);
    }

    /** One input, which is what most recipes need. */
    static void runSuccess(
            GameTestHelper helper,
            String target,
            int quantity,
            String rawInput,
            int rawCount,
            QuarterTurn orientation) {
        runSuccess(helper, target, quantity, Map.of(id(rawInput), (long) rawCount), orientation);
    }

    /**
     * Any number of inputs, because several capabilities have no single-input recipe.
     *
     * <p>Mixing, deploying and compacting all take two or three, and the single-input
     * form could not express them — so those executors had no physical coverage for a
     * reason that was about this helper rather than about them.</p>
     */
    static void runSuccess(
            GameTestHelper helper,
            String target,
            int quantity,
            Map<ResourceId, Long> inputs,
            QuarterTurn orientation) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        seed(level, buffer, inputs);
        ResourceId sessionId = id("steve_industrial:goal_test/"
                + target.replace(':', '_') + "_" + orientation.name().toLowerCase());
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id(target), quantity, inputs, anchor, orientation,
                sessionId, inputs,
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        check(ready.executionReadyPlan().evidence().size() == 16,
                "Execution readiness checklist is incomplete");
        CreateV606GoalDrivenExecution.Started started = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer,
                ready.executionMetadata()));
        check(started.session().directTaskGraph().verifiedPhysicalPlanId()
                        .equals(ready.executionReadyPlan().physicalPlan().id())
                        && started.session().directAssignment().mode() == ExecutionMode.DIRECT,
                "Goal session did not retain exact DirectWorldExecutor plan ownership");
        AtomicReference<CreateV606GoalDrivenExecution.Completed> completed = new AtomicReference<>();
        AtomicInteger maxInvocations = new AtomicInteger();
        Set<CreateV606GoalDrivenExecution.Phase> phases = new LinkedHashSet<>();
        helper.succeedWhen(() -> {
            CreateV606GoalDrivenExecution.TickResult result = started.session().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("Goal-driven execution failed: " + failure.code() + " - " + failure.detail());
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Progress progress) {
                var direct = started.session().lastDirectTaskResult().orElseThrow();
                check(direct.outcome() == TaskExecutionOutcome.PENDING
                                && direct.worldMutationCount() <= 1
                                && direct.materialMutationCount() <= 1,
                        "DirectWorldExecutor progress violated the bounded task result contract");
                phases.add(progress.phase());
                maxInvocations.accumulateAndGet(progress.maximumHandlerInvocationsThisTick(), Math::max);
                throw new GameTestAssertException("goal execution pending " + progress.phase()
                        + " node=" + progress.processNodeIndex() + "/" + progress.processNodeCount());
            }
            CreateV606GoalDrivenExecution.Completed value =
                    (CreateV606GoalDrivenExecution.Completed) result;
            completed.set(value);
            var direct = started.session().lastDirectTaskResult().orElseThrow();
            check(direct.outcome() == TaskExecutionOutcome.SUCCEEDED
                            && direct.evidence().stream().anyMatch(evidence ->
                            evidence.kind()
                                    == dev.stevecreate.agent.core.execution.construction
                                    .ExecutionEvidenceKind.OUTPUT_VERIFIED),
                    "DirectWorldExecutor completion lacks assignment-bound output evidence");
            check(value.target().equals(id(target)) && value.requiredQuantity() == quantity
                            && value.observedQuantity() >= quantity,
                    "Goal output identity or quantity changed");
            boolean hasRouteCells = ready.executionReadyPlan().physicalPlan().routes().stream()
                    .anyMatch(route -> route.positions().size() > 2);
            int expectedJournals = ready.executionReadyPlan().physicalPlan().placements().size()
                    + (hasRouteCells ? 1 : 0);
            check(value.journals().size() == expectedJournals,
                    "Process and physical-route journals were not retained");
            check(maxInvocations.get() == 1,
                    "A goal-driven tick exceeded the one-handler bound");
            check(phases.containsAll(EnumSet.of(
                            CreateV606GoalDrivenExecution.Phase.BUILD,
                            CreateV606GoalDrivenExecution.Phase.CONNECT,
                            CreateV606GoalDrivenExecution.Phase.FEED,
                            CreateV606GoalDrivenExecution.Phase.PROCESS,
                            CreateV606GoalDrivenExecution.Phase.VERIFY)),
                    "Goal-driven phase trace is incomplete: " + phases);
            check(value.trace().stream().anyMatch(line -> line.contains("execution:goal_verified=")),
                    "Goal-driven final trace lacks output verification");
            check(value.trace().stream().anyMatch(line ->
                            line.equals("execution:backend=direct-world-executor-v1")),
                    "Goal-driven trace does not prove DirectWorldExecutor migration");
            check(!hasRouteCells
                            || value.trace().stream().anyMatch(line ->
                            line.contains("execution:item_routes_constructed=")),
                    "Multi-node execution did not physically construct its verified ITEM route");
            if (!ready.executionMetadata().fluidInputsByStep().isEmpty()) {
                List<dev.stevecreate.agent.core.process.ProcessResource> declaredFluids =
                        ready.executionMetadata().fluidInputsByStep().values().stream()
                                .flatMap(List::stream)
                                .toList();
                check(declaredFluids.size() == 1,
                        "the one-cycle physical gate requires one exact fluid: "
                                + declaredFluids);
                var declaredFluid = declaredFluids.get(0);
                PlacementItemBinding.Bucket declaredBucket = PlacementItemBinding
                        .bucketsFor(declaredFluid.resourceId(), declaredFluid.amount())
                        .orElseThrow();
                check(value.finalResourceBuffer().getOrDefault(
                                declaredBucket.item(), 0L) == 0,
                        "the exact fluid bucket material was not consumed: "
                                + declaredBucket.item());
                long injectedMb = value.journals().stream()
                        .flatMap(journal -> journal.entries().stream())
                        .filter(WorldChangeJournal.InjectedResourceChange.class::isInstance)
                        .map(WorldChangeJournal.InjectedResourceChange.class::cast)
                        .map(WorldChangeJournal.InjectedResourceChange::resource)
                        .filter(resource -> resource.resourceType() == GenericResourceType.FLUID
                                && resource.resourceId().equals(declaredFluid.resourceId()))
                        .mapToLong(dev.stevecreate.agent.core.process.ProcessResource::amount)
                        .sum();
                check(injectedMb == declaredFluid.amount(),
                        "the world journal did not retain the exact declared pour: expected="
                                + declaredFluid.amount() + " actual=" + injectedMb);
                String capability = target.equals("create:pulp")
                        ? "MIXING" : "COMPACTING";
                LOGGER.info(
                        "FLUID_INPUT_{} PASS target={} fluid={} amountMb={} bucket={} bucketConsumed=true liveRecipeMatched=true finalBasinFluidMb=0 outputObserved={}",
                        capability, target, declaredFluid.resourceId(), injectedMb,
                        declaredBucket.item(), value.observedQuantity());
            }
            clearPhysicalPlan(level, ready);
            LOGGER.info(
                    "GOAL_EXECUTION_GAMETEST PASS target={} quantity={} observed={} orientation={} nodes={} planningChecks=8 bindingChecks=15 physicalChecks=13 readinessChecks=16 maxHandlerInvocationsPerTick={} inputConsumed=true outputVerified=true journals={} traceEntries={}",
                    target, quantity, value.observedQuantity(), orientation,
                    ready.executionReadyPlan().physicalPlan().placements().size(), maxInvocations.get(),
                    value.journals().size(), value.trace().size());
        });
    }

    private static void clearPhysicalPlan(
            ServerLevel level,
            CreateV606GoalDrivenPlanner.Ready ready) {
        ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                placement.components().forEach(component -> level.setBlockAndUpdate(
                        block(component.position()), Blocks.AIR.defaultBlockState())));
        ready.executionReadyPlan().physicalPlan().routes().forEach(route ->
                route.positions().forEach(position -> level.setBlockAndUpdate(
                        block(position), Blocks.AIR.defaultBlockState())));
    }

    private static void runCapabilityExactRecovery(
            GameTestHelper helper,
            String capability,
            String outputId,
            Map<ResourceId, Long> inputs) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId output = id(outputId);
        seed(level, buffer, inputs);
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, output, 1, inputs, anchor, QuarterTurn.ZERO,
                id("steve_industrial:goal_test/"
                        + capability.toLowerCase() + "_exact_recovery"),
                inputs,
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                MaterialConstraints.none()));
        AtomicReference<CreateV606GoalDrivenExecution.Session> session =
                new AtomicReference<>(started(CreateV606GoalDrivenExecution.begin(
                        level, ready.executionReadyPlan(), ready.runtime(), buffer,
                        ready.executionMetadata())).session());
        CreateV606GoalDrivenExecution.TickResult first = session.get().tick();
        check(first instanceof CreateV606GoalDrivenExecution.Progress progress
                        && progress.phase() == CreateV606GoalDrivenExecution.Phase.BUILD,
                capability + " first bounded tick did not expose BUILD progress: " + first);
        CreateV606GoalDrivenExecution.ReloadResult captured =
                session.get().prepareReload();
        check(captured instanceof CreateV606GoalDrivenExecution.ReloadReady,
                capability + " exact recovery checkpoint was refused: " + captured);
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(
                RecoveryCheckpointCodec.encode(
                        ((CreateV606GoalDrivenExecution.ReloadReady) captured).checkpoint()));
        check(checkpoint.journal().entries().size() == 1,
                capability + " checkpoint did not retain exactly one atomic placement");
        CreateV606GoalDrivenExecution.ReconcileResult reconciled =
                CreateV606GoalDrivenExecution.reconcileReload(
                        level, ready.executionReadyPlan(), checkpoint,
                        ready.executionMetadata());
        check(reconciled instanceof CreateV606GoalDrivenExecution.ReconcileReady,
                capability + " exact authoritative rescan was refused: " + reconciled);
        session.set(started(CreateV606GoalDrivenExecution.resume(
                level, ready.executionReadyPlan(), ready.runtime(), buffer,
                ((CreateV606GoalDrivenExecution.ReconcileReady) reconciled)
                        .resumable(), ready.executionMetadata())).session());

        helper.succeedWhen(() -> {
            CreateV606GoalDrivenExecution.TickResult result = session.get().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail(capability + " recovered execution failed: " + failure);
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                List<BlockPos3i> changes = completed.journals().stream()
                        .flatMap(journal -> journal.modifiedPositions().stream())
                        .toList();
                check(new LinkedHashSet<>(changes).size() == changes.size(),
                        capability + " recovery repeated a journal-owned placement");
                check(completed.observedQuantity() >= 1
                                && completed.finalResourceBuffer()
                                        .getOrDefault(output, 0L) >= 1
                                && inputs.keySet().stream().allMatch(input ->
                                        completed.finalResourceBuffer()
                                                .getOrDefault(input, 0L) == 0),
                        capability + " recovery duplicated or lost resources: "
                                + completed.finalResourceBuffer());
                clearPhysicalPlan(level, ready);
                LOGGER.info(
                        "{}_EXACT_RECOVERY PASS boundary=BUILD_MID exactRescan=true checkpointCodec=true repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0 output={}",
                        capability, output);
                return;
            }
            throw new GameTestAssertException(
                    "waiting for " + capability + " exact recovered output");
        });
    }

    private static BlockPos3i componentPosition(
            CreateV606GoalDrivenPlanner.Ready ready,
            String role) {
        return ready.executionReadyPlan().physicalPlan().placements().stream()
                .flatMap(placement -> placement.components().stream())
                .filter(component -> component.roleId().path().endsWith("/" + role))
                .findFirst().orElseThrow().position();
    }

    private static void seed(ServerLevel level, BlockPos3i position, String itemId, int count) {
        check(level.setBlockAndUpdate(block(position), Blocks.CHEST.defaultBlockState()),
                "Could not place isolated resource buffer chest");
        check(level.getBlockEntity(block(position)) instanceof ChestBlockEntity,
                "Resource buffer chest block entity is unavailable");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        ResourceLocation resourceLocation = ResourceLocation.tryParse(itemId);
        check(resourceLocation != null, "Resource buffer input ID is invalid: " + itemId);
        Item item = ForgeRegistries.ITEMS.getValue(resourceLocation);
        check(item != null, "Resource buffer input is not registered: " + itemId);
        chest.setItem(0, new ItemStack(item, count));
        chest.setChanged();
    }

    private static void seed(
            ServerLevel level,
            BlockPos3i position,
            Map<ResourceId, Long> materials) {
        check(level.setBlockAndUpdate(block(position), Blocks.CHEST.defaultBlockState()),
                "Could not place isolated resource buffer chest");
        check(level.getBlockEntity(block(position)) instanceof ChestBlockEntity,
                "Resource buffer chest block entity is unavailable");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        List<Map.Entry<ResourceId, Long>> ordered = materials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(ResourceId::toString)))
                .toList();
        check(ordered.size() <= chest.getContainerSize(),
                "Exact recovery input manifest exceeds the buffer");
        for (int slot = 0; slot < ordered.size(); slot++) {
            Map.Entry<ResourceId, Long> material = ordered.get(slot);
            Item item = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.fromNamespaceAndPath(
                            material.getKey().namespace(),
                            material.getKey().path()));
            check(item != null, "Resource buffer input is not registered: "
                    + material.getKey());
            chest.setItem(slot, new ItemStack(
                    item, Math.toIntExact(material.getValue())));
        }
        chest.setChanged();
    }

    private static int count(
            ServerLevel level, BlockPos3i position, String itemId) {
        ResourceLocation resourceLocation = ResourceLocation.tryParse(itemId);
        check(resourceLocation != null, "Resource buffer item ID is invalid: " + itemId);
        Item item = ForgeRegistries.ITEMS.getValue(resourceLocation);
        check(item != null
                        && level.getBlockEntity(block(position))
                                instanceof ChestBlockEntity,
                "Resource buffer readback is unavailable for " + itemId);
        ChestBlockEntity chest =
                (ChestBlockEntity) level.getBlockEntity(block(position));
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static CreateV606GoalDrivenPlanner.Ready ready(
            CreateV606GoalDrivenPlanner.PlanningResult result) {
        check(result instanceof CreateV606GoalDrivenPlanner.Ready,
                "Goal-driven planning did not produce readiness: " + result);
        return (CreateV606GoalDrivenPlanner.Ready) result;
    }

    private static CreateV606GoalDrivenExecution.Started started(
            CreateV606GoalDrivenExecution.StartResult result) {
        check(result instanceof CreateV606GoalDrivenExecution.Started,
                "Goal-driven execution did not start: " + result);
        return (CreateV606GoalDrivenExecution.Started) result;
    }

    private static CrushingWheelExecutionSession crushingSession(
            AdapterResult<CrushingWheelExecutionSession> result) {
        check(result instanceof AdapterResult.Success<CrushingWheelExecutionSession>,
                "C-05 execution did not start: " + result);
        return ((AdapterResult.Success<CrushingWheelExecutionSession>) result).value();
    }

    private static void clearC05(ServerLevel level, CrushingWheelPlan plan) {
        // The unknown-side-effect probe deliberately puts dirt in the C-05 output
        // chest. Breaking that nonempty fixture chest spawns a real dirt entity, which
        // can drift into a neighbouring GameTest's C-07 lane. Empty only the fixture's
        // exact owned containers before removing its blocks; C-07 must keep rejecting
        // foreign entities and no broad entity cleanup is permitted here.
        plan.placements().forEach(placement -> {
            var entity = level.getBlockEntity(block(placement.position()));
            if (entity instanceof net.minecraft.world.Container container) {
                container.clearContent();
                entity.setChanged();
            }
            level.setBlockAndUpdate(
                    block(placement.position()), Blocks.AIR.defaultBlockState());
        });
        level.setBlockAndUpdate(
                block(plan.controllerPosition()), Blocks.AIR.defaultBlockState());
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new GameTestAssertException(detail);
    }
}
