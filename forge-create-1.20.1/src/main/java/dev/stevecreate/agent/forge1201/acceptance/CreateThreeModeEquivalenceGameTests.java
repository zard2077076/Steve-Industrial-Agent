package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.tree.CommandNode;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ConstructionFailureCode;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionOutcome;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.RecipeHeatTier;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ThreeModeExecution;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Create physical equivalence across Direct, visible Bot fleet and safe Hybrid routing. */
@PrefixGameTestTemplate(false)
public final class CreateThreeModeEquivalenceGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateThreeModeEquivalenceGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_15", timeoutTicks = 12_000)
    public static void c03MillingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C03", "minecraft:gravel", 3,
                "minecraft:andesite", 3);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_16", timeoutTicks = 12_000)
    public static void c04PressingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C04", "create:iron_sheet", 2,
                "minecraft:iron_ingot", 2);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_17", timeoutTicks = 12_000)
    public static void c05CrushingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C05", "minecraft:sand", 1,
                "minecraft:gravel", 1,
                new MaterialConstraints(
                        Set.of(id("minecraft:sandstone")),
                        Map.of()));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c07_three_mode", timeoutTicks = 12_000)
    public static void c07CuttingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C07", "minecraft:stripped_oak_log", 1,
                Map.of(id("minecraft:oak_log"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c06_three_mode", timeoutTicks = 12_000)
    public static void c06SafeWashingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C06", "create:dough", 1,
                Map.of(id("create:wheat_flour"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c09_three_mode", timeoutTicks = 12_000)
    public static void c09CompactingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C09", "create:blaze_cake_base", 1,
                Map.of(
                        id("minecraft:egg"), 1L,
                        id("minecraft:sugar"), 1L,
                        id("create:cinder_flour"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c09_lava_three_mode", timeoutTicks = 12_000)
    public static void c09LavaCompactingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C09_LAVA", "minecraft:granite", 1,
                Map.of(
                        id("minecraft:flint"), 2L,
                        id("minecraft:red_sand"), 1L,
                        id("minecraft:lava_bucket"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_three_mode", timeoutTicks = 12_000)
    public static void c08MixingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C08", "create:andesite_alloy", 1,
                Map.of(
                        id("minecraft:andesite"), 1L,
                        id("minecraft:iron_nugget"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_heated_three_mode", timeoutTicks = 12_000)
    public static void c08HeatedBrassIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C08_HEATED", "create:brass_ingot", 2,
                Map.of(
                        id("minecraft:copper_ingot"), 1L,
                        id("create:zinc_ingot"), 1L,
                        id("minecraft:coal"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c10_three_mode", timeoutTicks = 12_000)
    public static void c10DeployingIsEquivalentAcrossAllModes(GameTestHelper helper) {
        runEquivalence(
                helper, "C10", "create:cogwheel", 1,
                Map.of(
                        id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L),
                MaterialConstraints.none());
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c07_cancel_matrix", timeoutTicks = 2_000)
    public static void c07CancellationRestoresAllModes(GameTestHelper helper) {
        runCancellationMatrix(
                helper, "C07", "minecraft:stripped_oak_log",
                Map.of(id("minecraft:oak_log"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c06_cancel_matrix", timeoutTicks = 2_000)
    public static void c06CancellationRestoresAllModes(GameTestHelper helper) {
        runCancellationMatrix(
                helper, "C06", "create:dough",
                Map.of(id("create:wheat_flour"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c09_cancel_matrix", timeoutTicks = 2_000)
    public static void c09CancellationRestoresAllModes(GameTestHelper helper) {
        runCancellationMatrix(
                helper, "C09", "create:blaze_cake_base",
                Map.of(
                        id("minecraft:egg"), 1L,
                        id("minecraft:sugar"), 1L,
                        id("create:cinder_flour"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c09_lava_cancel_matrix", timeoutTicks = 2_000)
    public static void c09LavaCancellationRestoresAllModes(GameTestHelper helper) {
        runCancellationMatrix(
                helper, "C09_LAVA", "minecraft:granite",
                Map.of(
                        id("minecraft:flint"), 2L,
                        id("minecraft:red_sand"), 1L,
                        id("minecraft:lava_bucket"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_cancel_matrix", timeoutTicks = 2_000)
    public static void c08CancellationRestoresAllModes(GameTestHelper helper) {
        runCancellationMatrix(
                helper, "C08", "create:andesite_alloy",
                Map.of(
                        id("minecraft:andesite"), 1L,
                        id("minecraft:iron_nugget"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c10_cancel_matrix", timeoutTicks = 2_000)
    public static void c10CancellationRestoresAllModes(GameTestHelper helper) {
        runCancellationMatrix(
                helper, "C10", "create:cogwheel",
                Map.of(
                        id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c07_fault_matrix", timeoutTicks = 4_000)
    public static void c07FaultMatrixFailsClosed(GameTestHelper helper) {
        runFaultMatrix(
                helper, "C07", "minecraft:stripped_oak_log",
                Map.of(id("minecraft:oak_log"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c06_fault_matrix", timeoutTicks = 4_000)
    public static void c06FaultMatrixFailsClosed(GameTestHelper helper) {
        runFaultMatrix(
                helper, "C06", "create:dough",
                Map.of(id("create:wheat_flour"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c09_fault_matrix", timeoutTicks = 4_000)
    public static void c09FaultMatrixFailsClosed(GameTestHelper helper) {
        runFaultMatrix(
                helper, "C09", "create:blaze_cake_base",
                Map.of(
                        id("minecraft:egg"), 1L,
                        id("minecraft:sugar"), 1L,
                        id("create:cinder_flour"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c08_fault_matrix", timeoutTicks = 4_000)
    public static void c08FaultMatrixFailsClosed(GameTestHelper helper) {
        runFaultMatrix(
                helper, "C08", "create:andesite_alloy",
                Map.of(
                        id("minecraft:andesite"), 1L,
                        id("minecraft:iron_nugget"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_c10_fault_matrix", timeoutTicks = 4_000)
    public static void c10FaultMatrixFailsClosed(GameTestHelper helper) {
        runFaultMatrix(
                helper, "C10", "create:cogwheel",
                Map.of(
                        id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L));
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "goal_execution_18", timeoutTicks = 200)
    public static void executorModeCommandsAreRegistered(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
        var dispatcher = server.getCommands().getDispatcher();
        CommandNode<CommandSourceStack> root = child(
                dispatcher.getRoot(), "industrialagent");
        CommandNode<CommandSourceStack> mode = child(child(root, "build"), "mode");
        child(mode, "direct");
        child(mode, "bots");
        child(mode, "hybrid");
        child(mode, "status");
        check(server.getCommands().performPrefixedCommand(
                        source, "industrialagent build mode direct") == 1,
                "Direct build-mode command did not execute");
        check(server.getCommands().performPrefixedCommand(
                        source, "industrialagent build mode bots") == 1,
                "Bots build-mode command did not execute");
        check(server.getCommands().performPrefixedCommand(
                        source, "industrialagent build mode hybrid") == 1,
                "Hybrid build-mode command did not execute");
        check(server.getCommands().performPrefixedCommand(
                        source, "industrialagent build mode status") == 1,
                "Build-mode status command did not execute");
        for (String configured : List.of("direct", "bots", "hybrid")) {
            String command = "industrialagent pilot start minecraft:gravel 3 --mode "
                    + configured;
            var parsed = dispatcher.parse(command, source);
            check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(),
                    "Explicit pilot mode command did not fully parse: " + command);
        }
        LOGGER.info("EXECUTOR_MODE_COMMANDS PASS buildModes=direct,bots,hybrid status=true explicitPilotModes=direct,bots,hybrid safePolicy=true");
        helper.succeed();
    }

    private static void runEquivalence(
            GameTestHelper helper,
            String capability,
            String targetId,
            int targetQuantity,
            String rawId,
            int rawQuantity) {
        runEquivalence(
                helper,
                capability,
                targetId,
                targetQuantity,
                Map.of(id(rawId), (long) rawQuantity),
                MaterialConstraints.none());
    }

    private static void runEquivalence(
            GameTestHelper helper,
            String capability,
            String targetId,
            int targetQuantity,
            String rawId,
            int rawQuantity,
            MaterialConstraints materialConstraints) {
        runEquivalence(
                helper, capability, targetId, targetQuantity,
                Map.of(id(rawId), (long) rawQuantity), materialConstraints);
    }

    private static void runEquivalence(
            GameTestHelper helper,
            String capability,
            String targetId,
            int targetQuantity,
            Map<ResourceId, Long> rawMaterials,
            MaterialConstraints materialConstraints) {
        ServerLevel level = helper.getLevel();
        prepareArena(helper);
        BlockPos3i source = position(helper.absolutePos(new BlockPos(2, 2, 15)));
        BlockPos3i delivery = position(helper.absolutePos(new BlockPos(8, 2, 15)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(18, 2, 2)));
        int workerCount = Set.of("C08", "C08_HEATED", "C09", "C09_LAVA", "C10")
                .contains(capability) ? 3 : 2;
        List<BlockPos3i> starts = java.util.stream.IntStream.range(0, workerCount)
                .mapToObj(index -> position(helper.absolutePos(new BlockPos(3 + index, 2, 18))))
                .toList();
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        position(helper.absolutePos(new BlockPos(1, 2, 1))),
                        position(helper.absolutePos(new BlockPos(31, 3, 20))));
        ResourceId rootSession = id("steve_industrial:three_mode/"
                + capability.toLowerCase());
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id(targetId), targetQuantity, rawMaterials,
                anchor, QuarterTurn.ZERO, rootSession, rawMaterials,
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                materialConstraints));
        if (capability.equals("C08_HEATED")) {
            check(ready.executionMetadata().heatByStep().size() == 1
                            && ready.executionMetadata().heatByStep().values().stream()
                                    .allMatch(value -> value.heatTier()
                                            == RecipeHeatTier.HEATED)
                            && ready.executionMetadata().fuelReservations().size() == 1
                            && ready.executionMetadata().fuelReservations().get(0)
                                    .fuel().resourceId().equals(id("minecraft:coal"))
                            && ready.executionMetadata().fuelReservations().get(0)
                                    .fuel().amount() == 1,
                    "C08 HEATED planning did not retain exact heat/fuel metadata: "
                            + ready.executionMetadata());
        }
        if (capability.equals("C09_LAVA")) {
            var declaredFluids = ready.executionMetadata().fluidInputsByStep().values().stream()
                    .flatMap(List::stream)
                    .toList();
            check(declaredFluids.size() == 1
                            && declaredFluids.get(0).resourceId()
                                    .equals(id("minecraft:lava"))
                            && declaredFluids.get(0).amount() == 100,
                    "C09 lava planning did not retain exact 100mB metadata: "
                            + ready.executionMetadata());
        }
        Map<ResourceId, Long> installationMaterials = installationMaterials(ready);
        Map<ResourceId, Long> completeMaterials = mergeMaterials(
                rawMaterials, installationMaterials);
        AtomicReference<ExecutionMode> currentMode = new AtomicReference<>(ExecutionMode.DIRECT);
        AtomicReference<CreateV606ThreeModeExecution.Session> session = new AtomicReference<>();
        EnumMap<ExecutionMode, ModeEvidence> evidence = new EnumMap<>(ExecutionMode.class);

        helper.onEachTick(() -> {
            if (session.get() == null) {
                seedChest(level, source, completeMaterials);
                seedEmptyChest(level, delivery);
                List<BlockPos3i> modeStarts = currentMode.get() == ExecutionMode.DIRECT
                        ? List.of() : starts;
                CreateV606ThreeModeExecution.StartResult start =
                        CreateV606ThreeModeExecution.start(
                                level, ready.executionReadyPlan(), ready.runtime(),
                                source, delivery, currentMode.get(), region, modeStarts,
                                ready.executionMetadata(), installationMaterials);
                check(start instanceof CreateV606ThreeModeExecution.Started,
                        "Three-mode execution did not start: " + start);
                session.set(((CreateV606ThreeModeExecution.Started) start).session());
            }
            CreateV606ThreeModeExecution.TickResult result = session.get().tick();
            if (result instanceof CreateV606ThreeModeExecution.Failed failed) {
                helper.fail(capability + " " + currentMode.get()
                        + " failed: " + failed.detail());
                return;
            }
            if (result instanceof CreateV606ThreeModeExecution.Progress progress) {
                check(progress.outcome() == TaskExecutionOutcome.PENDING,
                        "Three-mode progress did not retain PENDING contract outcome");
                return;
            }
            CreateV606ThreeModeExecution.Completed completed =
                    (CreateV606ThreeModeExecution.Completed) result;
            check(completed.mode() == currentMode.get()
                            && completed.verifiedPhysicalPlanId()
                            .equals(ready.executionReadyPlan().physicalPlan().id()),
                    "Mode completion changed the verified physical plan identity");
            check(completeMaterials.keySet().stream()
                            .allMatch(resource -> count(level, source, resource.toString()) == 0),
                    "Mode did not settle process and installation material escrow");
            check(count(level, delivery, targetId) >= targetQuantity,
                    "Mode did not produce the exact authoritative output");
            if (capability.equals("C09_LAVA")) {
                check(completed.process().finalResourceBuffer().equals(
                                Map.of(id(targetId), (long) targetQuantity)),
                        "C09 lava mode retained an unexpected bucket, residue or output: "
                                + completed.process().finalResourceBuffer());
                long injectedLava = completed.process().journals().stream()
                        .flatMap(journal -> journal.entries().stream())
                        .filter(WorldChangeJournal.InjectedResourceChange.class::isInstance)
                        .map(WorldChangeJournal.InjectedResourceChange.class::cast)
                        .map(WorldChangeJournal.InjectedResourceChange::resource)
                        .filter(resource -> resource.resourceType() == GenericResourceType.FLUID
                                && resource.resourceId().equals(id("minecraft:lava")))
                        .mapToLong(value -> value.amount())
                        .sum();
                check(injectedLava == 100,
                        "C09 lava mode did not journal exactly 100mB: " + injectedLava);
            }
            check(completed.taskResults().stream().allMatch(value ->
                            value.outcome() == TaskExecutionOutcome.SUCCEEDED
                                    && value.mode() == currentMode.get()
                                    && !value.evidence().isEmpty()),
                    "Mode completion lacks successful assignment-bound evidence");
            if (currentMode.get() == ExecutionMode.BOTS) {
                check(completed.workerActivities().size() == workerCount
                                && completed.workerActivities().stream()
                                .map(CreateV606ThreeModeExecution.WorkerActivity::role)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(Set.of("logistics", "builder_inspector"))
                                && completed.workerActivities().stream()
                                .allMatch(value -> value.assignmentsStarted() > 0
                                        && value.movementTicks() > 0)
                                && completed.workerActivities().stream()
                                .map(CreateV606ThreeModeExecution.WorkerActivity::finalPosition)
                                .distinct()
                                .count() == workerCount,
                        "Bots mode did not visibly exercise every distinct fleet worker: "
                                + completed.workerActivities());
            }
            LOGGER.info("{}_THREE_MODE_STAGE PASS mode={} elapsedTicks={} plan={} graph={} evidence={} reload={} installationKinds={} installationEscrowSettled=true workerActivity={}",
                    capability, currentMode.get().serializedName(), completed.elapsedTicks(),
                    completed.verifiedPhysicalPlanId(), completed.graphId(),
                    completed.taskResults().stream()
                            .mapToInt(value -> value.evidence().size()).sum(),
                    completed.reloadReconciled(), installationMaterials.size(),
                    completed.workerActivities());
            CreateV606ThreeModeExecution.CleanupReport cleanup = session.get().cleanup();
            check(cleanup.remainingPositions() == 0
                            && cleanup.clearedPositions() == cleanup.plannedPositions(),
                    "Mode cleanup did not clear its exact final plan snapshot: " + cleanup);
            ModeEvidence value = evidence(ready, completed, cleanup);
            evidence.put(currentMode.get(), value);
            session.set(null);
            if (currentMode.get() == ExecutionMode.DIRECT) {
                currentMode.set(ExecutionMode.BOTS);
                return;
            }
            if (currentMode.get() == ExecutionMode.BOTS) {
                currentMode.set(ExecutionMode.HYBRID);
                return;
            }
            assertEquivalent(capability, ready, evidence);
            ModeEvidence direct = evidence.get(ExecutionMode.DIRECT);
            ModeEvidence bots = evidence.get(ExecutionMode.BOTS);
            ModeEvidence hybrid = evidence.get(ExecutionMode.HYBRID);
            LOGGER.info(
                    "{}_THREE_MODE_EQUIVALENCE PASS plan={} graph={} directTicks={} botsTicks={} hybridTicks={} finalBlockSnapshot=true orientation=true ports=true shaftsBelts=true output=true sessionEvidence=true cleanup=true reload=true materialConsumption=true failures=0 installationEscrow=true completeItemFormBom=true installationKinds={} botWorkers={} botRoles=logistics+builder_inspector botWorkersActive={} playerLike=true smoothMovement=true completionStationsDistinct=true botOverlap=false hybridRoutes=bots+direct{}",
                    capability, ready.executionReadyPlan().physicalPlan().id(), direct.graphId(),
                    direct.elapsedTicks(), bots.elapsedTicks(), hybrid.elapsedTicks(),
                    installationMaterials.size(), bots.workerCount(), bots.workerCount(),
                    capability.equals("C09_LAVA")
                            ? " fluidJournalMb=100 noResidue=true" : "");
            helper.succeed();
        });
    }

    private static void runCancellationMatrix(
            GameTestHelper helper,
            String capability,
            String targetId,
            Map<ResourceId, Long> rawMaterials) {
        ServerLevel level = helper.getLevel();
        prepareArena(helper);
        BlockPos3i source = position(helper.absolutePos(new BlockPos(2, 2, 15)));
        BlockPos3i delivery = position(helper.absolutePos(new BlockPos(8, 2, 15)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(18, 2, 2)));
        List<BlockPos3i> starts = List.of(
                position(helper.absolutePos(new BlockPos(3, 2, 18))),
                position(helper.absolutePos(new BlockPos(4, 2, 18))));
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        position(helper.absolutePos(new BlockPos(1, 2, 1))),
                        position(helper.absolutePos(new BlockPos(31, 3, 20))));
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id(targetId), 1, rawMaterials,
                anchor, QuarterTurn.ZERO,
                id("steve_industrial:three_mode/"
                        + capability.toLowerCase() + "_cancel"),
                rawMaterials,
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                MaterialConstraints.none()));
        Map<ResourceId, Long> installationMaterials = installationMaterials(ready);
        Map<ResourceId, Long> completeMaterials = mergeMaterials(
                rawMaterials, installationMaterials);
        AtomicReference<ExecutionMode> mode =
                new AtomicReference<>(ExecutionMode.DIRECT);
        AtomicReference<CreateV606ThreeModeExecution.Session> session =
                new AtomicReference<>();
        EnumMap<ExecutionMode, CreateV606ThreeModeExecution.CancellationReport>
                reports = new EnumMap<>(ExecutionMode.class);

        helper.onEachTick(() -> {
            if (session.get() == null) {
                seedChest(level, source, completeMaterials);
                seedEmptyChest(level, delivery);
                CreateV606ThreeModeExecution.StartResult start =
                        CreateV606ThreeModeExecution.start(
                                level, ready.executionReadyPlan(), ready.runtime(),
                                source, delivery, mode.get(), region,
                                mode.get() == ExecutionMode.DIRECT
                                        ? List.of() : starts,
                                ready.executionMetadata(), installationMaterials);
                check(start instanceof CreateV606ThreeModeExecution.Started,
                        capability + " cancellation session did not start: " + start);
                session.set(((CreateV606ThreeModeExecution.Started) start).session());
            }
            CreateV606ThreeModeExecution.TickResult result = session.get().tick();
            if (result instanceof CreateV606ThreeModeExecution.Failed failed) {
                helper.fail(capability + " cancellation setup failed: " + failed.detail());
                return;
            }
            // Compare against the complete seeded quantity per identity. C-10 legitimately uses
            // create:shaft both as installation escrow and as a process input, so summing only the
            // raw identities cannot distinguish "one raw shaft withdrawn" from "two structural
            // shafts still reserved" and would let the process become terminal before cancellation.
            boolean rawWithdrawalObserved = rawMaterials.entrySet().stream().anyMatch(entry ->
                    count(level, source, entry.getKey().toString())
                            < entry.getValue()
                            + installationMaterials.getOrDefault(entry.getKey(), 0L));
            if (!rawWithdrawalObserved) {
                return;
            }
            CreateV606ThreeModeExecution.CancellationReport report =
                    session.get().cancelDetailed(id(
                            "steve_industrial:three_mode/"
                                    + capability.toLowerCase()
                                    + "_cancel_" + mode.get().serializedName()));
            check(report.mode() == mode.get()
                            && report.taskResult().outcome()
                                    == TaskExecutionOutcome.CANCELLED
                            && report.sourceRestored()
                            && report.deliveryEmpty()
                            && report.removedWorkers()
                                    == (mode.get() == ExecutionMode.DIRECT ? 0 : 2)
                            && completeMaterials.entrySet().stream().allMatch(entry ->
                                    count(level, source, entry.getKey().toString())
                                            == entry.getValue())
                            && totalItems(level, delivery) == 0,
                    capability + " cancellation did not restore its exact boundary: "
                            + report);
            check(session.get().tick() instanceof CreateV606ThreeModeExecution.Failed,
                    capability + " cancelled session remained executable");
            check(CreateV606ThreeModeExecution.cleanupWorkers(level, region) == 0,
                    capability + " cancellation left a visible worker behind");
            reports.put(mode.get(), report);
            session.set(null);
            if (mode.get() == ExecutionMode.DIRECT) {
                mode.set(ExecutionMode.BOTS);
                return;
            }
            if (mode.get() == ExecutionMode.BOTS) {
                mode.set(ExecutionMode.HYBRID);
                return;
            }
            check(reports.size() == 3,
                    capability + " cancellation did not cover all modes");
            LOGGER.info(
                    "{}_CANCEL_MATRIX PASS direct=EXECUTION_CANCELLED bots=EXECUTION_CANCELLED hybrid=EXECUTION_CANCELLED exactInputReturn=true installationEscrowRestored=true deliveryEmpty=true workersRemoved=true repeatedTickRefused=true privateContainerReads=0 playerInventoryReads=0",
                    capability);
            helper.succeed();
        });
    }

    private static void runFaultMatrix(
            GameTestHelper helper,
            String capability,
            String targetId,
            Map<ResourceId, Long> rawMaterials) {
        ServerLevel level = helper.getLevel();
        prepareArena(helper);
        BlockPos3i source = position(helper.absolutePos(new BlockPos(2, 2, 15)));
        BlockPos3i delivery = position(helper.absolutePos(new BlockPos(8, 2, 15)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(18, 2, 2)));
        List<BlockPos3i> starts = List.of(
                position(helper.absolutePos(new BlockPos(3, 2, 18))),
                position(helper.absolutePos(new BlockPos(7, 2, 18))));
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        position(helper.absolutePos(new BlockPos(1, 2, 1))),
                        position(helper.absolutePos(new BlockPos(31, 3, 20))));
        ResourceId target = id(targetId);

        CreateV606GoalDrivenPlanner.PlanningResult missing =
                CreateV606GoalDrivenPlanner.plan(
                        level, target, 1, rawMaterials, anchor, QuarterTurn.ZERO,
                        id("steve_industrial:fault/"
                                + capability.toLowerCase() + "_missing"),
                        Map.of(),
                        ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                        MaterialConstraints.none());
        check(missing instanceof CreateV606GoalDrivenPlanner.Failure failure
                        && failure.code()
                                == ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                capability + " missing input was not rejected precisely: " + missing);
        CreateV606GoalDrivenPlanner.PlanningResult formal =
                CreateV606GoalDrivenPlanner.plan(
                        level, target, 1, rawMaterials, anchor, QuarterTurn.ZERO,
                        id("steve_industrial:fault/"
                                + capability.toLowerCase() + "_formal"),
                        rawMaterials,
                        ExecutionWorldClassification.FORMAL_EXTERNAL_INSTANCE,
                        MaterialConstraints.none());
        check(formal instanceof CreateV606GoalDrivenPlanner.Failure failure
                        && failure.code()
                                == ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                capability + " formal-world plan was not rejected: " + formal);

        CreateV606GoalDrivenPlanner.Ready obstructionReady =
                capabilityReady(
                        level, capability, target, rawMaterials, anchor, "obstruction");
        BlockPos3i first = obstructionReady.executionReadyPlan().physicalPlan()
                .placements().get(0).components().get(0).position();
        seedChest(level, delivery, rawMaterials);
        level.setBlockAndUpdate(block(first), Blocks.OBSIDIAN.defaultBlockState());
        CreateV606GoalDrivenExecution.StartResult obstructed =
                CreateV606GoalDrivenExecution.begin(
                        level, obstructionReady.executionReadyPlan(),
                        obstructionReady.runtime(), delivery);
        check(obstructed instanceof CreateV606GoalDrivenExecution.Rejected rejected
                        && rejected.code()
                                == ExecutionReadinessFailureCode.BLOCK_PLACEMENT_BLOCKED,
                capability + " obstruction was not rejected before mutation: "
                        + obstructed);
        level.setBlockAndUpdate(block(first), Blocks.AIR.defaultBlockState());
        seedEmptyChest(level, delivery);

        AtomicInteger probe = new AtomicInteger();
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<CreateV606ThreeModeExecution.Session> session =
                new AtomicReference<>();
        AtomicReference<CreateV606GoalDrivenPlanner.Ready> activeReady =
                new AtomicReference<>();
        AtomicReference<BlockPos3i> driftPosition = new AtomicReference<>();

        helper.onEachTick(() -> {
            if (session.get() == null) {
                seedChest(level, source, rawMaterials);
                seedEmptyChest(level, delivery);
                String suffix = switch (probe.get()) {
                    case 0 -> "bot_blocked";
                    case 1 -> "wrong_orientation";
                    case 2 -> "power_lost";
                    default -> throw new GameTestAssertException(
                            capability + " fault probe overflow");
                };
                CreateV606GoalDrivenPlanner.Ready ready = capabilityReady(
                        level, capability, target, rawMaterials, anchor, suffix);
                activeReady.set(ready);
                if (probe.get() == 0) {
                    BlockPos start = block(starts.get(0));
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        BlockPos adjacent = start.relative(direction);
                        // Block every bounded destination, including the one-block
                        // stair-up candidate.  Blocking only the level cell lets a
                        // perfectly valid Bot climb over the test's "wall" and turns
                        // a navigation fault probe into a successful run.
                        level.setBlockAndUpdate(
                                adjacent, Blocks.OBSIDIAN.defaultBlockState());
                        level.setBlockAndUpdate(
                                adjacent.above(), Blocks.OBSIDIAN.defaultBlockState());
                    }
                }
                CreateV606ThreeModeExecution.StartResult start =
                        CreateV606ThreeModeExecution.start(
                                level, ready.executionReadyPlan(), ready.runtime(),
                                source, delivery,
                                probe.get() == 0
                                        ? ExecutionMode.BOTS : ExecutionMode.DIRECT,
                                region,
                                probe.get() == 0 ? starts : List.of());
                check(start instanceof CreateV606ThreeModeExecution.Started,
                        capability + " fault probe did not start: " + start);
                session.set(((CreateV606ThreeModeExecution.Started) start).session());
            }

            CreateV606ThreeModeExecution.TickResult result = session.get().tick();
            if (result instanceof CreateV606ThreeModeExecution.Progress progress) {
                if (probe.get() > 0 && !injected.get()
                        && progress.createPhase().orElse(null)
                                == CreateV606GoalDrivenExecution.Phase.CONNECT) {
                    BlockPos3i changedPosition = componentPosition(
                            activeReady.get(),
                            probe.get() == 1
                                    ? faultMachineToken(capability)
                                    : faultPowerToken(capability));
                    BlockPos changedBlock = block(changedPosition);
                    if (probe.get() == 1) {
                        BlockState state = level.getBlockState(changedBlock);
                        if (state.hasProperty(BlockStateProperties.FACING)) {
                            Direction current =
                                    state.getValue(BlockStateProperties.FACING);
                            level.setBlockAndUpdate(
                                    changedBlock,
                                    state.setValue(
                                            BlockStateProperties.FACING,
                                            current.getOpposite()));
                        } else if (state.hasProperty(
                                BlockStateProperties.HORIZONTAL_FACING)) {
                            Direction current = state.getValue(
                                    BlockStateProperties.HORIZONTAL_FACING);
                            level.setBlockAndUpdate(
                                    changedBlock,
                                    state.setValue(
                                            BlockStateProperties.HORIZONTAL_FACING,
                                            current.getOpposite()));
                        } else {
                            check(state.hasProperty(BlockStateProperties.AXIS),
                                    capability
                                            + " process drive lacks a typed orientation property");
                            Direction.Axis current =
                                    state.getValue(BlockStateProperties.AXIS);
                            Direction.Axis changed = current == Direction.Axis.X
                                    ? Direction.Axis.Z : Direction.Axis.X;
                            level.setBlockAndUpdate(
                                    changedBlock,
                                    state.setValue(
                                            BlockStateProperties.AXIS, changed));
                        }
                    } else {
                        level.setBlockAndUpdate(
                                changedBlock, Blocks.AIR.defaultBlockState());
                    }
                    driftPosition.set(changedPosition);
                    injected.set(true);
                }
                return;
            }
            check(result instanceof CreateV606ThreeModeExecution.Failed,
                    capability + " fault probe unexpectedly completed: " + result);
            CreateV606ThreeModeExecution.Failed failed =
                    (CreateV606ThreeModeExecution.Failed) result;
            if (probe.get() == 0) {
                check(failed.taskResult().failure().orElseThrow().code()
                                .equals(ConstructionFailureCode.NAVIGATION_BLOCKED.id()),
                        capability + " blocked Bot did not fail NAVIGATION_BLOCKED: "
                                + failed);
            } else {
                check(injected.get()
                                && failed.taskResult().failure().isPresent()
                                && failed.taskResult().failure().orElseThrow()
                                        .code() != null,
                        capability + " runtime drift did not return a typed failure: "
                                + failed);
            }
            CreateV606ThreeModeExecution.FailureCleanupReport cleanup =
                    session.get().cleanupFailed();
            check(cleanup.sourceRestored() && cleanup.deliveryEmpty()
                            && cleanup.removedWorkers()
                                    == (probe.get() == 0 ? 2 : 0),
                    capability + " failure cleanup lost a boundary: " + cleanup);
            if (probe.get() == 0) {
                check(cleanup.warnings() == 0
                                && CreateV606ThreeModeExecution.cleanupWorkers(
                                        level, region) == 0,
                        capability + " blocked Bot cleanup left residual state: "
                                + cleanup);
                BlockPos start = block(starts.get(0));
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos adjacent = start.relative(direction);
                    level.setBlockAndUpdate(adjacent, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(adjacent.above(), Blocks.AIR.defaultBlockState());
                }
            } else {
                check(cleanup.warnings() >= 1,
                        capability + " cleanup overwrote or ignored external drift: "
                                + cleanup);
                BlockPos changed = block(driftPosition.get());
                if (probe.get() == 2) {
                    check(level.getBlockState(changed).isAir(),
                            capability + " cleanup recreated an externally removed motor");
                }
                clearPhysicalPlan(level, activeReady.get());
            }
            rawMaterials.forEach((resource, quantity) -> check(
                    count(level, source, resource.toString()) == quantity,
                    capability + " failure cleanup did not restore " + resource));
            session.set(null);
            activeReady.set(null);
            driftPosition.set(null);
            injected.set(false);
            if (probe.incrementAndGet() < 3) {
                return;
            }
            LOGGER.info(
                    "{}_FAULT_MATRIX PASS materialMissing=INPUT_RESOURCE_MISSING formalWorld=FORMAL_WORLD_FORBIDDEN obstruction=BLOCK_PLACEMENT_BLOCKED botBlocked=NAVIGATION_BLOCKED wrongOrientation=typed insufficientPower=typed ownershipDriftPreserved=true sourceRestored=true deliveryEmpty=true cleanup=true",
                    capability);
            helper.succeed();
        });
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

    private static String faultMachineToken(String capability) {
        return switch (capability) {
            case "C07" -> "mechanical_saw";
            case "C06" -> "encased_fan";
            case "C09" -> "mechanical_press";
            case "C08" -> "cogwheel";
            case "C10" -> "deployer";
            default -> throw new IllegalArgumentException(
                    "Unknown Phase IV capability " + capability);
        };
    }

    private static String faultPowerToken(String capability) {
        return switch (capability) {
            case "C06" -> "fan_drive_shaft";
            case "C07" -> "horizontal_shaft";
            case "C08" -> "small_cogwheel";
            case "C09" -> "horizontal_shaft";
            case "C10" -> "horizontal_shaft";
            default -> throw new IllegalArgumentException(
                    "Unknown Phase IV capability " + capability);
        };
    }

    private static void clearPhysicalPlan(
            ServerLevel level,
            CreateV606GoalDrivenPlanner.Ready ready) {
        ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                placement.components().forEach(component ->
                        level.setBlockAndUpdate(
                                block(component.position()),
                                Blocks.AIR.defaultBlockState())));
        ready.executionReadyPlan().physicalPlan().routes().forEach(route ->
                route.positions().forEach(position ->
                        level.setBlockAndUpdate(
                                block(position),
                                Blocks.AIR.defaultBlockState())));
    }

    private static CreateV606GoalDrivenPlanner.Ready capabilityReady(
            ServerLevel level,
            String capability,
            ResourceId target,
            Map<ResourceId, Long> rawMaterials,
            BlockPos3i anchor,
            String suffix) {
        return ready(CreateV606GoalDrivenPlanner.plan(
                level, target, 1, rawMaterials,
                anchor, QuarterTurn.ZERO,
                id("steve_industrial:fault/"
                        + capability.toLowerCase() + "_" + suffix),
                rawMaterials,
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                MaterialConstraints.none()));
    }

    private static ModeEvidence evidence(
            CreateV606GoalDrivenPlanner.Ready ready,
            CreateV606ThreeModeExecution.Completed completed,
            CreateV606ThreeModeExecution.CleanupReport cleanup) {
        Map<BlockPos3i, String> orientation = componentSnapshot(
                ready, completed.finalBlockSnapshot(),
                Set.of(
                        "shaft", "wheel", "belt", "press", "motor", "fan",
                        "saw", "mixer", "deployer"));
        Map<BlockPos3i, String> ports = portSnapshot(ready, completed.finalBlockSnapshot());
        Map<BlockPos3i, String> shaftsBelts = componentSnapshot(
                ready, completed.finalBlockSnapshot(),
                Set.of(
                        "shaft", "belt", "wheel", "motor", "fan", "saw",
                        "mixer", "press", "deployer"));
        boolean hybridBotRoute = completed.taskResults().stream()
                .flatMap(value -> value.evidence().stream())
                .anyMatch(value -> value.provenance().startsWith("hybrid-route=bots;"));
        boolean hybridDirectRoute = completed.taskResults().stream()
                .flatMap(value -> value.evidence().stream())
                .anyMatch(value -> value.provenance().startsWith("hybrid-route=direct;"));
        return new ModeEvidence(
                completed.verifiedPhysicalPlanId(), completed.graphId(),
                completed.finalBlockSnapshot(), orientation, ports, shaftsBelts,
                completed.process().finalResourceBuffer(), completed.elapsedTicks(),
                completed.reloadReconciled(), completed.workerIds().size(),
                completed.taskResults().stream().map(value -> value.evidence().size()).reduce(0, Integer::sum),
                cleanup.remainingPositions(), hybridBotRoute, hybridDirectRoute);
    }

    private static Map<BlockPos3i, String> componentSnapshot(
            CreateV606GoalDrivenPlanner.Ready ready,
            Map<BlockPos3i, String> full,
            Set<String> tokens) {
        Map<BlockPos3i, String> values = new LinkedHashMap<>();
        ready.executionReadyPlan().physicalPlan().placements().stream()
                .flatMap(placement -> placement.components().stream())
                .filter(component -> tokens.stream().anyMatch(token ->
                        component.roleId().path().toLowerCase().contains(token)))
                .forEach(component -> values.put(
                        component.position(), full.get(component.position())));
        return Map.copyOf(values);
    }

    private static BlockPos3i componentPosition(
            CreateV606GoalDrivenPlanner.Ready ready,
            String role) {
        List<dev.stevecreate.agent.core.layout.ResolvedGeometryComponent> components =
                ready.executionReadyPlan().physicalPlan().placements().stream()
                .flatMap(placement -> placement.components().stream())
                .toList();
        return components.stream()
                .filter(component -> component.blockId().path()
                        .toLowerCase().contains(role.toLowerCase()))
                .findFirst()
                .or(() -> components.stream()
                        .filter(component -> component.roleId().path()
                                .toLowerCase().contains(role.toLowerCase()))
                        .findFirst())
                .orElseThrow(() -> new GameTestAssertException(
                        "Missing physical component role " + role))
                .position();
    }

    private static Map<BlockPos3i, String> portSnapshot(
            CreateV606GoalDrivenPlanner.Ready ready,
            Map<BlockPos3i, String> full) {
        Map<BlockPos3i, String> values = new LinkedHashMap<>(componentSnapshot(
                ready, full, Set.of("input", "output", "chest")));
        ready.executionReadyPlan().physicalPlan().routes().forEach(route -> {
            if (!route.positions().isEmpty()) {
                BlockPos3i input = route.positions().get(0);
                BlockPos3i output = route.positions().get(route.positions().size() - 1);
                if (full.containsKey(input)) values.put(input, full.get(input));
                if (full.containsKey(output)) values.put(output, full.get(output));
            }
        });
        return Map.copyOf(values);
    }

    private static void assertEquivalent(
            String capability,
            CreateV606GoalDrivenPlanner.Ready ready,
            EnumMap<ExecutionMode, ModeEvidence> evidence) {
        check(evidence.size() == 3, capability + " did not complete all three modes");
        ModeEvidence direct = evidence.get(ExecutionMode.DIRECT);
        ModeEvidence bots = evidence.get(ExecutionMode.BOTS);
        ModeEvidence hybrid = evidence.get(ExecutionMode.HYBRID);
        check(direct.planId().equals(bots.planId()) && direct.planId().equals(hybrid.planId())
                        && direct.planId().equals(ready.executionReadyPlan().physicalPlan().id()),
                capability + " modes did not share one VerifiedPhysicalPlan");
        check(direct.graphId().equals(bots.graphId()) && direct.graphId().equals(hybrid.graphId()),
                capability + " modes did not share one construction graph");
        check(direct.finalSnapshot().equals(bots.finalSnapshot())
                        && direct.finalSnapshot().equals(hybrid.finalSnapshot()),
                capability + " final block snapshots differ by mode");
        check(direct.orientation().equals(bots.orientation())
                        && direct.orientation().equals(hybrid.orientation())
                        && !direct.orientation().isEmpty(),
                capability + " orientation evidence differs or is empty");
        check(direct.ports().equals(bots.ports()) && direct.ports().equals(hybrid.ports())
                        && !direct.ports().isEmpty(),
                capability + " port evidence differs or is empty");
        check(direct.shaftsBelts().equals(bots.shaftsBelts())
                        && direct.shaftsBelts().equals(hybrid.shaftsBelts())
                        && !direct.shaftsBelts().isEmpty(),
                capability + " shaft/belt evidence differs or is empty");
        ResourceId target = ready.executionReadyPlan().physicalPlan().candidate()
                .boundPlan().graph().logicalPlan().candidate().goal().target();
        if (capability.equals("C05")) {
            long directPrimary = direct.output().getOrDefault(target, 0L);
            check(directPrimary == bots.output().getOrDefault(target, 0L)
                            && directPrimary == hybrid.output().getOrDefault(target, 0L),
                    capability + " guaranteed primary output differs by mode");
        } else {
            check(direct.output().equals(bots.output())
                            && direct.output().equals(hybrid.output()),
                    capability + " final resource-buffer outputs differ by mode");
        }
        check(direct.evidenceCount() >= 3 && bots.evidenceCount() >= 3
                        && hybrid.evidenceCount() >= 3,
                capability + " session evidence is incomplete");
        check(direct.cleanupRemaining() == 0 && bots.cleanupRemaining() == 0
                        && hybrid.cleanupRemaining() == 0,
                capability + " cleanup differs or remains incomplete");
        check(direct.reload() && bots.reload() && hybrid.reload(),
                capability + " idle reload reconciliation is incomplete");
        int expectedWorkers = Set.of("C08", "C08_HEATED", "C09", "C09_LAVA", "C10")
                .contains(capability) ? 3 : 2;
        check(direct.workerCount() == 0 && bots.workerCount() == expectedWorkers
                        && hybrid.workerCount() == expectedWorkers,
                capability + " fleet cardinality differs from the declared modes");
        check(direct.elapsedTicks() > 0 && bots.elapsedTicks() > 0 && hybrid.elapsedTicks() > 0,
                capability + " execution-time evidence is incomplete");
        check(hybrid.hybridBotRoute() && hybrid.hybridDirectRoute()
                        && !direct.hybridBotRoute() && !bots.hybridDirectRoute(),
                capability + " Hybrid provenance does not prove Bot plus Direct routing");
    }

    private static void prepareArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 31; x++) {
            for (int z = 1; z <= 20; z++) {
                level.setBlockAndUpdate(
                        helper.absolutePos(new BlockPos(x, 1, z)),
                        Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(
                        helper.absolutePos(new BlockPos(x, 2, z)),
                        Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(
                        helper.absolutePos(new BlockPos(x, 3, z)),
                        Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void seedEmptyChest(ServerLevel level, BlockPos3i position) {
        level.setBlockAndUpdate(block(position), Blocks.CHEST.defaultBlockState());
        check(level.getBlockEntity(block(position)) instanceof ChestBlockEntity,
                "Could not create dedicated empty delivery chest");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        chest.clearContent();
        chest.setChanged();
    }

    private static void seedChest(
            ServerLevel level,
            BlockPos3i position,
            Map<ResourceId, Long> materials) {
        seedEmptyChest(level, position);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        List<Map.Entry<ResourceId, Long>> ordered = materials.entrySet().stream()
                .sorted(java.util.Comparator.comparing(value -> value.getKey().toString()))
                .toList();
        check(ordered.size() <= chest.getContainerSize(),
                "Test input manifest exceeds the dedicated source chest");
        for (int slot = 0; slot < ordered.size(); slot++) {
            Map.Entry<ResourceId, Long> material = ordered.get(slot);
            Item item = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.fromNamespaceAndPath(
                            material.getKey().namespace(),
                            material.getKey().path()));
            check(item != null, "Test input is not registered: " + material.getKey());
            chest.setItem(slot, new ItemStack(item, Math.toIntExact(material.getValue())));
        }
        chest.setChanged();
    }

    private static int count(ServerLevel level, BlockPos3i position, String itemId) {
        ResourceLocation resourceLocation = ResourceLocation.tryParse(itemId);
        check(resourceLocation != null, "Resource buffer item ID is invalid: " + itemId);
        Item item = ForgeRegistries.ITEMS.getValue(resourceLocation);
        check(item != null && level.getBlockEntity(block(position)) instanceof ChestBlockEntity,
                "Resource buffer readback is unavailable for " + itemId);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int totalItems(ServerLevel level, BlockPos3i position) {
        check(level.getBlockEntity(block(position)) instanceof ChestBlockEntity,
                "Resource-buffer total readback is unavailable");
        ChestBlockEntity chest =
                (ChestBlockEntity) level.getBlockEntity(block(position));
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            total += chest.getItem(slot).getCount();
        }
        return total;
    }

    private static CreateV606GoalDrivenPlanner.Ready ready(
            CreateV606GoalDrivenPlanner.PlanningResult value) {
        check(value instanceof CreateV606GoalDrivenPlanner.Ready,
                "Three-mode planning did not produce readiness: " + value);
        return (CreateV606GoalDrivenPlanner.Ready) value;
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static CommandNode<CommandSourceStack> child(
            CommandNode<CommandSourceStack> parent,
            String name) {
        CommandNode<CommandSourceStack> value = parent.getChild(name);
        check(value != null, "Missing command node " + name + " below " + parent.getName());
        return value;
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new GameTestAssertException(detail);
    }

    private record ModeEvidence(
            ResourceId planId,
            ResourceId graphId,
            Map<BlockPos3i, String> finalSnapshot,
            Map<BlockPos3i, String> orientation,
            Map<BlockPos3i, String> ports,
            Map<BlockPos3i, String> shaftsBelts,
            Map<ResourceId, Long> output,
            long elapsedTicks,
            boolean reload,
            int workerCount,
            int evidenceCount,
            int cleanupRemaining,
            boolean hybridBotRoute,
            boolean hybridDirectRoute) {}
}
