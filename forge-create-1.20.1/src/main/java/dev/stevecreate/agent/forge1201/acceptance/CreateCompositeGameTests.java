package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.MaterialEdge;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Node;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Shape;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ReservationStatus;
import dev.stevecreate.agent.core.execution.construction.SharedInfrastructureReservation;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606CompositeExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606BranchMergeExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ConcurrentExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ThreeModeExecution;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Concentrated real Create composite acceptance; no intermediate is inserted by test code. */
@PrefixGameTestTemplate(false)
public final class CreateCompositeGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateCompositeGameTests.class);

    private CreateCompositeGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_01_direct", timeoutTicks = 12_000)
    public static void composite01DirectRunsThreeRealStagesAndTwoPhysicalRoutes(
            GameTestHelper helper) {
        runComposite01(helper, ExecutionMode.DIRECT);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_01_bots", timeoutTicks = 12_000)
    public static void composite01BotsRunsThreeRealStagesAndTwoPhysicalRoutes(
            GameTestHelper helper) {
        runComposite01(helper, ExecutionMode.BOTS);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_01_hybrid", timeoutTicks = 12_000)
    public static void composite01HybridRunsThreeRealStagesAndTwoPhysicalRoutes(
            GameTestHelper helper) {
        runComposite01(helper, ExecutionMode.HYBRID);
    }

    private static void runComposite01(GameTestHelper helper, ExecutionMode mode) {
        ServerLevel level = helper.getLevel();
        clearArenaItemEntities(helper);
        prepareArena(helper);
        BlockPos3i sourceOne = pos(helper, 2, 2, 3);
        BlockPos3i deliveryOne = pos(helper, 7, 3, 3);
        BlockPos3i sourceTwo = pos(helper, 8, 2, 3);
        BlockPos3i deliveryTwo = pos(helper, 11, 3, 3);
        BlockPos3i sourceThree = pos(helper, 12, 2, 3);
        BlockPos3i finalDelivery = pos(helper, 15, 2, 3);
        BlockPos3i hopperOne = pos(helper, 7, 2, 3);
        BlockPos3i hopperTwo = pos(helper, 11, 2, 3);
        BlockPos3i lockOne = pos(helper, 7, 2, 4);
        BlockPos3i lockTwo = pos(helper, 11, 2, 4);
        BlockPos3i salvage = pos(helper, 11, 2, 2);

        seedEmptyChest(level, deliveryOne);
        seedEmptyChest(level, deliveryTwo);
        seedEmptyChest(level, finalDelivery);
        seedEmptyChest(level, salvage);
        installLockedHopper(level, hopperOne, lockOne);
        installLockedHopper(level, hopperTwo, lockTwo);

        CreateV606GoalDrivenPlanner.Ready first = plan(
                level, "minecraft:stripped_oak_log", 1,
                Map.of(id("minecraft:oak_log"), 1L), pos(helper, 20, 2, 3), "cut_log");
        CreateV606GoalDrivenPlanner.Ready second = plan(
                level, "minecraft:oak_planks", 6,
                Map.of(id("minecraft:stripped_oak_log"), 1L), pos(helper, 20, 2, 11), "cut_planks");
        CreateV606GoalDrivenPlanner.Ready third = plan(
                level, "create:cogwheel", 1,
                Map.of(id("minecraft:oak_planks"), 1L, id("create:shaft"), 1L),
                pos(helper, 20, 2, 19), "deploy_cogwheels");
        check(first.runtime().equals(second.runtime()) && first.runtime().equals(third.runtime()),
                "Composite runtime identity changed between plans");
        seedChest(level, sourceOne, mergeMaterials(
                Map.of(id("minecraft:oak_log"), 1L), installationMaterials(first)));
        seedChest(level, sourceTwo, installationMaterials(second));
        seedChest(level, sourceThree, mergeMaterials(
                Map.of(id("create:shaft"), 1L), installationMaterials(third)));

        CompositeProductionGraph graph = new CompositeProductionGraph(
                id("steve_industrial:composite/01"), Shape.LINEAR_CHAIN,
                List.of(
                        node("steve_industrial:composite/01_cut_log", "create:cutting"),
                        node("steve_industrial:composite/01_cut_planks", "create:cutting"),
                        node("steve_industrial:composite/01_deploy", "create:deploying")),
                List.of(
                        new MaterialEdge(
                                id("steve_industrial:composite/01_stripped_route"),
                                id("steve_industrial:composite/01_cut_log"),
                                id("steve_industrial:composite/01_cut_planks"),
                                id("minecraft:stripped_oak_log"), 1, 1),
                        new MaterialEdge(
                                id("steve_industrial:composite/01_plank_route"),
                                id("steve_industrial:composite/01_cut_planks"),
                                id("steve_industrial:composite/01_deploy"),
                                id("minecraft:oak_planks"), 1, 1)));
        List<CreateV606CompositeExecution.Stage> stages = List.of(
                stage(graph.nodes().get(0), first, sourceOne, deliveryOne,
                        "minecraft:stripped_oak_log", 1,
                        fleetStarts(helper, mode, 3, 2, 8)),
                stage(graph.nodes().get(1), second, sourceTwo, deliveryTwo,
                        "minecraft:oak_planks", 6,
                        fleetStarts(helper, mode, 3, 2, 16)),
                stage(graph.nodes().get(2), third, sourceThree, finalDelivery,
                        "create:cogwheel", 1,
                        fleetStarts(helper, mode, 3, 2, 24)));
        List<CreateV606CompositeExecution.HopperRoute> routes = List.of(
                route(graph.edges().get(0), hopperOne, lockOne, Optional.empty()),
                route(graph.edges().get(1), hopperTwo, lockTwo, Optional.of(salvage)));
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        pos(helper, 1, 2, 1), pos(helper, 31, 7, 31));
        CreateV606CompositeExecution.StartResult start =
                CreateV606CompositeExecution.start(
                        level, graph, stages, routes, mode, region);
        check(start instanceof CreateV606CompositeExecution.Started,
                "Composite-01 " + mode + " did not start: " + start);
        AtomicReference<CreateV606CompositeExecution.Session> session =
                new AtomicReference<>(((CreateV606CompositeExecution.Started) start).session());

        helper.onEachTick(() -> {
            CreateV606CompositeExecution.TickResult result = session.get().tick();
            if (result instanceof CreateV606CompositeExecution.Failed failed) {
                helper.fail("Composite-01 " + mode + " failed: " + failed);
                return;
            }
            if (result instanceof CreateV606CompositeExecution.Progress) return;
            CreateV606CompositeExecution.Completed completed =
                    (CreateV606CompositeExecution.Completed) result;
            check(completed.stages().size() == 3 && completed.routes().size() == 2,
                    "Composite-01 lacks full-chain stage/route evidence");
            check(completed.mode() == mode
                            && completed.stages().stream().allMatch(value -> value.mode() == mode),
                    "Composite-01 completion changed execution mode");
            if (mode == ExecutionMode.BOTS) {
                check(completed.stages().stream().allMatch(value ->
                                value.workerActivities().size() == 3
                                        && value.workerActivities().stream().allMatch(worker ->
                                                worker.assignmentsStarted() > 0
                                                        && worker.movementTicks() > 0)),
                        "Composite-01 Bots did not visibly execute every stage");
            }
            check(mode == ExecutionMode.DIRECT
                            ? completed.peakConcurrentWorkers() == 0
                                    && completed.retiredWorkerCount() == 0
                            : completed.peakConcurrentWorkers() == 3
                                    && completed.retiredWorkerCount() == 6,
                    "Composite-01 did not enforce one bounded three-worker stage fleet");
            check(completed.routes().stream().allMatch(value ->
                            value.observedTicks() > 0
                                    && value.observedSourceBefore()
                                            - value.observedSourceAfter()
                                            == value.expectedQuantity()
                                                    + value.observedOverflowAfter()
                                    && value.observedDestinationAfter() == value.expectedQuantity()),
                    "Composite-01 physical intermediate movement evidence is incomplete");
            check(count(level, finalDelivery, id("create:cogwheel")) == 1,
                    "Composite-01 final output is not exactly one real cogwheel");
            check(count(level, salvage, id("minecraft:oak_planks")) == 5,
                    "Composite-01 did not preserve the five unreserved real plank outputs");
            check(completed.coordinatorSnapshot().nodeStatuses().values().stream()
                            .allMatch(value -> value
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.SUCCEEDED),
                    "Composite-01 typed DAG did not finish every node");
            CreateV606CompositeExecution.CleanupReport cleanup = session.get().cleanup();
            check(cleanup.remainingMachinePositions() == 0
                            && cleanup.remainingRouteBlocks() == 0
                            && cleanup.clearedMachinePositions()
                                    == cleanup.plannedMachinePositions(),
                    "Composite-01 cleanup left owned infrastructure: " + cleanup);
            LOGGER.info("COMPOSITE_01_{} PASS stages=3 capabilities=2 routes=2 realIntermediateMovement=true final=create:cogwheel@1 preservedExcess=minecraft:oak_planks@5 reloadPerStage=true cleanup=true installationEscrow=true graph={} fingerprint={}",
                    mode.serializedName().toUpperCase(), completed.graphId(),
                    completed.graphFingerprint());
            helper.succeed();
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_03_direct", timeoutTicks = 12_000)
    public static void composite03DirectRunsPhysicalBranchAndMerge(GameTestHelper helper) {
        runComposite03(helper, ExecutionMode.DIRECT);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_03_bots", timeoutTicks = 12_000)
    public static void composite03BotsRunPhysicalBranchAndMerge(GameTestHelper helper) {
        runComposite03(helper, ExecutionMode.BOTS);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_03_hybrid", timeoutTicks = 12_000)
    public static void composite03HybridRunsPhysicalBranchAndMerge(GameTestHelper helper) {
        runComposite03(helper, ExecutionMode.HYBRID);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_03_contamination", timeoutTicks = 12_000)
    public static void composite03RefusesPhysicalRouteContamination(GameTestHelper helper) {
        runComposite03(helper, ExecutionMode.DIRECT, Composite03Fault.CONTAMINATION);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_03_backpressure", timeoutTicks = 12_000)
    public static void composite03RefusesPhysicalMergeBackpressure(GameTestHelper helper) {
        runComposite03(helper, ExecutionMode.DIRECT, Composite03Fault.BACKPRESSURE);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_03_branch_failure", timeoutTicks = 12_000)
    public static void composite03IsolatesPhysicalBranchFailure(GameTestHelper helper) {
        runComposite03(helper, ExecutionMode.DIRECT, Composite03Fault.BRANCH_INPUT_LOSS);
    }

    private enum Composite03Fault {
        NONE,
        CONTAMINATION,
        BACKPRESSURE,
        BRANCH_INPUT_LOSS
    }

    private static void runComposite03(GameTestHelper helper, ExecutionMode mode) {
        runComposite03(helper, mode, Composite03Fault.NONE);
    }

    private static void runComposite03(
            GameTestHelper helper, ExecutionMode mode, Composite03Fault fault) {
        ServerLevel level = helper.getLevel();
        clearArenaItemEntities(helper);
        prepareArena(helper);
        BlockPos3i rawSource = pos(helper, 15, 3, 13);
        BlockPos3i splitHopper = pos(helper, 15, 2, 13);
        BlockPos3i splitLock = pos(helper, 15, 2, 12);
        BlockPos3i woodSource = pos(helper, 14, 2, 13);
        BlockPos3i alloySource = pos(helper, 16, 2, 13);
        BlockPos3i woodDelivery = pos(helper, 14, 3, 18);
        BlockPos3i alloyDelivery = pos(helper, 16, 3, 18);
        BlockPos3i mergeSource = pos(helper, 15, 2, 18);
        BlockPos3i woodMergeHopper = pos(helper, 14, 2, 18);
        BlockPos3i alloyMergeHopper = pos(helper, 16, 2, 18);
        BlockPos3i woodMergeLock = pos(helper, 14, 2, 17);
        BlockPos3i alloyMergeLock = pos(helper, 16, 2, 17);
        BlockPos3i woodOverflow = pos(helper, 13, 2, 18);
        BlockPos3i alloyOverflow = pos(helper, 17, 2, 18);
        BlockPos3i faultQuarantine = pos(helper, 18, 2, 13);
        BlockPos3i finalDelivery = pos(helper, 10, 2, 25);

        seedOrderedChest(level, rawSource, List.of(
                id("minecraft:stripped_oak_log"), id("create:shaft"),
                id("minecraft:oak_planks")));
        seedEmptyChest(level, woodSource);
        seedEmptyChest(level, alloySource);
        seedEmptyChest(level, woodDelivery);
        seedEmptyChest(level, alloyDelivery);
        seedEmptyChest(level, mergeSource);
        seedEmptyChest(level, woodOverflow);
        seedEmptyChest(level, alloyOverflow);
        seedEmptyChest(level, faultQuarantine);
        seedEmptyChest(level, finalDelivery);
        installLockedHopper(level, splitHopper, splitLock);
        level.setBlockAndUpdate(block(splitHopper), level.getBlockState(block(splitHopper))
                .setValue(HopperBlock.FACING, Direction.WEST));
        installLockedHopper(level, woodMergeHopper, woodMergeLock);
        installLockedHopper(level, alloyMergeHopper, alloyMergeLock);
        level.setBlockAndUpdate(block(alloyMergeHopper),
                level.getBlockState(block(alloyMergeHopper))
                        .setValue(HopperBlock.FACING, Direction.WEST));

        CreateV606GoalDrivenPlanner.Ready woodReady = plan(
                level, "minecraft:oak_planks", 6,
                Map.of(id("minecraft:stripped_oak_log"), 1L),
                pos(helper, 4, 2, 3), "c03_wood");
        CreateV606GoalDrivenPlanner.Ready alloyReady = plan(
                level, "create:cogwheel", 1,
                Map.of(id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L),
                pos(helper, 20, 2, 3), "c03_alloy");
        CreateV606GoalDrivenPlanner.Ready mergeReady = plan(
                level, "create:large_cogwheel", 1,
                Map.of(id("minecraft:oak_planks"), 1L,
                        id("create:cogwheel"), 1L),
                pos(helper, 20, 2, 23), "c03_merge");
        seedChest(level, woodSource, installationMaterials(woodReady));
        seedChest(level, alloySource, installationMaterials(alloyReady));
        seedChest(level, mergeSource, installationMaterials(mergeReady));

        ResourceId line = id("steve_industrial:composite/03_line");
        Node rootNode = new Node(id("steve_industrial:composite/03_split"),
                id("steve_industrial:typed_split"), line, Set.of());
        Node woodNode = new Node(id("steve_industrial:composite/03_wood"),
                id("create:cutting"), line, Set.of());
        Node alloyNode = new Node(id("steve_industrial:composite/03_alloy"),
                id("create:deploying"), line, Set.of());
        Node mergeNode = new Node(id("steve_industrial:composite/03_merge"),
                id("create:deploying"), line, Set.of());
        List<MaterialEdge> edges = List.of(
                new MaterialEdge(id("steve_industrial:composite/03_raw_wood"),
                        rootNode.nodeId(), woodNode.nodeId(),
                        id("minecraft:stripped_oak_log"), 1, 1),
                new MaterialEdge(id("steve_industrial:composite/03_raw_alloy"),
                        rootNode.nodeId(), alloyNode.nodeId(),
                        id("create:shaft"), 1, 1),
                new MaterialEdge(id("steve_industrial:composite/03_raw_plank"),
                        rootNode.nodeId(), alloyNode.nodeId(),
                        id("minecraft:oak_planks"), 1, 1),
                new MaterialEdge(id("steve_industrial:composite/03_planks"),
                        woodNode.nodeId(), mergeNode.nodeId(),
                        id("minecraft:oak_planks"), 1, 6),
                new MaterialEdge(id("steve_industrial:composite/03_cogwheel"),
                        alloyNode.nodeId(), mergeNode.nodeId(),
                        id("create:cogwheel"), 1, 1));
        CompositeProductionGraph graph = new CompositeProductionGraph(
                id("steve_industrial:composite/03"), Shape.BRANCH_MERGE,
                List.of(rootNode, woodNode, alloyNode, mergeNode), edges);
        CreateV606BranchMergeExecution.RootSplit split =
                new CreateV606BranchMergeExecution.RootSplit(
                        rootNode.nodeId(), rawSource, splitHopper, splitLock,
                        id("minecraft:stripped_oak_log"), 1,
                        id("create:shaft"), 1,
                        id("minecraft:oak_planks"), 1);
        CreateV606BranchMergeExecution.HandlerStage wood = branchStage(
                woodNode, woodReady, woodSource, woodDelivery,
                "minecraft:oak_planks", 6,
                fleetStarts(helper, mode, 2, 2, 3));
        CreateV606BranchMergeExecution.HandlerStage alloy = branchStage(
                alloyNode, alloyReady, alloySource, alloyDelivery,
                "create:cogwheel", 1,
                fleetStarts(helper, mode, 3, 27, 3));
        CreateV606BranchMergeExecution.HandlerStage merge = branchStage(
                mergeNode, mergeReady, mergeSource, finalDelivery,
                "create:large_cogwheel", 1,
                fleetStarts(helper, mode, 3, 27, 28));
        List<CreateV606BranchMergeExecution.MergeRoute> routes = List.of(
                new CreateV606BranchMergeExecution.MergeRoute(
                        edges.get(3).edgeId(), woodNode.nodeId(),
                        id("minecraft:oak_planks"), woodMergeHopper, woodMergeLock,
                        woodOverflow),
                new CreateV606BranchMergeExecution.MergeRoute(
                        edges.get(4).edgeId(), alloyNode.nodeId(),
                        id("create:cogwheel"), alloyMergeHopper, alloyMergeLock,
                        alloyOverflow));
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        pos(helper, 1, 2, 1), pos(helper, 31, 7, 31));
        CreateV606BranchMergeExecution.StartResult start =
                CreateV606BranchMergeExecution.start(
                        level, graph, split, wood, alloy, merge, routes,
                        mode, region);
        check(start instanceof CreateV606BranchMergeExecution.Started,
                "Composite-03 " + mode + " did not start: " + start);
        CreateV606BranchMergeExecution.Session session =
                ((CreateV606BranchMergeExecution.Started) start).session();
        if (fault == Composite03Fault.CONTAMINATION) {
            appendExact(level, woodSource, id("minecraft:cobblestone"), 1);
        }
        AtomicBoolean faultInjected = new AtomicBoolean();

        helper.onEachTick(() -> {
            CreateV606BranchMergeExecution.TickResult result = session.tick();
            boolean injectBackpressure =
                    result instanceof CreateV606BranchMergeExecution.Progress progress
                            && fault == Composite03Fault.BACKPRESSURE
                            && progress.phase()
                            == CreateV606BranchMergeExecution.Phase.MERGE_ROUTING;
            boolean injectBranchLoss =
                    result instanceof CreateV606BranchMergeExecution.Progress progress
                            && fault == Composite03Fault.BRANCH_INPUT_LOSS
                            && progress.phase()
                            == CreateV606BranchMergeExecution.Phase.BRANCHES;
            if ((injectBackpressure || injectBranchLoss)
                    && faultInjected.compareAndSet(false, true)) {
                if (fault == Composite03Fault.BACKPRESSURE) {
                    appendExact(level, mergeSource, id("minecraft:oak_planks"), 2);
                } else {
                    moveExactOne(level, alloySource, faultQuarantine, id("create:shaft"));
                }
                result = session.tick();
            }
            if (result instanceof CreateV606BranchMergeExecution.Failed failed) {
                if (fault == Composite03Fault.NONE) {
                    helper.fail("Composite-03 " + mode + " failed: " + failed);
                    return;
                }
                String expected = fault == Composite03Fault.BRANCH_INPUT_LOSS
                        ? "Independent branch failed before merge"
                        : "contamination/backpressure";
                check(failed.detail().contains(expected),
                        "Composite-03 directed fault returned another failure: " + failed);
                if (fault == Composite03Fault.BRANCH_INPUT_LOSS) {
                    moveExactOne(
                            level, faultQuarantine, alloySource, id("create:shaft"));
                }
                CreateV606BranchMergeExecution.FailureCleanupReport cleanup =
                        session.cleanupFailed(
                                id("steve_industrial:reason/composite_03_fault_cleanup"));
                check(cleanup.remainingMachinePositions() == 0
                                && cleanup.remainingRouteBlocks() == 0,
                        "Composite-03 directed fault cleanup left owned state: " + cleanup);
                if (fault == Composite03Fault.CONTAMINATION) {
                    check(failed.coordinatorSnapshot().nodeStatuses().get(rootNode.nodeId())
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.FAILED
                                    && count(level, finalDelivery,
                                            id("create:large_cogwheel")) == 0,
                            "Composite-03 contamination crossed the root refusal boundary");
                } else if (fault == Composite03Fault.BACKPRESSURE) {
                    check(failed.coordinatorSnapshot().nodeStatuses().get(woodNode.nodeId())
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.SUCCEEDED
                                    && failed.coordinatorSnapshot().nodeStatuses()
                                    .get(alloyNode.nodeId())
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.SUCCEEDED
                                    && failed.coordinatorSnapshot().nodeStatuses()
                                    .get(mergeNode.nodeId())
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.FAILED
                                    && count(level, woodDelivery,
                                            id("minecraft:oak_planks")) == 6
                                    && count(level, alloyDelivery,
                                            id("create:cogwheel")) == 1,
                            "Composite-03 merge backpressure lost completed branch outputs: "
                                    + "woodDelivery=" + count(
                                    level, woodDelivery, id("minecraft:oak_planks"))
                                    + " alloyDelivery=" + count(
                                    level, alloyDelivery, id("create:cogwheel"))
                                    + " mergePlanks=" + count(
                                    level, mergeSource, id("minecraft:oak_planks"))
                                    + " mergeCogwheels=" + count(
                                    level, mergeSource, id("create:cogwheel"))
                                    + " cleanup=" + cleanup);
                } else {
                    check(failed.coordinatorSnapshot().nodeStatuses().get(alloyNode.nodeId())
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.FAILED
                                    && failed.coordinatorSnapshot().nodeStatuses()
                                    .get(woodNode.nodeId())
                                    != dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.FAILED
                                    && routeDelivered(level, woodSource,
                                            id("minecraft:stripped_oak_log"),
                                            installationMaterials(woodReady)) == 1
                                    && routeDelivered(level, alloySource,
                                            id("create:shaft"),
                                            installationMaterials(alloyReady)) == 1
                                    && routeDelivered(level, alloySource,
                                            id("minecraft:oak_planks"),
                                            installationMaterials(alloyReady)) == 1
                                    && totalItems(level, faultQuarantine) == 0,
                            "Composite-03 failed branch contaminated its sibling/resources: "
                                    + "woodStatus=" + failed.coordinatorSnapshot()
                                            .nodeStatuses().get(woodNode.nodeId())
                                    + " alloyStatus=" + failed.coordinatorSnapshot()
                                            .nodeStatuses().get(alloyNode.nodeId())
                                    + " woodSourceLog=" + count(
                                            level, woodSource, id("minecraft:stripped_oak_log"))
                                    + " alloySourceShaft=" + count(
                                            level, alloySource, id("create:shaft"))
                                    + " alloySourcePlanks=" + count(
                                            level, alloySource, id("minecraft:oak_planks"))
                                    + " quarantine=" + totalItems(level, faultQuarantine)
                                    + " alloyInstall=" + installationMaterials(alloyReady)
                                    + " woodInstall=" + installationMaterials(woodReady));
                }
                LOGGER.info(
                        "COMPOSITE_03_{}_FAULT PASS typedRefusal=true exactReconciliation=true branchIsolation=true cleanup=true detail={}",
                        fault, failed.detail());
                helper.succeed();
                return;
            }
            if (result instanceof CreateV606BranchMergeExecution.Progress) return;
            if (fault != Composite03Fault.NONE) {
                helper.fail("Composite-03 directed fault unexpectedly completed: " + fault);
                return;
            }
            CreateV606BranchMergeExecution.Completed completed =
                    (CreateV606BranchMergeExecution.Completed) result;
            check(count(level, finalDelivery, id("create:large_cogwheel")) == 1
                            && count(level, woodOverflow, id("minecraft:oak_planks")) == 5
                            && count(level, alloyOverflow, id("create:cogwheel")) == 0
                            && completed.completedPhysicalRoutes() == 4
                            && completed.handlerCompletions().size() == 3,
                    "Composite-03 final output/routes/handlers are incomplete");
            check(completed.coordinatorSnapshot().nodeStatuses().values().stream()
                            .allMatch(value -> value
                                    == dev.stevecreate.agent.core.execution.composite
                                    .CompositeProductionCoordinator.NodeStatus.SUCCEEDED),
                    "Composite-03 DAG did not reach exact merge completion");
            check(completed.mode() == mode
                            && completed.handlerCompletions().stream()
                            .allMatch(value -> value.mode() == mode),
                    "Composite-03 handler mode changed inside the branch/merge graph");
            boolean botMovement = mode != ExecutionMode.BOTS
                    || completed.handlerCompletions().stream()
                            .map(value -> value.workerActivities().size())
                            .sorted().toList().equals(List.of(2, 3, 3))
                    && completed.handlerCompletions().stream().allMatch(value ->
                            value.workerActivities().stream().allMatch(worker ->
                                    worker.assignmentsStarted() > 0
                                            && worker.movementTicks() > 0));
            check(botMovement,
                    "Composite-03 Bots did not visibly execute every handler stage");
            check(mode == ExecutionMode.DIRECT
                            ? completed.peakConcurrentWorkers() == 0
                                    && completed.retiredBranchWorkers() == 0
                            : completed.peakConcurrentWorkers() == 5
                                    && completed.retiredBranchWorkers() == 5,
                    "Composite-03 did not enforce the bounded five-worker branch fleet");
            boolean hybridBotRoute = completed.handlerCompletions().stream()
                    .flatMap(value -> value.taskResults().stream())
                    .flatMap(value -> value.evidence().stream())
                    .anyMatch(value -> value.provenance().startsWith("hybrid-route=bots;"));
            boolean hybridDirectRoute = completed.handlerCompletions().stream()
                    .flatMap(value -> value.taskResults().stream())
                    .flatMap(value -> value.evidence().stream())
                    .anyMatch(value -> value.provenance().startsWith("hybrid-route=direct;"));
            boolean hybridProvenance = mode != ExecutionMode.HYBRID
                    || hybridBotRoute && hybridDirectRoute;
            check(hybridProvenance,
                    "Composite-03 Hybrid lost a physical backend provenance route");
            CreateV606BranchMergeExecution.CleanupReport cleanup = session.cleanup();
            check(cleanup.remainingMachinePositions() == 0
                            && cleanup.remainingRouteBlocks() == 0,
                    "Composite-03 cleanup left owned blocks: " + cleanup);
            LOGGER.info("COMPOSITE_03_{} PASS nodes=4 edges=5 physicalRoutes=4 realBranches=2 mergeWaited=true typedFilters=true final=create:large_cogwheel@1 preservedExcess=minecraft:oak_planks@5 reloadPerHandler=true cleanup=true installationEscrow=true botMovement={} hybridProvenance={} graph={} fingerprint={}",
                    mode.serializedName().toUpperCase(), botMovement, hybridProvenance,
                    completed.graphId(), completed.graphFingerprint());
            helper.succeed();
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_02_direct", timeoutTicks = 12_000)
    public static void composite02DirectCancelIsolation(GameTestHelper helper) {
        runComposite02(helper, ExecutionMode.DIRECT);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_02_bots", timeoutTicks = 12_000)
    public static void composite02BotsCancelIsolation(GameTestHelper helper) {
        runComposite02(helper, ExecutionMode.BOTS);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "phase_iv_composite_02_hybrid", timeoutTicks = 12_000)
    public static void composite02HybridCancelIsolation(GameTestHelper helper) {
        runComposite02(helper, ExecutionMode.HYBRID);
    }

    private static void runComposite02(GameTestHelper helper, ExecutionMode mode) {
        ServerLevel level = helper.getLevel();
        clearArenaItemEntities(helper);
        prepareArena(helper);
        BlockPos3i sourceA = pos(helper, 2, 2, 10);
        BlockPos3i deliveryA = pos(helper, 9, 3, 10);
        BlockPos3i finalA = pos(helper, 8, 2, 10);
        BlockPos3i sourceB = pos(helper, 18, 2, 10);
        BlockPos3i deliveryB = pos(helper, 11, 3, 10);
        BlockPos3i finalB = pos(helper, 12, 2, 10);
        BlockPos3i hopperA = pos(helper, 9, 2, 10);
        BlockPos3i sharedPower = pos(helper, 10, 2, 10);
        BlockPos3i hopperB = pos(helper, 11, 2, 10);
        seedEmptyChest(level, deliveryA);
        seedEmptyChest(level, finalA);
        seedEmptyChest(level, deliveryB);
        seedEmptyChest(level, finalB);
        level.setBlockAndUpdate(block(hopperA), Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(block(sharedPower), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(block(hopperB), Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.EAST));

        CreateV606GoalDrivenPlanner.Ready readyA = plan(
                level, "minecraft:stripped_oak_log", 1,
                Map.of(id("minecraft:oak_log"), 1L), pos(helper, 4, 2, 3), "c02_line_a");
        CreateV606GoalDrivenPlanner.Ready readyB = plan(
                level, "create:andesite_alloy", 1,
                Map.of(id("minecraft:andesite"), 1L, id("minecraft:iron_nugget"), 1L),
                pos(helper, 20, 2, 3), "c02_line_b");
        seedChest(level, sourceA, mergeMaterials(
                Map.of(id("minecraft:oak_log"), 1L), installationMaterials(readyA)));
        seedChest(level, sourceB, mergeMaterials(Map.of(
                id("minecraft:andesite"), 1L,
                id("minecraft:iron_nugget"), 1L), installationMaterials(readyB)));
        ResourceId sessionA = id("steve_industrial:composite/02_line_a");
        ResourceId sessionB = id("steve_industrial:composite/02_line_b");
        CreateV606ConcurrentExecution.Line lineA = new CreateV606ConcurrentExecution.Line(
                sessionA, readyA.executionReadyPlan(), readyA.runtime(), sourceA, deliveryA,
                finalA, id("minecraft:stripped_oak_log"), 1,
                fleetStarts(helper, mode, 2, 2, 20), installationMaterials(readyA));
        CreateV606ConcurrentExecution.Line lineB = new CreateV606ConcurrentExecution.Line(
                sessionB, readyB.executionReadyPlan(), readyB.runtime(), sourceB, deliveryB,
                finalB, id("create:andesite_alloy"), 1,
                fleetStarts(helper, mode, 3, 20, 20), installationMaterials(readyB));
        ResourceId reservationId = id("steve_industrial:composite/02_shared_interlock");
        SharedInfrastructureReservation reservation = new SharedInfrastructureReservation(
                reservationId, readyA.executionReadyPlan().physicalPlan().id(),
                id("steve_industrial:infrastructure/shared_output_interlock"),
                Set.of(sessionA), List.of(sharedPower), ReservationStatus.ACTIVE, 0,
                level.getGameTime());
        CreateV606ConcurrentExecution.SharedOutputInterlock interlock =
                new CreateV606ConcurrentExecution.SharedOutputInterlock(
                        sharedPower, hopperA, hopperB);
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        pos(helper, 1, 2, 1), pos(helper, 31, 7, 31));
        CreateV606ConcurrentExecution.StartResult start =
                CreateV606ConcurrentExecution.start(
                        level, lineA, lineB, interlock, reservation, mode, region);
        check(start instanceof CreateV606ConcurrentExecution.Started,
                "Composite-02 did not start: " + start);
        CreateV606ConcurrentExecution.Session session =
                ((CreateV606ConcurrentExecution.Started) start).session();
        AtomicBoolean cancelled = new AtomicBoolean();

        helper.onEachTick(() -> {
            CreateV606ConcurrentExecution.TickResult result = session.tick();
            if (result instanceof CreateV606ConcurrentExecution.Failed failed) {
                helper.fail("Composite-02 failed: " + failed.detail());
                return;
            }
            if (!cancelled.get()) {
                if (mode == ExecutionMode.BOTS
                        && session.lineAWorkerActivities().stream().anyMatch(value ->
                                value.assignmentsStarted() < 1
                                        || value.movementTicks() < 1)) {
                    return;
                }
                CreateV606ThreeModeExecution.CancellationReport cancellation =
                        session.cancelLineA(id("steve_industrial:reason/composite_02_cancel_a"));
                check(cancellation.sourceRestored() && cancellation.deliveryEmpty()
                                && level.getBlockState(block(sharedPower))
                                        .is(Blocks.REDSTONE_BLOCK),
                        "Composite-02 line A cancel did not preserve source/shared infrastructure");
                cancelled.set(true);
                return;
            }
            if (result instanceof CreateV606ConcurrentExecution.Progress progress) {
                check(progress.lineACancelled(),
                        "Composite-02 lost line A cancellation state");
                if (!progress.lineBCompleted()) {
                    check(progress.sharedReferenceCount() == 1
                                    && progress.interlockPresent(),
                            "Composite-02 lost the one-owner shared interlock while B was active");
                } else {
                    check(progress.sharedReferenceCount() == 0
                                    && !progress.interlockPresent(),
                            "Composite-02 retained shared infrastructure after the final release");
                }
                return;
            }
            CreateV606ConcurrentExecution.Completed completed =
                    (CreateV606ConcurrentExecution.Completed) result;
            check(count(level, finalA, id("minecraft:stripped_oak_log")) == 0
                            && count(level, finalB, id("create:andesite_alloy")) == 1,
                    "Composite-02 independent outputs changed");
            check(completed.finalReservation().status() == ReservationStatus.RELEASED
                            && completed.finalReservation().referenceCount() == 0
                            && completed.independentGraphs()
                            && completed.independentWorkers()
                            && completed.peakConcurrentWorkers()
                                    == (mode == ExecutionMode.DIRECT ? 0 : 5)
                            && completed.allBotsActive()
                            && completed.wrapperReloadReconciled()
                            && completed.wrapperGraphFingerprint().matches("[0-9a-f]{64}")
                            && completed.lineBCompletion().reloadReconciled(),
                    "Composite-02 reservation/task isolation evidence is incomplete");
            CreateV606ConcurrentExecution.CleanupReport cleanup = session.cleanup();
            check(cleanup.lineB().remainingPositions() == 0
                            && cleanup.remainingSharedBlocks() == 0,
                    "Composite-02 cleanup left line B or shared infrastructure");
            LOGGER.info("COMPOSITE_02_{} PASS concurrentLines=2 cancelA=true lineBContinued=true sharedReferenceAfterCancel=1 sharedCleanupAfterAll=true independentTargets=true independentGraphs=true independentWorkers=true finalB=create:andesite_alloy@1 reloadB=true wrapperReload=true cleanup=true installationEscrow=true",
                    mode);
            helper.succeed();
        });
    }

    private static List<BlockPos3i> starts(
            GameTestHelper helper, ExecutionMode mode, int z) {
        return starts(helper, mode, 2, z);
    }

    private static List<BlockPos3i> starts(
            GameTestHelper helper, ExecutionMode mode, int x, int z) {
        return mode == ExecutionMode.DIRECT ? List.of() : List.of(
                pos(helper, x, 2, z),
                pos(helper, x + 1, 2, z));
    }

    private static List<BlockPos3i> fleetStarts(
            GameTestHelper helper,
            ExecutionMode mode,
            int workers,
            int x,
            int z) {
        if (mode == ExecutionMode.DIRECT) return List.of();
        List<BlockPos3i> starts = new java.util.ArrayList<>();
        for (int index = 0; index < workers; index++) {
            starts.add(pos(helper, x + index, 2, z));
        }
        return List.copyOf(starts);
    }

    private static CreateV606GoalDrivenPlanner.Ready plan(
            ServerLevel level,
            String target,
            int quantity,
            Map<ResourceId, Long> raw,
            BlockPos3i anchor,
            String suffix) {
        CreateV606GoalDrivenPlanner.PlanningResult result = CreateV606GoalDrivenPlanner.plan(
                level, id(target), quantity, raw, anchor, QuarterTurn.ZERO,
                id("steve_industrial:composite/01_" + suffix), raw,
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                MaterialConstraints.none());
        if (result instanceof CreateV606GoalDrivenPlanner.Ready ready) return ready;
        throw new GameTestAssertException("Composite plan failed: " + result);
    }

    private static Node node(String node, String capability) {
        return new Node(id(node), id(capability), id("steve_industrial:composite/01_line"), Set.of());
    }

    private static CreateV606CompositeExecution.Stage stage(
            Node node,
            CreateV606GoalDrivenPlanner.Ready ready,
            BlockPos3i source,
            BlockPos3i delivery,
            String target,
            long quantity,
            List<BlockPos3i> starts) {
        return new CreateV606CompositeExecution.Stage(
                node.nodeId(), ready.executionReadyPlan(), ready.runtime(), source, delivery,
                id(target), quantity, starts, installationMaterials(ready));
    }

    private static CreateV606CompositeExecution.HopperRoute route(
            MaterialEdge edge,
            BlockPos3i hopper,
            BlockPos3i lock,
            Optional<BlockPos3i> overflow) {
        return new CreateV606CompositeExecution.HopperRoute(
                edge.edgeId(), edge.producerNodeId(), edge.consumerNodeId(), edge.resourceId(),
                edge.quantity(), hopper, lock, overflow);
    }

    private static CreateV606BranchMergeExecution.HandlerStage branchStage(
            Node node,
            CreateV606GoalDrivenPlanner.Ready ready,
            BlockPos3i source,
            BlockPos3i delivery,
            String target,
            long quantity,
            List<BlockPos3i> starts) {
        return new CreateV606BranchMergeExecution.HandlerStage(
                node.nodeId(), ready.executionReadyPlan(), ready.runtime(), source,
                delivery, id(target), quantity, starts, installationMaterials(ready));
    }

    /**
     * What a physical route actually put into a branch chest, above what was already there.
     *
     * <p>A branch source chest is never empty: it is seeded with that stage's own
     * installation materials. Asserting its absolute count assumed those two sets never
     * overlap, which stopped being true when the survival-power promotion made the alloy
     * branch bill its own {@code create:shaft=2} — "exactly one shaft" then described a state
     * the run could never reach, and the isolation check failed on a healthy branch that had
     * returned its input exactly.</p>
     *
     * <p>Subtracting the baseline makes the assertion stricter, not weaker: the difference is
     * only one if the returned input is exact <em>and</em> the installation materials are
     * still all there. The executor measures the same quantity the same way.</p>
     */
    private static long routeDelivered(
            ServerLevel level,
            BlockPos3i position,
            ResourceId resource,
            Map<ResourceId, Long> installationMaterials) {
        return count(level, position, resource)
                - installationMaterials.getOrDefault(resource, 0L);
    }

    private static Map<ResourceId, Long> installationMaterials(
            CreateV606GoalDrivenPlanner.Ready ready) {
        LinkedHashMap<ResourceId, Long> result = new LinkedHashMap<>();
        ready.executionReadyPlan().physicalPlan().placements().stream()
                .flatMap(value -> value.components().stream())
                .forEach(value -> {
                    Item item = ForgeRegistries.ITEMS.getValue(
                            ResourceLocation.fromNamespaceAndPath(
                                    value.blockId().namespace(), value.blockId().path()));
                    if (item != null && item != net.minecraft.world.item.Items.AIR) {
                        result.merge(value.blockId(), 1L, Math::addExact);
                    }
                });
        return Map.copyOf(result);
    }

    private static Map<ResourceId, Long> mergeMaterials(
            Map<ResourceId, Long> process,
            Map<ResourceId, Long> installations) {
        LinkedHashMap<ResourceId, Long> result = new LinkedHashMap<>(process);
        installations.forEach((resource, quantity) ->
                result.merge(resource, quantity, Math::addExact));
        return Map.copyOf(result);
    }

    private static void prepareArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 31; x++) {
            for (int z = 1; z <= 31; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 1, z)),
                        Blocks.STONE.defaultBlockState());
                for (int y = 2; y <= 7; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Clearing old owned blocks can itself drop their item forms.  Remove
        // those after the block reset, before this scenario seeds its inputs.
        clearArenaItemEntities(helper);
    }

    /**
     * GameTest templates are reused in a compact world.  Remove only stale
     * dropped-item entities inside this test's bounded arena so one prior
     * scenario cannot be mistaken for the next C-07 output-lane observation.
     * The production handler remains strict and still refuses any unexpected
     * live entity in its lane.
     */
    private static void clearArenaItemEntities(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        AABB bounds = new AABB(origin).inflate(32.0D, 16.0D, 32.0D);
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds, Entity::isAlive)
                .forEach(Entity::discard);
    }

    private static void installLockedHopper(
            ServerLevel level, BlockPos3i hopper, BlockPos3i lock) {
        level.setBlockAndUpdate(block(hopper), Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(block(lock), Blocks.REDSTONE_BLOCK.defaultBlockState());
        check(level.getBlockState(block(hopper)).is(Blocks.HOPPER)
                        && level.getBlockState(block(lock)).is(Blocks.REDSTONE_BLOCK),
                "Could not install locked physical hopper route");
    }

    private static void seedEmptyChest(ServerLevel level, BlockPos3i position) {
        level.setBlockAndUpdate(block(position), Blocks.CHEST.defaultBlockState());
        check(level.getBlockEntity(block(position)) instanceof ChestBlockEntity,
                "Could not create composite boundary chest");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        chest.clearContent();
        chest.setChanged();
    }

    private static void seedChest(
            ServerLevel level, BlockPos3i position, Map<ResourceId, Long> materials) {
        seedEmptyChest(level, position);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        int slot = 0;
        for (Map.Entry<ResourceId, Long> entry : materials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(ResourceId::toString))).toList()) {
            Item item = item(entry.getKey());
            chest.setItem(slot++, new ItemStack(item, Math.toIntExact(entry.getValue())));
        }
        chest.setChanged();
    }

    private static void seedOrderedChest(
            ServerLevel level, BlockPos3i position, List<ResourceId> materials) {
        seedEmptyChest(level, position);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        for (int index = 0; index < materials.size(); index++) {
            chest.setItem(index, new ItemStack(item(materials.get(index)), 1));
        }
        chest.setChanged();
    }

    private static void appendExact(
            ServerLevel level, BlockPos3i position, ResourceId resource, int quantity) {
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) {
                chest.setItem(slot, new ItemStack(item(resource), quantity));
                chest.setChanged();
                return;
            }
        }
        throw new GameTestAssertException("Composite fault chest has no empty slot");
    }

    private static void moveExactOne(
            ServerLevel level,
            BlockPos3i sourcePosition,
            BlockPos3i destinationPosition,
            ResourceId resource) {
        ChestBlockEntity source =
                (ChestBlockEntity) level.getBlockEntity(block(sourcePosition));
        ChestBlockEntity destination =
                (ChestBlockEntity) level.getBlockEntity(block(destinationPosition));
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            ItemStack stack = source.getItem(slot);
            if (!stack.isEmpty() && !stack.hasTag() && stack.is(item(resource))) {
                ItemStack moved = source.removeItem(slot, 1);
                for (int target = 0; target < destination.getContainerSize(); target++) {
                    if (destination.getItem(target).isEmpty()) {
                        destination.setItem(target, moved);
                        source.setChanged();
                        destination.setChanged();
                        return;
                    }
                }
                throw new GameTestAssertException(
                        "Composite fault quarantine has no empty slot");
            }
        }
        throw new GameTestAssertException(
                "Composite fault source lacks exact resource " + resource);
    }

    private static long totalItems(ServerLevel level, BlockPos3i position) {
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        long total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            total += chest.getItem(slot).getCount();
        }
        return total;
    }

    private static long count(ServerLevel level, BlockPos3i position, ResourceId resource) {
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        long total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item(resource))) total += stack.getCount();
        }
        return total;
    }

    private static Item item(ResourceId resource) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(resource.toString()));
        check(item != null && item != net.minecraft.world.item.Items.AIR,
                "Unknown composite item " + resource);
        return item;
    }

    private static BlockPos3i pos(GameTestHelper helper, int x, int y, int z) {
        BlockPos value = helper.absolutePos(new BlockPos(x, y, z));
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
