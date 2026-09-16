package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.construction.AssignmentPolicy;
import dev.stevecreate.agent.core.execution.construction.BotFleetCoordinator;
import dev.stevecreate.agent.core.execution.construction.BotFleetExecutor;
import dev.stevecreate.agent.core.execution.construction.BotFleetTickResult;
import dev.stevecreate.agent.core.execution.construction.ChunkLoadPolicy;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionCommand;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionContext;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskGraph;
import dev.stevecreate.agent.core.execution.construction.DirectWorldExecutor;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.HybridExecutor;
import dev.stevecreate.agent.core.execution.construction.HybridRoutingPolicy;
import dev.stevecreate.agent.core.execution.construction.OwnedWorkpieceApplicationTaskGraphFactory;
import dev.stevecreate.agent.core.execution.construction.PriorityPolicy;
import dev.stevecreate.agent.core.execution.construction.RetryBudget;
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionOutcome;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskOwnership;
import dev.stevecreate.agent.core.execution.construction.WorkerHealthPolicy;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPlan;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateKineticAdapter;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Immediate safety gate for C-10's bounded owned-workpiece authority. */
@PrefixGameTestTemplate(false)
public final class CreateOwnedWorkpieceApplicationGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateOwnedWorkpieceApplicationGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_direct_graph", timeoutTicks = 2_000)
    public static void directExecutorRunsExactThreeTaskGraph(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "direct_graph");
        RuntimeFingerprint runtime = new ForgeCreateKineticAdapter(level).runtime();
        ConstructionTaskGraph graph = new OwnedWorkpieceApplicationTaskGraphFactory().create(
                plan, runtime.canonicalIdentity(), "c10-direct-gametest-snapshot-v1");
        CreateV606OwnedWorkpieceDirectBackend backend =
                new CreateV606OwnedWorkpieceDirectBackend(level, plan, runtime, graph);
        DirectWorldExecutor executor = new DirectWorldExecutor(
                CreateV606OwnedWorkpieceDirectBackend.EXECUTOR_ID,
                Set.of(OwnedWorkpieceApplicationTaskGraphFactory.DIRECT_EXECUTOR_CAPABILITY),
                backend);
        List<ConstructionTask> tasks = graph.topologicalOrder();
        check(tasks.size() == 3, "C-10 Direct graph did not contain exactly three tasks");
        long assignedTick = level.getGameTime();
        Map<ResourceId, TaskAssignment> assignments = tasks.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        ConstructionTask::taskId,
                        task -> assignment(
                                graph, task, plan.policy().sessionId(), assignedTick)));

        ConstructionTask deliver = tasks.get(0);
        TaskExecutionResult delivered = executor.execute(
                graph, deliver, assignments.get(deliver.taskId()),
                context(ConstructionExecutionCommand.START, level.getGameTime(), null));
        check(delivered.outcome() == TaskExecutionOutcome.SUCCEEDED
                        && delivered.worldMutationCount() == 0
                        && delivered.materialMutationCount() == 0
                        && delivered.detail().contains("no transfer was performed"),
                "Direct delivery task fabricated movement or failed: " + delivered.detail());

        ConstructionTask apply = tasks.get(1);
        ConstructionTask verify = tasks.get(2);
        AtomicReference<TaskExecutionResult> prior = new AtomicReference<>();
        AtomicInteger worldMutations = new AtomicInteger();
        AtomicInteger materialMutations = new AtomicInteger();
        helper.succeedWhen(() -> {
            TaskExecutionResult previous = prior.get();
            TaskExecutionResult update = previous;
            if (previous == null
                    || previous.outcome() == TaskExecutionOutcome.PENDING) {
                ConstructionExecutionCommand command = previous == null
                        ? ConstructionExecutionCommand.START
                        : ConstructionExecutionCommand.CONTINUE;
                update = executor.execute(
                        graph, apply, assignments.get(apply.taskId()),
                        new ConstructionExecutionContext(
                                command,
                                level.getGameTime(),
                                previous == null ? List.of() : previous.evidence(),
                                previous == null ? Optional.empty() : previous.failure(),
                                Optional.empty()));
                prior.set(update);
                worldMutations.addAndGet(update.worldMutationCount());
                materialMutations.addAndGet(update.materialMutationCount());
            }
            if (update.outcome() == TaskExecutionOutcome.PENDING) {
                throw new GameTestAssertException(
                        "waiting for Direct graph real Deployer cycle: " + update.detail());
            }
            check(update.outcome() == TaskExecutionOutcome.SUCCEEDED
                            && update.evidence().size() == apply.postconditions().size(),
                    "Direct application task did not produce complete evidence: "
                            + update.detail());
            TaskExecutionResult verified = executor.execute(
                    graph, verify, assignments.get(verify.taskId()),
                    context(ConstructionExecutionCommand.START, level.getGameTime(), null));
            check(verified.outcome() == TaskExecutionOutcome.SUCCEEDED
                            && verified.evidence().size() == verify.postconditions().size()
                            && worldMutations.get() == 3
                            && materialMutations.get() == 1
                            && id(level.getBlockState(pos(workpiece)).getBlock())
                            .equals(id("create:andesite_casing"))
                            && chest(level, buffer).isEmpty()
                            && machineCellsAreAir(level, plan),
                    "Direct terminal task did not prove exact output/buffer/cleanup: "
                            + verified.detail()
                            + " worldMutations=" + worldMutations
                            + " materialMutations=" + materialMutations);
            level.removeBlock(pos(workpiece), false);
            level.removeBlock(pos(buffer), false);
            LOGGER.info(
                    "C10_OWNED_WORKPIECE_DIRECT_GRAPH PASS tasks=3 deliveryTransfer=false realDeployerCycle=true worldMutationActions={} materialMutationActions={} evidence={}/{} output=true bufferEmpty=true cleanup=true",
                    worldMutations.get(), materialMutations.get(),
                    update.evidence().size(), verified.evidence().size());
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_direct_cancel", timeoutTicks = 300)
    public static void directExecutorCancellationUsesExactHandlerRollback(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "direct_cancel");
        RuntimeFingerprint runtime = new ForgeCreateKineticAdapter(level).runtime();
        ConstructionTaskGraph graph = new OwnedWorkpieceApplicationTaskGraphFactory().create(
                plan, runtime.canonicalIdentity(), "c10-direct-cancel-snapshot-v1");
        CreateV606OwnedWorkpieceDirectBackend backend =
                new CreateV606OwnedWorkpieceDirectBackend(level, plan, runtime, graph);
        DirectWorldExecutor executor = new DirectWorldExecutor(
                CreateV606OwnedWorkpieceDirectBackend.EXECUTOR_ID,
                Set.of(OwnedWorkpieceApplicationTaskGraphFactory.DIRECT_EXECUTOR_CAPABILITY),
                backend);
        long assignedTick = level.getGameTime();
        ConstructionTask deliver = graph.topologicalOrder().get(0);
        ConstructionTask apply = graph.topologicalOrder().get(1);
        TaskAssignment deliverAssignment = assignment(
                graph, deliver, plan.policy().sessionId(), assignedTick);
        TaskAssignment applyAssignment = assignment(
                graph, apply, plan.policy().sessionId(), assignedTick);
        check(executor.execute(
                        graph, deliver, deliverAssignment,
                        context(ConstructionExecutionCommand.START, level.getGameTime(), null))
                        .outcome() == TaskExecutionOutcome.SUCCEEDED,
                "Direct cancellation fixture could not establish delivery readiness");
        TaskExecutionResult started = executor.execute(
                graph, apply, applyAssignment,
                context(ConstructionExecutionCommand.START, level.getGameTime(), null));
        check(started.outcome() == TaskExecutionOutcome.PENDING
                        && started.worldMutationCount() == 1,
                "Direct cancellation fixture did not begin with one bounded placement");
        TaskExecutionResult cancelled = executor.execute(
                graph, apply, applyAssignment,
                context(
                        ConstructionExecutionCommand.CANCEL,
                        level.getGameTime(),
                        id("construction:test_requested_cancel")));
        check(cancelled.outcome() == TaskExecutionOutcome.CANCELLED
                        && cancelled.evidence().size() == 1
                        && machineCellsAreAir(level, plan)
                        && id(level.getBlockState(pos(workpiece)).getBlock())
                        .equals(id("minecraft:stripped_oak_log"))
                        && chest(level, buffer).getItem(0).getCount() == 1,
                "Direct cancellation did not prove exact handler rollback: "
                        + cancelled.detail());
        level.removeBlock(pos(workpiece), false);
        level.removeBlock(pos(buffer), false);
        LOGGER.info(
                "C10_OWNED_WORKPIECE_DIRECT_CANCEL PASS outcome=CANCELLED evidence=1 machineRestored=true inputUnchanged=true workpieceUnchanged=true");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_bot_graph", timeoutTicks = 2_000)
    public static void existingSteveAlexFleetRunsExactThreeTaskGraph(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        prepareBotFloor(level, workpiece);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "bot_graph");
        RuntimeFingerprint runtime = new ForgeCreateKineticAdapter(level).runtime();
        ConstructionTaskGraph graph = new OwnedWorkpieceApplicationTaskGraphFactory().create(
                plan, runtime.canonicalIdentity(), "c10-bot-gametest-snapshot-v1");
        CreateV606OwnedWorkpieceDirectBackend backend =
                new CreateV606OwnedWorkpieceDirectBackend(level, plan, runtime, graph);
        ResourceId botExecutorId = id("construction:c10_bot_fleet_v606");
        CreateV606OwnedWorkpieceBotWorker steve =
                CreateV606OwnedWorkpieceBotWorker.spawn(
                        level, graph, plan, backend, botExecutorId,
                        id("bot:steve_logistics"),
                        workpiece.translate(-4, -1, 2),
                        ConstructionBotEntity.Role.LOGISTICS);
        CreateV606OwnedWorkpieceBotWorker alex =
                CreateV606OwnedWorkpieceBotWorker.spawn(
                        level, graph, plan, backend, botExecutorId,
                        id("bot:alex_builder_inspector"),
                        workpiece.translate(-4, -1, -2),
                        ConstructionBotEntity.Role.BUILDER_INSPECTOR);
        BotFleetExecutor executor = new BotFleetExecutor(
                botExecutorId,
                Set.of(OwnedWorkpieceApplicationTaskGraphFactory.BOT_EXECUTOR_CAPABILITY),
                List.of(steve, alex));
        ConstructionTask apply = graph.topologicalOrder().stream()
                .filter(task -> task.kind()
                        == dev.stevecreate.agent.core.execution.construction.TaskKind
                                .SAFE_MACHINE_INTERACTION)
                .findFirst().orElseThrow();
        ResourceId placementReservation = apply.requiredPlacementReservationIds().stream()
                .findFirst().orElseThrow();
        BotFleetCoordinator coordinator = new BotFleetCoordinator(
                plan.policy().sessionId(),
                graph,
                executor,
                Map.of(placementReservation, plan.policy().workpiecePosition()),
                AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                PriorityPolicy.TOPOLOGICAL_THEN_ID,
                new RetryBudget(0, 0),
                new WorkerHealthPolicy(1, true),
                ChunkLoadPolicy.REQUIRE_ALREADY_LOADED,
                10_000);

        helper.succeedWhen(() -> {
            BotFleetTickResult result = coordinator.tick(level.getGameTime());
            check(result.terminalFailures().isEmpty() && result.deadlock().isEmpty(),
                    "Existing Steve/Alex fleet failed C-10: " + result.terminalFailures());
            if (result.completedResults().size() != graph.tasks().size()) {
                throw new GameTestAssertException(
                        "waiting for existing Steve/Alex fleet: completed="
                                + result.completedResults().size()
                                + " active=" + result.activeAssignments().size());
            }
            check(steve.assignmentsStarted() == 1
                            && alex.assignmentsStarted() == 2
                            && steve.movementTicks() > 0
                            && alex.movementTicks() > 0
                            && id(level.getBlockState(pos(workpiece)).getBlock())
                            .equals(id("create:andesite_casing"))
                            && chest(level, buffer).isEmpty()
                            && machineCellsAreAir(level, plan),
                    "Existing Steve/Alex fleet did not prove role assignment, movement or output");
            int steveMovement = steve.movementTicks();
            int alexMovement = alex.movementTicks();
            steve.discard();
            alex.discard();
            level.removeBlock(pos(workpiece), false);
            level.removeBlock(pos(buffer), false);
            LOGGER.info(
                    "C10_OWNED_WORKPIECE_BOT_GRAPH PASS scheduler=BotFleetCoordinator executor=BotFleetExecutor entities=existing_construction_bot steveAssignments=1 alexAssignments=2 steveMovementTicks={} alexMovementTicks={} teleport=false playerInventory=false output=true bufferEmpty=true cleanup=true",
                    steveMovement, alexMovement);
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_hybrid_graph", timeoutTicks = 2_000)
    public static void hybridRoutesLogisticsToSteveAndSensitiveWorkToDirect(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        prepareBotFloor(level, workpiece);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "hybrid_graph");
        RuntimeFingerprint runtime = new ForgeCreateKineticAdapter(level).runtime();
        ConstructionTaskGraph graph = new OwnedWorkpieceApplicationTaskGraphFactory().create(
                plan, runtime.canonicalIdentity(), "c10-hybrid-gametest-snapshot-v1");
        CreateV606OwnedWorkpieceDirectBackend backend =
                new CreateV606OwnedWorkpieceDirectBackend(level, plan, runtime, graph);
        DirectWorldExecutor direct = new DirectWorldExecutor(
                CreateV606OwnedWorkpieceDirectBackend.EXECUTOR_ID,
                Set.of(OwnedWorkpieceApplicationTaskGraphFactory.DIRECT_EXECUTOR_CAPABILITY),
                backend);
        ResourceId botExecutorId = id("construction:c10_hybrid_bot_fleet_v606");
        CreateV606OwnedWorkpieceBotWorker steve =
                CreateV606OwnedWorkpieceBotWorker.spawn(
                        level, graph, plan, backend, botExecutorId,
                        id("bot:steve_logistics"),
                        workpiece.translate(-4, -1, 2),
                        ConstructionBotEntity.Role.LOGISTICS);
        CreateV606OwnedWorkpieceBotWorker alex =
                CreateV606OwnedWorkpieceBotWorker.spawn(
                        level, graph, plan, backend, botExecutorId,
                        id("bot:alex_builder_inspector"),
                        workpiece.translate(-4, -1, -2),
                        ConstructionBotEntity.Role.BUILDER_INSPECTOR);
        BotFleetExecutor bots = new BotFleetExecutor(
                botExecutorId,
                Set.of(OwnedWorkpieceApplicationTaskGraphFactory.BOT_EXECUTOR_CAPABILITY),
                List.of(steve, alex));
        ResourceId hybridExecutorId = id("construction:c10_hybrid_router_v1");
        HybridExecutor hybrid = new HybridExecutor(
                hybridExecutorId,
                Set.of(OwnedWorkpieceApplicationTaskGraphFactory.HYBRID_EXECUTOR_CAPABILITY),
                direct, bots, HybridRoutingPolicy.safeDefaults());
        List<ConstructionTask> tasks = graph.topologicalOrder();
        long assignedTick = level.getGameTime();
        List<TaskAssignment> assignments = List.of(
                hybridAssignment(
                        graph, tasks.get(0), plan.policy().sessionId(), hybridExecutorId,
                        Optional.of(steve.workerId()), assignedTick),
                hybridAssignment(
                        graph, tasks.get(1), plan.policy().sessionId(), hybridExecutorId,
                        Optional.empty(), assignedTick),
                hybridAssignment(
                        graph, tasks.get(2), plan.policy().sessionId(), hybridExecutorId,
                        Optional.empty(), assignedTick));
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<TaskExecutionResult> prior = new AtomicReference<>();

        helper.succeedWhen(() -> {
            int index = stage.get();
            if (index >= tasks.size()) return;
            ConstructionTask task = tasks.get(index);
            TaskAssignment assignment = assignments.get(index);
            TaskExecutionResult previous = prior.get();
            ConstructionExecutionCommand command = previous == null
                    ? ConstructionExecutionCommand.START
                    : ConstructionExecutionCommand.CONTINUE;
            TaskExecutionResult update = hybrid.execute(
                    graph, task, assignment,
                    new ConstructionExecutionContext(
                            command,
                            level.getGameTime(),
                            previous == null ? List.of() : previous.evidence(),
                            Optional.empty(),
                            Optional.empty()));
            check(update.outcome() == TaskExecutionOutcome.PENDING
                            || update.outcome() == TaskExecutionOutcome.SUCCEEDED,
                    "C-10 Hybrid task failed: " + update.detail());
            if (update.outcome() == TaskExecutionOutcome.PENDING) {
                prior.set(update);
                throw new GameTestAssertException(
                        "waiting for C-10 Hybrid task " + task.taskId());
            }
            String expectedRoute = index == 0
                    ? "hybrid-route=bots;"
                    : "hybrid-route=direct;";
            check(!update.evidence().isEmpty()
                            && update.evidence().stream().allMatch(value ->
                            value.provenance().startsWith(expectedRoute)),
                    "C-10 Hybrid evidence lost its physical route: " + update.evidence());
            prior.set(null);
            if (stage.incrementAndGet() < tasks.size()) {
                throw new GameTestAssertException("advancing C-10 Hybrid task graph");
            }
            check(steve.assignmentsStarted() == 1
                            && steve.movementTicks() > 0
                            && alex.assignmentsStarted() == 0
                            && id(level.getBlockState(pos(workpiece)).getBlock())
                            .equals(id("create:andesite_casing"))
                            && chest(level, buffer).isEmpty()
                            && machineCellsAreAir(level, plan),
                    "C-10 Hybrid did not preserve route ownership, movement or exact output");
            int steveMovement = steve.movementTicks();
            steve.discard();
            alex.discard();
            level.removeBlock(pos(workpiece), false);
            level.removeBlock(pos(buffer), false);
            LOGGER.info(
                    "C10_OWNED_WORKPIECE_HYBRID_GRAPH PASS policy=safeDefaults logisticsRoute=bots sensitiveRoutes=direct existingSteveAlex=true steveAssignments=1 alexAssignments=0 steveMovementTicks={} teleport=false playerInventory=false output=true bufferEmpty=true cleanup=true provenance=true",
                    steveMovement);
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_real", timeoutTicks = 2_000)
    public static void reviewedAndesiteCasingUsesRealDeployerCycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "real");
        Create606OwnedWorkpieceApplicationHandler handler = started(
                Create606OwnedWorkpieceApplicationHandler.begin(
                        level,
                        plan,
                        new ForgeCreateKineticAdapter(level).runtime()));
        AtomicReference<Create606OwnedWorkpieceApplicationHandler.Update> last =
                new AtomicReference<>();

        helper.succeedWhen(() -> {
            AdapterResult<Create606OwnedWorkpieceApplicationHandler.Update> result =
                    handler.tick();
            if (result instanceof AdapterResult.Failure<
                    Create606OwnedWorkpieceApplicationHandler.Update> failure) {
                helper.fail("Owned-workpiece execution failed: " + failure);
                return;
            }
            Create606OwnedWorkpieceApplicationHandler.Update update =
                    ((AdapterResult.Success<
                            Create606OwnedWorkpieceApplicationHandler.Update>) result).value();
            last.set(update);
            if (update.phase()
                    != Create606OwnedWorkpieceApplicationHandler.Phase.COMPLETED) {
                throw new GameTestAssertException(
                        "waiting for the real allowlisted Deployer cycle: " + update.phase());
            }
            check(update.realDeployerCycleObserved()
                            && update.exactWorkpieceTransitionObserved()
                            && update.unrelatedWorldStateUnchanged()
                            && update.heldBefore() == 1
                            && update.heldAfter() == 0,
                    "completed evidence did not prove the exact physical transition: " + update);
            check(id(level.getBlockState(pos(workpiece)).getBlock())
                            .equals(id("create:andesite_casing")),
                    "real Deployer did not leave the expected casing block");
            AdapterResult<Boolean> cleanup = handler.cleanupMachine();
            check(cleanup instanceof AdapterResult.Success<Boolean>
                            && ((AdapterResult.Success<Boolean>) cleanup).value()
                            && level.getBlockState(pos(plan.drivePosition())).isAir()
                            && level.getBlockState(pos(plan.deployerPosition())).isAir(),
                    "owned-workpiece machine cleanup was not exact: " + cleanup);
            level.removeBlock(pos(workpiece), false);
            level.removeBlock(pos(buffer), false);
            LOGGER.info(
                    "C10_OWNED_WORKPIECE_REAL PASS recipe={} initial={} result={} held=1->0 realDeployerCycle=true arbitraryPosition=false container=false entity=false playerInventory=false unknownNbt=false unrelatedWorldState=false cleanup=true",
                    update.recipeId(), update.initialBlock(), update.resultBlock());
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_reload", timeoutTicks = 2_000)
    public static void exactPreResourceCheckpointRecoversWithoutDuplication(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "reload");
        Create606OwnedWorkpieceApplicationHandler original = started(
                Create606OwnedWorkpieceApplicationHandler.begin(
                        level,
                        plan,
                        new ForgeCreateKineticAdapter(level).runtime()));
        check(original.tick() instanceof AdapterResult.Success<?>,
                "reload probe could not place drive");
        check(original.tick() instanceof AdapterResult.Success<?>,
                "reload probe could not place Deployer");
        AdapterResult<Create606OwnedWorkpieceApplicationHandler.RecoveryCheckpoint>
                checkpointResult = original.recoveryCheckpoint();
        check(checkpointResult instanceof AdapterResult.Success<?>,
                "exact pre-resource checkpoint was rejected: " + checkpointResult);
        Create606OwnedWorkpieceApplicationHandler.RecoveryCheckpoint checkpoint =
                ((AdapterResult.Success<
                        Create606OwnedWorkpieceApplicationHandler.RecoveryCheckpoint>)
                        checkpointResult).value();
        check(checkpoint.journal().entries().size() == 2
                        && chest(level, buffer).getItem(0).getCount() == 1,
                "checkpoint did not preserve the exact unconsumed resource boundary");
        Create606OwnedWorkpieceApplicationHandler recovered = started(
                Create606OwnedWorkpieceApplicationHandler.recover(
                        level,
                        plan,
                        new ForgeCreateKineticAdapter(level).runtime(),
                        checkpoint));

        helper.succeedWhen(() -> {
            AdapterResult<Create606OwnedWorkpieceApplicationHandler.Update> result =
                    recovered.tick();
            if (result instanceof AdapterResult.Failure<
                    Create606OwnedWorkpieceApplicationHandler.Update> failure) {
                helper.fail("Recovered owned-workpiece execution failed: " + failure);
                return;
            }
            Create606OwnedWorkpieceApplicationHandler.Update update =
                    ((AdapterResult.Success<
                            Create606OwnedWorkpieceApplicationHandler.Update>) result).value();
            if (update.phase()
                    != Create606OwnedWorkpieceApplicationHandler.Phase.COMPLETED) {
                throw new GameTestAssertException(
                        "waiting for recovered real Deployer cycle: " + update.phase());
            }
            check(update.realDeployerCycleObserved()
                            && id(level.getBlockState(pos(workpiece)).getBlock())
                            .equals(id("create:andesite_casing"))
                            && chest(level, buffer).getItem(0).isEmpty(),
                    "recovered cycle did not conserve one exact input and one result: "
                            + update);
            AdapterResult<Boolean> cleanup = recovered.cleanupMachine();
            check(cleanup instanceof AdapterResult.Success<Boolean>
                            && ((AdapterResult.Success<Boolean>) cleanup).value()
                            && machineCellsAreAir(level, plan),
                    "recovered machine cleanup was not exact: " + cleanup);
            level.removeBlock(pos(workpiece), false);
            level.removeBlock(pos(buffer), false);
            LOGGER.info(
                    "C10_OWNED_WORKPIECE_RELOAD PASS prefixEntries=2 input=1->0 output=1 duplicatePlacement=false duplicateInput=false cleanup=true");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_container", timeoutTicks = 200)
    public static void adjacentPrivateContainerIsRejectedBeforeMutation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        level.setBlockAndUpdate(pos(workpiece.translate(1, 0, 0)),
                Blocks.CHEST.defaultBlockState());
        seed(level, buffer, false);
        AdapterResult<Create606OwnedWorkpieceApplicationHandler> result =
                Create606OwnedWorkpieceApplicationHandler.begin(
                        level,
                        plan(workpiece, "container"),
                        new ForgeCreateKineticAdapter(level).runtime());
        check(result instanceof AdapterResult.Failure<
                        Create606OwnedWorkpieceApplicationHandler>
                        && ((AdapterResult.Failure<
                                Create606OwnedWorkpieceApplicationHandler>) result).code()
                        == AdapterFailureCode.PLAN_REJECTED
                        && ((AdapterResult.Failure<
                                Create606OwnedWorkpieceApplicationHandler>) result).detail()
                        .contains("block entities"),
                "private container was not rejected precisely: " + result);
        check(machineCellsAreAir(level, plan(workpiece, "container")),
                "container refusal mutated machine cells");
        LOGGER.info("C10_OWNED_WORKPIECE_CONTAINER_REFUSAL PASS mutation=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_entity", timeoutTicks = 200)
    public static void entityInActivationVolumeIsRejectedBeforeMutation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        check(stand != null, "could not create entity safety probe");
        stand.setPos(workpiece.x() + 0.5, workpiece.y() + 1.0, workpiece.z() + 0.5);
        check(level.addFreshEntity(stand), "could not add entity safety probe");
        AdapterResult<Create606OwnedWorkpieceApplicationHandler> result =
                Create606OwnedWorkpieceApplicationHandler.begin(
                        level,
                        plan(workpiece, "entity"),
                        new ForgeCreateKineticAdapter(level).runtime());
        check(result instanceof AdapterResult.Failure<
                        Create606OwnedWorkpieceApplicationHandler>
                        && ((AdapterResult.Failure<
                                Create606OwnedWorkpieceApplicationHandler>) result).code()
                        == AdapterFailureCode.PLAN_REJECTED
                        && ((AdapterResult.Failure<
                                Create606OwnedWorkpieceApplicationHandler>) result).detail()
                        .contains("every entity"),
                "entity interaction surface was not rejected precisely: " + result);
        stand.discard();
        check(machineCellsAreAir(level, plan(workpiece, "entity")),
                "entity refusal mutated machine cells");
        LOGGER.info("C10_OWNED_WORKPIECE_ENTITY_REFUSAL PASS mutation=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_nbt", timeoutTicks = 200)
    public static void unknownHeldItemNbtIsRejectedWithoutNormalization(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, true);
        ItemStack original = chest(level, buffer).getItem(0).copy();
        AdapterResult<Create606OwnedWorkpieceApplicationHandler> result =
                Create606OwnedWorkpieceApplicationHandler.begin(
                        level,
                        plan(workpiece, "nbt"),
                        new ForgeCreateKineticAdapter(level).runtime());
        check(result instanceof AdapterResult.Failure<
                        Create606OwnedWorkpieceApplicationHandler>
                        && ((AdapterResult.Failure<
                                Create606OwnedWorkpieceApplicationHandler>) result).code()
                        == AdapterFailureCode.PLAN_REJECTED
                        && ((AdapterResult.Failure<
                                Create606OwnedWorkpieceApplicationHandler>) result).detail()
                        .contains("must contain only the one exact allowlisted held item"),
                "unknown held-item NBT was not rejected: " + result);
        check(ItemStack.isSameItemSameTags(original, chest(level, buffer).getItem(0))
                        && machineCellsAreAir(level, plan(workpiece, "nbt")),
                "NBT refusal normalized or mutated the source stack");
        LOGGER.info("C10_OWNED_WORKPIECE_NBT_REFUSAL PASS sourceUnchanged=true mutation=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "c10_owned_workpiece_cancel", timeoutTicks = 300)
    public static void cancellationRestoresMachineBeforeResourceConsumption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos3i workpiece = absolute(helper, 8, 3, 8);
        BlockPos3i buffer = absolute(helper, 3, 3, 3);
        level.setBlockAndUpdate(pos(workpiece), block("minecraft:stripped_oak_log")
                .defaultBlockState());
        seed(level, buffer, false);
        OwnedWorkpieceApplicationPlan plan = plan(workpiece, "cancel");
        Create606OwnedWorkpieceApplicationHandler handler = started(
                Create606OwnedWorkpieceApplicationHandler.begin(
                        level,
                        plan,
                        new ForgeCreateKineticAdapter(level).runtime()));
        check(handler.tick() instanceof AdapterResult.Success<?>,
                "cancel probe could not place drive");
        check(handler.tick() instanceof AdapterResult.Success<?>,
                "cancel probe could not place Deployer");
        AdapterResult<dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport>
                cancelled = handler.cancel();
        check(cancelled instanceof AdapterResult.Success<?>
                        && machineCellsAreAir(level, plan)
                        && id(level.getBlockState(pos(workpiece)).getBlock())
                        .equals(id("minecraft:stripped_oak_log"))
                        && chest(level, buffer).getItem(0).getCount() == 1,
                "cancellation did not restore exact machine/input/workpiece state: " + cancelled);
        LOGGER.info(
                "C10_OWNED_WORKPIECE_CANCEL PASS machineRestored=true inputRestored=true workpieceUnchanged=true warnings={}",
                ((AdapterResult.Success<
                        dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport>)
                        cancelled).value().warnings().size());
        helper.succeed();
    }

    private static OwnedWorkpieceApplicationPlan plan(
            BlockPos3i workpiece, String suffix) {
        return OwnedWorkpieceApplicationPlan.andesiteCasing(
                id("steve_industrial:c10_owned_workpiece/plan/" + suffix),
                id("steve_industrial:c10_owned_workpiece/session/" + suffix),
                workpiece,
                workpiece.translate(-5, 0, -5),
                workpiece.translate(-6, -2, -6),
                workpiece.translate(3, 4, 3));
    }

    private static TaskAssignment assignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            ResourceId sessionId,
            long assignedTick) {
        ResourceId assignmentId = new ResourceId(
                task.taskId().namespace(), task.taskId().path() + "/direct_assignment");
        TaskOwnership ownership = new TaskOwnership(
                sessionId,
                graph.graphId(), task.taskId(),
                CreateV606OwnedWorkpieceDirectBackend.EXECUTOR_ID,
                ExecutionMode.DIRECT, Optional.empty(), 0, assignedTick,
                assignedTick + 10_000,
                sha256(graph.graphId() + "|" + task.taskId() + "|direct"));
        return TaskAssignment.assign(
                graph, task.taskId(), assignmentId, ownership, 1, assignedTick);
    }

    private static TaskAssignment hybridAssignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            ResourceId sessionId,
            ResourceId hybridExecutorId,
            Optional<ResourceId> workerId,
            long assignedTick) {
        ResourceId assignmentId = new ResourceId(
                task.taskId().namespace(), task.taskId().path() + "/hybrid_assignment");
        TaskOwnership ownership = new TaskOwnership(
                sessionId,
                graph.graphId(), task.taskId(), hybridExecutorId,
                ExecutionMode.HYBRID, workerId, 0, assignedTick,
                assignedTick + 10_000,
                sha256(graph.graphId() + "|" + task.taskId() + "|hybrid"));
        return TaskAssignment.assign(
                graph, task.taskId(), assignmentId, ownership, 1, assignedTick);
    }

    private static ConstructionExecutionContext context(
            ConstructionExecutionCommand command,
            long tick,
            ResourceId reason) {
        return new ConstructionExecutionContext(
                command, tick, List.of(), Optional.empty(), Optional.ofNullable(reason));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime lacks SHA-256", exception);
        }
    }

    private static Create606OwnedWorkpieceApplicationHandler started(
            AdapterResult<Create606OwnedWorkpieceApplicationHandler> result) {
        check(result instanceof AdapterResult.Success<
                        Create606OwnedWorkpieceApplicationHandler>,
                "owned-workpiece begin failed: " + result);
        return ((AdapterResult.Success<Create606OwnedWorkpieceApplicationHandler>) result)
                .value();
    }

    private static boolean machineCellsAreAir(
            ServerLevel level, OwnedWorkpieceApplicationPlan plan) {
        return level.getBlockState(pos(plan.drivePosition())).isAir()
                && level.getBlockState(pos(plan.deployerPosition())).isAir();
    }

    private static void seed(ServerLevel level, BlockPos3i position, boolean tagged) {
        check(level.setBlockAndUpdate(pos(position), Blocks.CHEST.defaultBlockState()),
                "could not place owned resource buffer");
        ChestBlockEntity chest = chest(level, position);
        Item item = ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath("create", "andesite_alloy"));
        check(item != null, "Create andesite alloy is not registered");
        ItemStack stack = new ItemStack(item, 1);
        if (tagged) {
            CompoundTag tag = stack.getOrCreateTag();
            tag.putString("unknown_external_data", "must_not_normalize");
        }
        chest.setItem(0, stack);
        chest.setChanged();
    }

    private static void prepareBotFloor(ServerLevel level, BlockPos3i workpiece) {
        int floorY = workpiece.y() - 2;
        for (int x = workpiece.x() - 6; x <= workpiece.x() + 3; x++) {
            for (int z = workpiece.z() - 6; z <= workpiece.z() + 3; z++) {
                level.setBlockAndUpdate(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static ChestBlockEntity chest(ServerLevel level, BlockPos3i position) {
        check(level.getBlockEntity(pos(position)) instanceof ChestBlockEntity,
                "owned resource buffer block entity is unavailable");
        return (ChestBlockEntity) level.getBlockEntity(pos(position));
    }

    private static Block block(String value) {
        ResourceLocation key = ResourceLocation.tryParse(value);
        Block block = key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
        check(block != null && key.equals(ForgeRegistries.BLOCKS.getKey(block)),
                "block is not registered: " + value);
        return block;
    }

    private static ResourceId id(Block block) {
        ResourceLocation value = ForgeRegistries.BLOCKS.getKey(block);
        check(value != null, "block has no registry identity");
        return id(value.toString());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static BlockPos3i absolute(
            GameTestHelper helper, int x, int y, int z) {
        BlockPos value = helper.absolutePos(new BlockPos(x, y, z));
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static BlockPos pos(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
