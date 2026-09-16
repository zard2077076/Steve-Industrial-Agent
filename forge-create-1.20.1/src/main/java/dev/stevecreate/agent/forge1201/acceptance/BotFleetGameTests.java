package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.construction.BotFleetExecutor;
import dev.stevecreate.agent.core.execution.construction.AssignmentPolicy;
import dev.stevecreate.agent.core.execution.construction.BotFleetCoordinator;
import dev.stevecreate.agent.core.execution.construction.BotFleetTickResult;
import dev.stevecreate.agent.core.execution.construction.ChunkLoadPolicy;
import dev.stevecreate.agent.core.execution.construction.CapabilityExecutionDescriptor;
import dev.stevecreate.agent.core.execution.construction.CapabilitySupport;
import dev.stevecreate.agent.core.execution.construction.CleanupPolicy;
import dev.stevecreate.agent.core.execution.construction.CleanupScope;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionCommand;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionContext;
import dev.stevecreate.agent.core.execution.construction.ConstructionFailureCode;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskClass;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskGraph;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidenceKind;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ModeCapabilityDeclaration;
import dev.stevecreate.agent.core.execution.construction.RecoveryPolicy;
import dev.stevecreate.agent.core.execution.construction.PriorityPolicy;
import dev.stevecreate.agent.core.execution.construction.RetryBudget;
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskConditionKind;
import dev.stevecreate.agent.core.execution.construction.TaskDependency;
import dev.stevecreate.agent.core.execution.construction.TaskDependencyKind;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionOutcome;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskOwnership;
import dev.stevecreate.agent.core.execution.construction.TaskPostcondition;
import dev.stevecreate.agent.core.execution.construction.TaskSourceKind;
import dev.stevecreate.agent.core.execution.construction.VerifiedPlanTaskSource;
import dev.stevecreate.agent.core.execution.construction.WorkerHealthPolicy;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Real vanilla-entity/material/block acceptance for the isolated test-only Bot backend. */
@PrefixGameTestTemplate(false)
public final class BotFleetGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId PLAN = id("plan:bot_game_test");
    private static final ResourceId SESSION = id("session:bot_game_test");
    private static final ResourceId EXECUTOR = id("executor:bot_game_test");
    private static final ResourceId CAPABILITY = id("capability:bot_game_test");
    private static final ResourceId EXECUTOR_CAPABILITY = id("executor:test_only_bot_actions");
    private static final ResourceId REGION = id("region:bot_game_test");
    private static final ResourceId SOURCE = id("source:bot_test_chest");
    private static final ResourceId MATERIAL_RESERVATION = id("reservation:bot_cobblestone");
    private static final ResourceId DELIVERY = id("delivery:bot_cobblestone");
    private static final ResourceId COBBLESTONE = id("minecraft:cobblestone");

    private BotFleetGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "bot_fleet_01", timeoutTicks = 800)
    public static void singleBotRunsPhysicalActionChainAndIdleReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        check(level.getServer().isSameThread(), "Bot GameTest setup is not on the server thread");
        PhysicalFixture fixture = physicalFixture(helper, 1, 1, 14, 9);
        BlockPos chest = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos chestAccess = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos startOne = helper.absolutePos(new BlockPos(12, 2, 7));
        BlockPos startTwo = helper.absolutePos(new BlockPos(12, 2, 8));
        BlockPos work = helper.absolutePos(new BlockPos(8, 2, 2));
        BlockPos workStand = helper.absolutePos(new BlockPos(8, 2, 3));
        BlockPos lever = helper.absolutePos(new BlockPos(10, 2, 2));
        BlockPos leverStand = helper.absolutePos(new BlockPos(10, 2, 3));
        seedChest(level, chest, Blocks.COBBLESTONE.asItem(), 2);
        BlockState leverOff = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, false);
        level.setBlockAndUpdate(lever, leverOff);
        BlockState leverOn = leverOff.setValue(BlockStateProperties.POWERED, true);

        TestOnlyBotTaskSpec.MaterialBinding fetchTwo = material(2, chest);
        TestOnlyBotTaskSpec.MaterialBinding placeOne = material(1, chest);
        List<ConstructionTask> tasks = new ArrayList<>();
        Map<ResourceId, TestOnlyBotTaskSpec> specs = new LinkedHashMap<>();

        tasks.add(task("task:bot_fetch", TaskKind.FETCH_MATERIAL,
                ConstructionTaskClass.TEST_ONLY_RESOURCE, TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                post("condition:bot_fetch", TaskConditionKind.MATERIAL_RESERVED,
                        ExecutionEvidenceKind.MATERIAL_WITHDRAWN, "subject:bot_chest",
                        Map.of(id("schema:resource"), COBBLESTONE.toString(),
                                id("schema:carried_quantity"), "2",
                                id("schema:source_quantity"), "0")),
                Set.of(), Set.of(MATERIAL_RESERVATION)));
        specs.put(id("task:bot_fetch"), spec("task:bot_fetch", chestAccess,
                Optional.empty(), Optional.empty(), Optional.of(fetchTwo)));

        tasks.add(task("task:bot_transport", TaskKind.TRANSPORT_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT, TaskSourceKind.VERIFIED_ROUTE,
                post("condition:bot_transport", TaskConditionKind.WORK_POSITION_REACHABLE,
                        ExecutionEvidenceKind.NAVIGATION_REACHED, "subject:bot_work",
                        Map.of(id("schema:position"), positionString(workStand),
                                id("schema:carried_quantity"), "2")),
                Set.of(), Set.of(MATERIAL_RESERVATION)));
        specs.put(id("task:bot_transport"), spec("task:bot_transport", workStand,
                Optional.empty(), Optional.empty(), Optional.of(fetchTwo)));

        tasks.add(task("task:bot_place", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, TaskSourceKind.VERIFIED_PLACEMENT,
                post("condition:bot_place", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:bot_work",
                        blockExpected(work, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:bot_work")), Set.of(MATERIAL_RESERVATION)));
        specs.put(id("task:bot_place"), spec("task:bot_place", workStand,
                Optional.of(work), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                Optional.of(placeOne)));

        tasks.add(task("task:bot_verify", TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION, TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                post("condition:bot_verify", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:bot_work",
                        blockExpected(work, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:bot_work")), Set.of()));
        specs.put(id("task:bot_verify"), spec("task:bot_verify", workStand,
                Optional.of(work), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                Optional.empty()));

        tasks.add(task("task:bot_interact", TaskKind.SAFE_MACHINE_INTERACTION,
                ConstructionTaskClass.ORDINARY_BLOCK, TaskSourceKind.VERIFIED_PORT,
                post("condition:bot_interact", TaskConditionKind.SAFE_MACHINE_FACE,
                        ExecutionEvidenceKind.MACHINE_INTERACTION_VERIFIED, "subject:bot_lever",
                        blockExpected(lever, leverOn)),
                Set.of(id("reservation:bot_lever")), Set.of()));
        specs.put(id("task:bot_interact"), spec("task:bot_interact", leverStand,
                Optional.of(lever), Optional.of(leverOn), Optional.empty()));

        tasks.add(task("task:bot_remove", TaskKind.REMOVE_SESSION_OWNED_COMPONENT,
                ConstructionTaskClass.OBSTRUCTION_CLEARANCE, TaskSourceKind.VERIFIED_PLACEMENT,
                post("condition:bot_remove", TaskConditionKind.CLEANUP_COMPLETE,
                        ExecutionEvidenceKind.CLEANUP_VERIFIED, "subject:bot_work",
                        blockExpected(work, Blocks.AIR.defaultBlockState())),
                Set.of(id("reservation:bot_work")), Set.of()));
        specs.put(id("task:bot_remove"), spec("task:bot_remove", workStand,
                Optional.of(work), Optional.of(Blocks.AIR.defaultBlockState()), Optional.empty()));

        tasks.add(task("task:bot_return", TaskKind.RETURN_MATERIAL,
                ConstructionTaskClass.TEST_ONLY_RESOURCE, TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                post("condition:bot_return", TaskConditionKind.MATERIAL_DELIVERED,
                        ExecutionEvidenceKind.MATERIAL_DELIVERED, "subject:bot_chest",
                        Map.of(id("schema:resource"), COBBLESTONE.toString(),
                                id("schema:carried_quantity"), "0",
                                id("schema:source_quantity"), "1")),
                Set.of(), Set.of(MATERIAL_RESERVATION)));
        specs.put(id("task:bot_return"), spec("task:bot_return", chestAccess,
                Optional.empty(), Optional.empty(), Optional.of(placeOne)));

        ConstructionTaskGraph graph = chainedGraph(tasks);
        TestOnlyServerBotWorker worker = TestOnlyServerBotWorker.spawn(
                level, id("worker:physical_one"), SESSION, REGION, fixture.region(),
                startOne, specs, 16, level.getGameTime());
        TestOnlyServerBotWorker standby = TestOnlyServerBotWorker.spawn(
                level, id("worker:physical_standby"), SESSION, REGION, fixture.region(),
                startTwo, specs, 16, level.getGameTime());
        AtomicReference<TestOnlyServerBotWorker> currentWorker = new AtomicReference<>(worker);
        AtomicReference<BotFleetExecutor> executor = new AtomicReference<>(executor(worker, standby));
        AtomicReference<TaskExecutionResult> prior = new AtomicReference<>();
        AtomicReference<TaskAssignment> assignment = new AtomicReference<>();
        AtomicInteger taskIndex = new AtomicInteger();
        AtomicInteger evidenceCount = new AtomicInteger();
        AtomicBoolean reloaded = new AtomicBoolean();
        AtomicBoolean reloadDriftRefused = new AtomicBoolean();
        var entityId = worker.entityId();

        helper.succeedWhen(() -> {
            if (taskIndex.get() >= tasks.size()) {
                check(reloaded.get(), "Bot worker was not restored at the exact idle material boundary");
                check(reloadDriftRefused.get(),
                        "Bot reload did not refuse a drifted physical material source");
                check(currentWorker.get().entityId().equals(entityId),
                        "Bot reload replaced the exact visible entity identity");
                check(countChest(level, chest, Blocks.COBBLESTONE.asItem()) == 1,
                        "Bot material chain did not preserve exact physical chest arithmetic");
                check(currentWorker.get().ledger().inventories()
                                .get(currentWorker.get().workerId()).quantity(COBBLESTONE) == 0,
                        "Bot material chain left an unexpected carried quantity");
                check(level.getBlockState(work).isAir(),
                        "Bot session-owned cleanup did not restore the exact prior state");
                check(level.getBlockState(lever).equals(leverOn),
                        "Bot safe machine-face interaction did not retain exact lever readback");
                check(evidenceCount.get() == tasks.size(),
                        "Every Bot task must report exactly one objective evidence record");
                currentWorker.get().discardEntity(level.getGameTime());
                standby.discardEntity(level.getGameTime());
                LOGGER.info("BOT_PHYSICAL_ACTION_CHAIN PASS spawned=true identityStable=true "
                        + "navigate=true fetch=2 carry=true transport=true place=true verify=true "
                        + "safeMachineFace=true removeSessionOwned=true returnLeftover=1 "
                        + "idleReload=true reloadDriftRefused=true duplicateWithdrawal=false "
                        + "playerInventoryReads=0 "
                        + "privateContainerReads=0 existingEntity=true "
                        + "smoothAdjacentMovement=true teleport=false evidence={}",
                        evidenceCount.get());
                return;
            }
            ConstructionTask task = tasks.get(taskIndex.get());
            long tick = level.getGameTime();
            if (assignment.get() == null) {
                assignment.set(assignment(graph, task, currentWorker.get().workerId(), tick,
                        taskIndex.get() + 1));
            }
            ConstructionExecutionCommand command = prior.get() == null
                    ? ConstructionExecutionCommand.START : ConstructionExecutionCommand.CONTINUE;
            TaskExecutionResult result = executor.get().execute(
                    graph, task, assignment.get(), context(command, tick, prior.get()));
            if (result.outcome() == TaskExecutionOutcome.PENDING) {
                prior.set(result);
                throw new GameTestAssertException("Bot action chain is still advancing one bounded step");
            }
            check(result.outcome() == TaskExecutionOutcome.SUCCEEDED,
                    "Bot task failed: " + task.taskId() + " -> " + result.detail());
            evidenceCount.addAndGet(result.evidence().size());
            taskIndex.incrementAndGet();
            prior.set(null);
            assignment.set(null);
            if (taskIndex.get() == 1) {
                TestOnlyServerBotWorkerState state = currentWorker.get().snapshotState(tick);
                ChestBlockEntity sourceChest = (ChestBlockEntity) level.getBlockEntity(chest);
                sourceChest.setItem(0, new ItemStack(Blocks.COBBLESTONE.asItem(), 1));
                sourceChest.setChanged();
                try {
                    TestOnlyServerBotWorker.restore(
                            level, REGION, fixture.region(), specs, state);
                    check(false, "Bot reload accepted drifted physical source contents");
                } catch (IllegalArgumentException expected) {
                    reloadDriftRefused.set(true);
                }
                sourceChest.clearContent();
                sourceChest.setChanged();
                TestOnlyServerBotWorker restored = TestOnlyServerBotWorker.restore(
                        level, REGION, fixture.region(), specs, state);
                currentWorker.set(restored);
                executor.set(executor(restored, standby));
                reloaded.set(true);
            }
            throw new GameTestAssertException("Bot action chain advanced to its next exact task");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "bot_fleet_02", timeoutTicks = 500)
    public static void botRepathsCancelsAndRefusesOutsideOrRevokedAuthority(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhysicalFixture fixture = physicalFixture(helper, 1, 1, 14, 9);
        BlockPos chest = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos startOne = helper.absolutePos(new BlockPos(2, 2, 7));
        BlockPos startTwo = helper.absolutePos(new BlockPos(2, 2, 8));
        BlockPos verifyTarget = helper.absolutePos(new BlockPos(12, 2, 2));
        BlockPos verifyStand = helper.absolutePos(new BlockPos(12, 2, 3));
        level.setBlockAndUpdate(verifyTarget, Blocks.COBBLESTONE.defaultBlockState());
        seedChest(level, chest, Blocks.COBBLESTONE.asItem(), 1);

        ConstructionTask verify = task("task:bot_repath", TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION, TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                post("condition:bot_repath", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:bot_repath",
                        blockExpected(verifyTarget, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:bot_repath")), Set.of());
        ConstructionTask outside = task("task:bot_outside", TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION, TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                post("condition:bot_outside", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:bot_outside",
                        blockExpected(verifyTarget, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:bot_outside")), Set.of());
        ConstructionTask revoked = task("task:bot_revoked", TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION, TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                post("condition:bot_revoked", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:bot_revoked",
                        blockExpected(verifyTarget, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:bot_revoked")), Set.of());
        ConstructionTaskGraph graph = graph(List.of(verify, outside, revoked), List.of());
        BlockPos outsidePosition = fixture.region().maximum().east();
        Map<ResourceId, TestOnlyBotTaskSpec> specs = Map.of(
                verify.taskId(), spec("task:bot_repath", verifyStand,
                        Optional.of(verifyTarget), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                        Optional.empty()),
                outside.taskId(), spec("task:bot_outside", outsidePosition,
                        Optional.of(verifyTarget), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                        Optional.empty()),
                revoked.taskId(), spec("task:bot_revoked", verifyStand,
                        Optional.of(verifyTarget), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                        Optional.empty()));
        TestOnlyServerBotWorker worker = TestOnlyServerBotWorker.spawn(
                level, id("worker:policy_one"), SESSION, REGION, fixture.region(),
                startOne, specs, 8, level.getGameTime());
        TestOnlyServerBotWorker standby = TestOnlyServerBotWorker.spawn(
                level, id("worker:policy_standby"), SESSION, REGION, fixture.region(),
                startTwo, specs, 8, level.getGameTime());
        BotFleetExecutor executor = executor(worker, standby);
        AtomicReference<TaskAssignment> firstAssignment = new AtomicReference<>();
        AtomicReference<TaskExecutionResult> prior = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        AtomicBoolean detour = new AtomicBoolean();
        AtomicBoolean obstaclePlaced = new AtomicBoolean();

        helper.succeedWhen(() -> {
            long tick = level.getGameTime();
            if (phase.get() == 0) {
                if (firstAssignment.get() == null) {
                    firstAssignment.set(assignment(graph, verify, worker.workerId(), tick, 1));
                }
                ConstructionExecutionCommand command = prior.get() == null
                        ? ConstructionExecutionCommand.START : ConstructionExecutionCommand.CONTINUE;
                TaskExecutionResult result = executor.execute(
                        graph, verify, firstAssignment.get(), context(command, tick, prior.get()));
                if (!obstaclePlaced.get() && result.outcome() == TaskExecutionOutcome.PENDING) {
                    BlockPos nextStraight = worker.snapshot(tick).position().x() < verifyStand.getX()
                            ? new BlockPos(worker.snapshot(tick).position().x() + 1,
                            worker.snapshot(tick).position().y(), worker.snapshot(tick).position().z())
                            : verifyStand;
                    if (!nextStraight.equals(verifyStand)) {
                        level.setBlockAndUpdate(nextStraight, Blocks.OBSIDIAN.defaultBlockState());
                        obstaclePlaced.set(true);
                    }
                }
                detour.set(detour.get() || worker.snapshot(tick).position().z() != startOne.getZ());
                if (result.outcome() == TaskExecutionOutcome.PENDING) {
                    prior.set(result);
                    if (obstaclePlaced.get() && detour.get()) {
                        TaskExecutionResult cancelled = executor.execute(
                                graph, verify, firstAssignment.get(),
                                context(ConstructionExecutionCommand.CANCEL, tick, result));
                        check(cancelled.outcome() == TaskExecutionOutcome.CANCELLED,
                                "Bot cancellation did not stop the exact pending assignment");
                        check(worker.snapshot(tick).status()
                                        == dev.stevecreate.agent.core.execution.construction.BotWorkerStatus.IDLE,
                                "Cancelled Bot did not return to IDLE");
                        phase.incrementAndGet();
                        prior.set(null);
                    }
                    throw new GameTestAssertException("Bot policy test is waiting for detour/cancel");
                }
                check(false, "Bot repath task unexpectedly terminated before cancellation");
            }
            if (phase.get() == 1) {
                TaskAssignment assignment = assignment(graph, outside, worker.workerId(), tick, 2);
                TaskExecutionResult result = executor.execute(
                        graph, outside, assignment, context(ConstructionExecutionCommand.START, tick, null));
                check(result.outcome() == TaskExecutionOutcome.TERMINAL_FAILURE
                                && result.failure().orElseThrow().code().equals(
                                ConstructionFailureCode.PATH_OUTSIDE_AUTHORIZED_REGION.id()),
                        "Bot did not refuse an out-of-region navigation target precisely: " + result.detail());
                phase.incrementAndGet();
                throw new GameTestAssertException("Bot policy test advanced past region refusal");
            }
            if (phase.get() == 2) {
                TaskAssignment assignment = assignment(graph, revoked, worker.workerId(), tick, 3);
                String key = TestOnlyBotWorldGuard.EXPECTED_GAME_DIR;
                String expected = System.getProperty(key);
                check(expected != null, "Bot authority-revocation test lacks expected gameDir");
                System.clearProperty(key);
                TaskExecutionResult result;
                try {
                    result = executor.execute(
                            graph, revoked, assignment,
                            context(ConstructionExecutionCommand.START, tick, null));
                } finally {
                    System.setProperty(key, expected);
                }
                check(result.outcome() == TaskExecutionOutcome.TERMINAL_FAILURE
                                && result.failure().orElseThrow().code().equals(
                                ConstructionFailureCode.PATH_OUTSIDE_AUTHORIZED_REGION.id()),
                        "Bot did not fail closed when its isolated path authority was revoked");
                check(level.getBlockState(verifyTarget).is(Blocks.COBBLESTONE),
                        "Revoked Bot authority changed the verification target");
                worker.discardEntity(tick);
                standby.discardEntity(tick);
                LOGGER.info("BOT_POLICY_GAMETEST PASS adjacentMovement=true repath=true "
                        + "cancel=true idleAfterCancel=true outOfRegionRefused=true "
                        + "authorityRevoked=true worldMutationAfterRevoke=false attackActions=0 "
                        + "unknownRightClicks=0 teleportBypass=false");
                return;
            }
            check(false, "Unexpected Bot policy test phase");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "bot_fleet_03", timeoutTicks = 800)
    public static void twoPhysicalBotsExecuteParallelMaterialDags(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhysicalFixture fixture = physicalFixture(helper, 1, 1, 15, 10);
        BlockPos chestA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos accessA = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos chestB = helper.absolutePos(new BlockPos(2, 2, 9));
        BlockPos accessB = helper.absolutePos(new BlockPos(3, 2, 9));
        BlockPos startA = helper.absolutePos(new BlockPos(13, 2, 4));
        BlockPos startB = helper.absolutePos(new BlockPos(13, 2, 7));
        BlockPos targetA = helper.absolutePos(new BlockPos(9, 2, 3));
        BlockPos standA = helper.absolutePos(new BlockPos(9, 2, 4));
        BlockPos targetB = helper.absolutePos(new BlockPos(9, 2, 8));
        BlockPos standB = helper.absolutePos(new BlockPos(9, 2, 7));
        seedChest(level, chestA, Blocks.COBBLESTONE.asItem(), 1);
        seedChest(level, chestB, Blocks.COBBLESTONE.asItem(), 1);

        ResourceId sourceA = id("source:fleet_a");
        ResourceId sourceB = id("source:fleet_b");
        ResourceId reservationA = id("reservation:fleet_material_a");
        ResourceId reservationB = id("reservation:fleet_material_b");
        ResourceId deliveryA = id("delivery:fleet_a");
        ResourceId deliveryB = id("delivery:fleet_b");
        TestOnlyBotTaskSpec.MaterialBinding materialA = material(
                sourceA, reservationA, deliveryA, 1, chestA);
        TestOnlyBotTaskSpec.MaterialBinding materialB = material(
                sourceB, reservationB, deliveryB, 1, chestB);

        ConstructionTask fetchA = task("task:fleet_fetch_a", TaskKind.FETCH_MATERIAL,
                ConstructionTaskClass.TEST_ONLY_RESOURCE, TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                post("condition:fleet_fetch_a", TaskConditionKind.MATERIAL_RESERVED,
                        ExecutionEvidenceKind.MATERIAL_WITHDRAWN, "subject:fleet_chest_a",
                        Map.of(id("schema:resource"), COBBLESTONE.toString(),
                                id("schema:carried_quantity"), "1",
                                id("schema:source_quantity"), "0")),
                Set.of(), Set.of(reservationA));
        ConstructionTask fetchB = task("task:fleet_fetch_b", TaskKind.FETCH_MATERIAL,
                ConstructionTaskClass.TEST_ONLY_RESOURCE, TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                post("condition:fleet_fetch_b", TaskConditionKind.MATERIAL_RESERVED,
                        ExecutionEvidenceKind.MATERIAL_WITHDRAWN, "subject:fleet_chest_b",
                        Map.of(id("schema:resource"), COBBLESTONE.toString(),
                                id("schema:carried_quantity"), "1",
                                id("schema:source_quantity"), "0")),
                Set.of(), Set.of(reservationB));
        ConstructionTask transportA = task("task:fleet_transport_a", TaskKind.TRANSPORT_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT, TaskSourceKind.VERIFIED_ROUTE,
                post("condition:fleet_transport_a", TaskConditionKind.WORK_POSITION_REACHABLE,
                        ExecutionEvidenceKind.NAVIGATION_REACHED, "subject:fleet_work_a",
                        Map.of(id("schema:position"), positionString(standA),
                                id("schema:carried_quantity"), "1")),
                Set.of(), Set.of(reservationA));
        ConstructionTask transportB = task("task:fleet_transport_b", TaskKind.TRANSPORT_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT, TaskSourceKind.VERIFIED_ROUTE,
                post("condition:fleet_transport_b", TaskConditionKind.WORK_POSITION_REACHABLE,
                        ExecutionEvidenceKind.NAVIGATION_REACHED, "subject:fleet_work_b",
                        Map.of(id("schema:position"), positionString(standB),
                                id("schema:carried_quantity"), "1")),
                Set.of(), Set.of(reservationB));
        ConstructionTask placeA = task("task:fleet_place_a", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, TaskSourceKind.VERIFIED_PLACEMENT,
                post("condition:fleet_place_a", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:fleet_work_a",
                        blockExpected(targetA, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:fleet_work_a")), Set.of(reservationA));
        ConstructionTask placeB = task("task:fleet_place_b", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, TaskSourceKind.VERIFIED_PLACEMENT,
                post("condition:fleet_place_b", TaskConditionKind.CURRENT_STATE_MATCHES,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED, "subject:fleet_work_b",
                        blockExpected(targetB, Blocks.COBBLESTONE.defaultBlockState())),
                Set.of(id("reservation:fleet_work_b")), Set.of(reservationB));
        List<ConstructionTask> tasks = List.of(
                fetchA, fetchB, transportA, transportB, placeA, placeB);
        List<TaskDependency> dependencies = List.of(
                dependency(fetchA, transportA), dependency(fetchB, transportB),
                dependency(transportA, placeA), dependency(transportB, placeB));
        ConstructionTaskGraph graph = graph(tasks, dependencies);
        Map<ResourceId, TestOnlyBotTaskSpec> specs = Map.of(
                fetchA.taskId(), spec("task:fleet_fetch_a", accessA,
                        Optional.empty(), Optional.empty(), Optional.of(materialA)),
                fetchB.taskId(), spec("task:fleet_fetch_b", accessB,
                        Optional.empty(), Optional.empty(), Optional.of(materialB)),
                transportA.taskId(), spec("task:fleet_transport_a", standA,
                        Optional.empty(), Optional.empty(), Optional.of(materialA)),
                transportB.taskId(), spec("task:fleet_transport_b", standB,
                        Optional.empty(), Optional.empty(), Optional.of(materialB)),
                placeA.taskId(), spec("task:fleet_place_a", standA,
                        Optional.of(targetA), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                        Optional.of(materialA)),
                placeB.taskId(), spec("task:fleet_place_b", standB,
                        Optional.of(targetB), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                        Optional.of(materialB)));
        TestOnlyServerBotWorker workerA = TestOnlyServerBotWorker.spawn(
                level, id("worker:a_physical"), SESSION, REGION, fixture.region(),
                startA, specs, 8, level.getGameTime());
        TestOnlyServerBotWorker workerB = TestOnlyServerBotWorker.spawn(
                level, id("worker:b_physical"), SESSION, REGION, fixture.region(),
                startB, specs, 8, level.getGameTime());
        BotFleetExecutor executor = executor(workerA, workerB);
        BotFleetCoordinator coordinator = new BotFleetCoordinator(
                SESSION, graph, executor,
                Map.of(
                        id("reservation:fleet_work_a"), position(targetA),
                        id("reservation:fleet_work_b"), position(targetB)),
                AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                PriorityPolicy.TOPOLOGICAL_THEN_ID,
                new RetryBudget(2, 0),
                new WorkerHealthPolicy(1, true),
                ChunkLoadPolicy.REQUIRE_ALREADY_LOADED,
                1_000);
        AtomicBoolean parallelAssignments = new AtomicBoolean();
        AtomicBoolean parallelMovement = new AtomicBoolean();
        AtomicInteger maximumActive = new AtomicInteger();

        helper.succeedWhen(() -> {
            long tick = level.getGameTime();
            BlockPos beforeA = block(workerA.snapshot(tick).position());
            BlockPos beforeB = block(workerB.snapshot(tick).position());
            BotFleetTickResult result = coordinator.tick(tick);
            maximumActive.set(Math.max(maximumActive.get(), result.activeAssignments().size()));
            parallelAssignments.set(parallelAssignments.get()
                    || result.activeAssignments().size() == 2);
            BlockPos afterA = block(workerA.snapshot(tick).position());
            BlockPos afterB = block(workerB.snapshot(tick).position());
            parallelMovement.set(parallelMovement.get()
                    || (!afterA.equals(beforeA) && !afterB.equals(beforeB)));
            if (result.completedResults().size() < tasks.size()) {
                check(result.terminalFailures().isEmpty(),
                        "Physical Bot fleet produced a terminal failure: " + result.terminalFailures());
                throw new GameTestAssertException("Physical Bot fleet DAG is still executing");
            }
            check(parallelAssignments.get() && parallelMovement.get() && maximumActive.get() == 2,
                    "Two physical Bots did not execute assignments/movement in parallel");
            check(result.completedResults().keySet().containsAll(
                            tasks.stream().map(ConstructionTask::taskId).toList()),
                    "Physical Bot fleet did not complete every DAG task");
            check(level.getBlockState(targetA).is(Blocks.COBBLESTONE)
                            && level.getBlockState(targetB).is(Blocks.COBBLESTONE),
                    "Physical Bot fleet did not place both exact target blocks");
            check(countChest(level, chestA, Blocks.COBBLESTONE.asItem()) == 0
                            && countChest(level, chestB, Blocks.COBBLESTONE.asItem()) == 0,
                    "Physical Bot fleet did not consume both exact dedicated sources");
            check(workerA.ledger().inventories().get(workerA.workerId()).usedCapacity() == 0
                            && workerB.ledger().inventories().get(workerB.workerId()).usedCapacity() == 0,
                    "Physical Bot fleet left carried material after both placements");
            workerA.discardEntity(tick);
            workerB.discardEntity(tick);
            level.setBlockAndUpdate(chestA, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(chestB, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(targetA, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(targetB, Blocks.AIR.defaultBlockState());
            LOGGER.info("BOT_FLEET_PHYSICAL_DAG PASS workers=2 maxActive={} "
                    + "parallelAssignments=true parallelMovement=true parallelFetch=true "
                    + "parallelTransport=true sequentialDependencies=true workReservations=2 "
                    + "placements=2 physicalSources=2 completedTasks=6 existingEntity=true "
                    + "smoothAdjacentMovement=true teleport=false cleanup=true "
                    + "terminalFailures=0",
                    maximumActive.get());
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "bot_fleet_04_three", timeoutTicks = 3_000)
    public static void threeExistingConstructionBotsRunParallelPhysicalTasks(
            GameTestHelper helper) {
        runScaledPhysicalFleet(helper, 3);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "bot_fleet_05_five", timeoutTicks = 3_000)
    public static void fiveExistingConstructionBotsRunParallelPhysicalTasks(
            GameTestHelper helper) {
        runScaledPhysicalFleet(helper, 5);
    }

    private static void runScaledPhysicalFleet(GameTestHelper helper, int workerCount) {
        ServerLevel level = helper.getLevel();
        PhysicalFixture fixture = physicalFixture(helper, 1, 1, 15, 24);
        List<ConstructionTask> tasks = new ArrayList<>();
        List<TaskDependency> dependencies = new ArrayList<>();
        Map<ResourceId, TestOnlyBotTaskSpec> specs = new LinkedHashMap<>();
        Map<ResourceId, BlockPos3i> workPositions = new LinkedHashMap<>();
        List<BlockPos> chests = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        List<BlockPos> starts = new ArrayList<>();
        for (int index = 0; index < workerCount; index++) {
            int z = 2 + index * 5;
            String suffix = Integer.toString(index);
            BlockPos chest = helper.absolutePos(new BlockPos(2, 2, z));
            BlockPos access = helper.absolutePos(new BlockPos(3, 2, z));
            BlockPos target = helper.absolutePos(new BlockPos(9, 2, z));
            BlockPos stand = helper.absolutePos(new BlockPos(9, 2, z + 1));
            BlockPos start = helper.absolutePos(new BlockPos(13, 2, z + 1));
            chests.add(chest);
            targets.add(target);
            starts.add(start);
            seedChest(level, chest, Blocks.COBBLESTONE.asItem(), 1);

            ResourceId source = id("source:scaled_fleet_" + suffix);
            ResourceId materialReservation = id("reservation:scaled_material_" + suffix);
            ResourceId delivery = id("delivery:scaled_fleet_" + suffix);
            ResourceId workReservation = id("reservation:scaled_work_" + suffix);
            TestOnlyBotTaskSpec.MaterialBinding material = material(
                    source, materialReservation, delivery, 1, chest);
            ConstructionTask fetch = task(
                    "task:scaled_fetch_" + suffix, TaskKind.FETCH_MATERIAL,
                    ConstructionTaskClass.TEST_ONLY_RESOURCE,
                    TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                    post("condition:scaled_fetch_" + suffix,
                            TaskConditionKind.MATERIAL_RESERVED,
                            ExecutionEvidenceKind.MATERIAL_WITHDRAWN,
                            "subject:scaled_chest_" + suffix,
                            Map.of(id("schema:resource"), COBBLESTONE.toString(),
                                    id("schema:carried_quantity"), "1",
                                    id("schema:source_quantity"), "0")),
                    Set.of(), Set.of(materialReservation));
            ConstructionTask transport = task(
                    "task:scaled_transport_" + suffix, TaskKind.TRANSPORT_MATERIAL,
                    ConstructionTaskClass.MATERIAL_TRANSPORT, TaskSourceKind.VERIFIED_ROUTE,
                    post("condition:scaled_transport_" + suffix,
                            TaskConditionKind.WORK_POSITION_REACHABLE,
                            ExecutionEvidenceKind.NAVIGATION_REACHED,
                            "subject:scaled_work_" + suffix,
                            Map.of(id("schema:position"), positionString(stand),
                                    id("schema:carried_quantity"), "1")),
                    Set.of(), Set.of(materialReservation));
            ConstructionTask place = task(
                    "task:scaled_place_" + suffix, TaskKind.PLACE_COMPONENT,
                    ConstructionTaskClass.ORDINARY_BLOCK, TaskSourceKind.VERIFIED_PLACEMENT,
                    post("condition:scaled_place_" + suffix,
                            TaskConditionKind.CURRENT_STATE_MATCHES,
                            ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                            "subject:scaled_work_" + suffix,
                            blockExpected(target, Blocks.COBBLESTONE.defaultBlockState())),
                    Set.of(workReservation), Set.of(materialReservation));
            tasks.add(fetch);
            tasks.add(transport);
            tasks.add(place);
            dependencies.add(dependency(fetch, transport));
            dependencies.add(dependency(transport, place));
            specs.put(fetch.taskId(), spec(fetch.taskId().toString(), access,
                    Optional.empty(), Optional.empty(), Optional.of(material)));
            specs.put(transport.taskId(), spec(transport.taskId().toString(), stand,
                    Optional.empty(), Optional.empty(), Optional.of(material)));
            specs.put(place.taskId(), spec(place.taskId().toString(), stand,
                    Optional.of(target), Optional.of(Blocks.COBBLESTONE.defaultBlockState()),
                    Optional.of(material)));
            workPositions.put(workReservation, position(target));
        }
        ConstructionTaskGraph graph = graph(tasks, dependencies);
        List<TestOnlyServerBotWorker> workers = new ArrayList<>();
        for (int index = 0; index < workerCount; index++) {
            workers.add(TestOnlyServerBotWorker.spawn(
                    level, id("worker:scaled_" + workerCount + "_" + index),
                    SESSION, REGION, fixture.region(), starts.get(index), specs,
                    8, level.getGameTime()));
        }
        check(workers.stream().allMatch(worker ->
                        level.getEntity(worker.entityId())
                                instanceof dev.stevecreate.agent.forge1201.entity
                                .ConstructionBotEntity),
                "Scaled fleet did not reuse the registered ConstructionBotEntity");
        BotFleetExecutor executor = new BotFleetExecutor(
                EXECUTOR, Set.of(EXECUTOR_CAPABILITY), workers);
        BotFleetCoordinator coordinator = new BotFleetCoordinator(
                SESSION, graph, executor, workPositions,
                AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                PriorityPolicy.TOPOLOGICAL_THEN_ID,
                new RetryBudget(2, 0), new WorkerHealthPolicy(1, true),
                ChunkLoadPolicy.REQUIRE_ALREADY_LOADED, 2_000);
        AtomicInteger maximumActive = new AtomicInteger();
        Set<ResourceId> movedWorkers = new java.util.LinkedHashSet<>();
        AtomicBoolean botOverlap = new AtomicBoolean();

        helper.succeedWhen(() -> {
            long tick = level.getGameTime();
            Map<ResourceId, BlockPos3i> before = workers.stream().collect(
                    java.util.stream.Collectors.toMap(
                            TestOnlyServerBotWorker::workerId,
                            worker -> worker.snapshot(tick).position()));
            BotFleetTickResult result = coordinator.tick(tick);
            maximumActive.set(Math.max(maximumActive.get(), result.activeAssignments().size()));
            List<BlockPos3i> after = workers.stream().map(worker -> {
                BlockPos3i position = worker.snapshot(tick).position();
                if (!position.equals(before.get(worker.workerId()))) {
                    movedWorkers.add(worker.workerId());
                }
                return position;
            }).toList();
            botOverlap.set(botOverlap.get() || after.stream().distinct().count() != after.size());
            if (result.completedResults().size() < tasks.size()) {
                check(result.terminalFailures().isEmpty(),
                        "Scaled physical fleet failed: " + result.terminalFailures());
                throw new GameTestAssertException("Scaled physical fleet is still executing");
            }
            check(maximumActive.get() == workerCount
                            && movedWorkers.size() == workerCount
                            && !botOverlap.get(),
                    "Scaled fleet lost full concurrency, movement or exclusive worker cells");
            check(targets.stream().allMatch(target ->
                            level.getBlockState(target).is(Blocks.COBBLESTONE))
                            && chests.stream().allMatch(chest ->
                            countChest(level, chest, Blocks.COBBLESTONE.asItem()) == 0),
                    "Scaled fleet did not conserve every physical source/placement");
            check(workers.stream().allMatch(worker ->
                            worker.ledger().inventories().get(worker.workerId()).usedCapacity() == 0),
                    "Scaled fleet left carried material");
            List<java.util.UUID> entityIds = workers.stream()
                    .map(TestOnlyServerBotWorker::entityId).toList();
            workers.forEach(worker -> worker.discardEntity(tick));
            check(entityIds.stream().allMatch(id -> level.getEntity(id) == null),
                    "Scaled fleet cleanup left a worker entity");
            chests.forEach(position -> level.setBlockAndUpdate(
                    position, Blocks.AIR.defaultBlockState()));
            targets.forEach(position -> level.setBlockAndUpdate(
                    position, Blocks.AIR.defaultBlockState()));
            LOGGER.info("BOT_FLEET_SCALED_{} PASS workers={} maxActive={} "
                            + "existingEntity=true sharedKernel=GraphNeutralFleetCoordinator "
                            + "parallelAssignments=true allMoved=true botOverlap=false "
                            + "physicalSources={} placements={} completedTasks={} "
                            + "leases={} teleport=false playerInventoryReads=0 "
                            + "privateContainerReads=0 cleanup=true terminalFailures=0",
                    workerCount, workerCount, maximumActive.get(), workerCount, workerCount,
                    tasks.size(), workPositions.size());
        });
    }

    private static BotFleetExecutor executor(
            TestOnlyServerBotWorker worker,
            TestOnlyServerBotWorker standby) {
        return new BotFleetExecutor(EXECUTOR, Set.of(EXECUTOR_CAPABILITY), List.of(worker, standby));
    }

    private static PhysicalFixture physicalFixture(
            GameTestHelper helper,
            int minX,
            int minZ,
            int maxX,
            int maxZ) {
        ServerLevel level = helper.getLevel();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 1, z)), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 3, z)), Blocks.AIR.defaultBlockState());
            }
        }
        return new PhysicalFixture(new TestOnlyServerBotWorker.TestRegion(
                helper.absolutePos(new BlockPos(minX, 2, minZ)),
                helper.absolutePos(new BlockPos(maxX, 3, maxZ))));
    }

    private static void seedChest(ServerLevel level, BlockPos position, Item item, int quantity) {
        level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
        check(level.getBlockEntity(position) instanceof ChestBlockEntity,
                "Bot material source did not create a real chest BlockEntity");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(position);
        chest.clearContent();
        chest.setItem(0, new ItemStack(item, quantity));
        chest.setChanged();
    }

    private static int countChest(ServerLevel level, BlockPos position, Item item) {
        check(level.getBlockEntity(position) instanceof ChestBlockEntity,
                "Bot material source chest disappeared");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(position);
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).is(item)) total += chest.getItem(slot).getCount();
        }
        return total;
    }

    private static TestOnlyBotTaskSpec.MaterialBinding material(int quantity, BlockPos chest) {
        return material(SOURCE, MATERIAL_RESERVATION, DELIVERY, quantity, chest);
    }

    private static TestOnlyBotTaskSpec.MaterialBinding material(
            ResourceId source,
            ResourceId reservation,
            ResourceId delivery,
            int quantity,
            BlockPos chest) {
        return new TestOnlyBotTaskSpec.MaterialBinding(
                source, reservation, delivery, COBBLESTONE,
                chest, Blocks.COBBLESTONE.asItem(), quantity);
    }

    private static TestOnlyBotTaskSpec spec(
            String taskId,
            BlockPos navigation,
            Optional<BlockPos> target,
            Optional<BlockState> state,
            Optional<TestOnlyBotTaskSpec.MaterialBinding> material) {
        return new TestOnlyBotTaskSpec(id(taskId), navigation, target, state, material);
    }

    private static ConstructionTask task(
            String taskId,
            TaskKind kind,
            ConstructionTaskClass taskClass,
            TaskSourceKind sourceKind,
            TaskPostcondition postcondition,
            Set<ResourceId> placementReservations,
            Set<ResourceId> materialReservations) {
        return new ConstructionTask(
                id(taskId),
                new VerifiedPlanTaskSource(
                        PLAN, sourceKind,
                        id("physical:" + taskId.substring(taskId.indexOf(':') + 1)), 0),
                CAPABILITY,
                kind,
                taskClass,
                Set.of(ExecutionMode.BOTS),
                List.of(),
                List.of(postcondition),
                new RetryPolicy(2, 0, Set.of(ConstructionFailureCode.NAVIGATION_BLOCKED.id())),
                true,
                RecoveryPolicy.REFUSE,
                new CleanupPolicy(CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                        false, true, 16),
                placementReservations,
                materialReservations,
                Set.of(),
                1_200,
                Map.of(id("test:binding"), "test-only-server-bot"));
    }

    private static TaskPostcondition post(
            String conditionId,
            TaskConditionKind conditionKind,
            ExecutionEvidenceKind evidenceKind,
            String subject,
            Map<ResourceId, String> expected) {
        return new TaskPostcondition(
                id(conditionId), conditionKind, id(subject), evidenceKind, expected);
    }

    private static ConstructionTaskGraph chainedGraph(List<ConstructionTask> tasks) {
        List<TaskDependency> dependencies = new ArrayList<>();
        for (int index = 1; index < tasks.size(); index++) {
            ConstructionTask prior = tasks.get(index - 1);
            ConstructionTask next = tasks.get(index);
            dependencies.add(new TaskDependency(
                    prior.taskId(), next.taskId(), TaskDependencyKind.FINISH_TO_START,
                    prior.postconditionIds()));
        }
        return graph(tasks, dependencies);
    }

    private static TaskDependency dependency(
            ConstructionTask predecessor,
            ConstructionTask successor) {
        return new TaskDependency(
                predecessor.taskId(), successor.taskId(), TaskDependencyKind.FINISH_TO_START,
                predecessor.postconditionIds());
    }

    private static ConstructionTaskGraph graph(
            List<ConstructionTask> tasks,
            List<TaskDependency> dependencies) {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, new ModeCapabilityDeclaration(
                ExecutionMode.DIRECT, CapabilitySupport.UNSUPPORTED, Set.of(),
                "physical Bot fixture requires BOTS"));
        modes.put(ExecutionMode.BOTS, new ModeCapabilityDeclaration(
                ExecutionMode.BOTS, CapabilitySupport.SUPPORTED,
                Set.of(EXECUTOR_CAPABILITY), ""));
        modes.put(ExecutionMode.HYBRID, new ModeCapabilityDeclaration(
                ExecutionMode.HYBRID, CapabilitySupport.UNSUPPORTED, Set.of(),
                "physical Bot fixture requires BOTS"));
        CapabilityExecutionDescriptor descriptor = new CapabilityExecutionDescriptor(
                CAPABILITY, id("implementation:test_only_bot"), id("adapter:forge1201_test_bot"),
                "bot-game-runtime-v1", EnumSet.copyOf(tasks.stream()
                .map(ConstructionTask::kind).collect(java.util.stream.Collectors.toSet())),
                modes, Map.of(id("test:version"), "1"));
        return new ConstructionTaskGraph(
                id("graph:bot_game_test_" + tasks.get(0).taskId().path()),
                PLAN, "bot-game-runtime-v1", "bot-game-world-snapshot-v1",
                List.of(descriptor), tasks, dependencies);
    }

    private static TaskAssignment assignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            ResourceId workerId,
            long tick,
            int generation) {
        TaskOwnership ownership = new TaskOwnership(
                SESSION, graph.graphId(), task.taskId(), EXECUTOR, ExecutionMode.BOTS,
                Optional.of(workerId), generation, tick, tick + 1_000,
                Integer.toHexString(Objects.hash(graph.graphId(), task.taskId(), generation))
                        .repeat(64).substring(0, 64));
        return TaskAssignment.assign(
                graph, task.taskId(), id("assignment:" + task.taskId().path() + "_" + generation),
                ownership, 1, tick);
    }

    private static ConstructionExecutionContext context(
            ConstructionExecutionCommand command,
            long tick,
            TaskExecutionResult prior) {
        return new ConstructionExecutionContext(
                command,
                tick,
                prior == null ? List.of() : prior.evidence(),
                prior == null ? Optional.empty() : prior.failure(),
                command == ConstructionExecutionCommand.CANCEL
                        ? Optional.of(id("reason:bot_test_cancel")) : Optional.empty());
    }

    private static Map<ResourceId, String> blockExpected(BlockPos target, BlockState state) {
        var blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return Map.of(
                id("schema:block"), blockId == null ? "unregistered" : blockId.toString(),
                id("schema:block_state"), stateString(state),
                id("schema:target"), positionString(target));
    }

    private static String stateString(BlockState state) {
        var blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        StringBuilder value = new StringBuilder(blockId == null ? "unregistered" : blockId.toString());
        List<Map.Entry<Property<?>, Comparable<?>>> properties = new ArrayList<>(
                state.getValues().entrySet());
        properties.sort(java.util.Comparator.comparing(entry -> entry.getKey().getName()));
        if (!properties.isEmpty()) {
            value.append('[');
            for (int index = 0; index < properties.size(); index++) {
                if (index > 0) value.append(',');
                Map.Entry<Property<?>, Comparable<?>> entry = properties.get(index);
                value.append(entry.getKey().getName()).append('=').append(entry.getValue());
            }
            value.append(']');
        }
        return value.toString();
    }

    private static String positionString(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static BlockPos3i position(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private record PhysicalFixture(TestOnlyServerBotWorker.TestRegion region) {}
}
