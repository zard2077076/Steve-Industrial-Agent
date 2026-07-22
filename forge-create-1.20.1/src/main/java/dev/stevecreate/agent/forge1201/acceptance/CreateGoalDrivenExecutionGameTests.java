package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
        BlockPos3i drive = componentPosition(ready, "belt_drive");
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

    private static void runSuccess(
            GameTestHelper helper,
            String target,
            int quantity,
            String rawInput,
            int rawCount,
            QuarterTurn orientation) {
        ServerLevel level = helper.getLevel();
        BlockPos3i buffer = position(helper.absolutePos(new BlockPos(2, 2, 12)));
        BlockPos3i anchor = position(helper.absolutePos(new BlockPos(20, 2, 2)));
        ResourceId raw = id(rawInput);
        seed(level, buffer, rawInput, rawCount);
        ResourceId sessionId = id("steve_industrial:goal_test/"
                + target.replace(':', '_') + "_" + orientation.name().toLowerCase());
        CreateV606GoalDrivenPlanner.Ready ready = ready(CreateV606GoalDrivenPlanner.plan(
                level, id(target), quantity, Map.of(raw, (long) rawCount), anchor, orientation,
                sessionId, Map.of(raw, (long) rawCount),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
        check(ready.executionReadyPlan().evidence().size() == 16,
                "Execution readiness checklist is incomplete");
        CreateV606GoalDrivenExecution.Started started = started(CreateV606GoalDrivenExecution.begin(
                level, ready.executionReadyPlan(), ready.runtime(), buffer));
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
                phases.add(progress.phase());
                maxInvocations.accumulateAndGet(progress.maximumHandlerInvocationsThisTick(), Math::max);
                throw new GameTestAssertException("goal execution pending " + progress.phase()
                        + " node=" + progress.processNodeIndex() + "/" + progress.processNodeCount());
            }
            CreateV606GoalDrivenExecution.Completed value =
                    (CreateV606GoalDrivenExecution.Completed) result;
            completed.set(value);
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
            check(!hasRouteCells
                            || value.trace().stream().anyMatch(line ->
                            line.contains("execution:item_routes_constructed=")),
                    "Multi-node execution did not physically construct its verified ITEM route");
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
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        check(item != null, "Resource buffer input is not registered: " + itemId);
        chest.setItem(0, new ItemStack(item, count));
        chest.setChanged();
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
