package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionSession;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionUpdate;
import dev.stevecreate.agent.adapter.api.RecoverableExecutionSession;
import dev.stevecreate.agent.adapter.api.WaterWheelMillstoneEvidence;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.RescanRequired;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.StaleSession;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.StaleSessionReason;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.IrreversibleProcessingChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreatePlanAdapter;
import dev.stevecreate.agent.forge1201.recovery.ForgeRecoveryWorldScanner;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/** Two-process acceptance for a real C-03 production handler checkpoint and resume. */
public final class C03RecoveryReloadAcceptanceFixture {
    private static final int TIMEOUT_TICKS = 1_800;
    private static final QuarterTurn ROTATION = QuarterTurn.CLOCKWISE_90;
    private static ActiveFixture active;

    private C03RecoveryReloadAcceptanceFixture() {
    }

    public static void start(MinecraftServer server, String phase, Logger logger) {
        try {
            check(active == null, "C-03 recovery fixture was started twice");
            check(!FMLLoader.isProduction(), "C-03 recovery fixture is disabled in production");
            check(server.isSameThread(), "C-03 recovery setup is not on the server thread");
            if ("write".equals(phase)) {
                startWrite(server, logger);
            } else if ("read".equals(phase)) {
                startRead(server, logger);
            } else {
                throw new IllegalArgumentException("Unknown C-03 recovery phase: " + phase);
            }
        } catch (RuntimeException exception) {
            logger.error("C03_RECOVERY_RELOAD_ACCEPTANCE FAIL phase={}", phase, exception);
            active = null;
            server.halt(false);
            throw exception;
        }
    }

    public static void tick(MinecraftServer server) {
        ActiveFixture fixture = active;
        if (fixture == null || fixture.server() != server) {
            return;
        }
        try {
            int elapsed = server.getTickCount() - fixture.startTick();
            check(elapsed <= TIMEOUT_TICKS,
                    "C-03 recovery fixture exceeded " + TIMEOUT_TICKS + " ticks");
            AdapterResult<CreatePlanExecutionUpdate> result = fixture.session().tick();
            check(result instanceof AdapterResult.Success<CreatePlanExecutionUpdate>,
                    "C-03 production session failed: " + result);
            CreatePlanExecutionUpdate update =
                    ((AdapterResult.Success<CreatePlanExecutionUpdate>) result).value();
            if (fixture.phase().equals("write")) {
                tickWrite(fixture, update);
            } else {
                tickRead(fixture, update);
            }
        } catch (RuntimeException exception) {
            fixture.logger().error(
                    "C03_RECOVERY_RELOAD_ACCEPTANCE FAIL phase={}",
                    fixture.phase(),
                    exception);
            active = null;
            server.halt(false);
            throw exception;
        }
    }

    private static void startWrite(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        C03RecoveryReloadAcceptanceSavedData savedData = savedData(level);
        check(!savedData.hasCheckpoint(), "C-03 write phase found an existing checkpoint");
        BlockPos origin = fixtureOrigin(level);
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(
                position(origin), ROTATION);
        AdapterResult<CreatePlanExecutionSession> begin =
                new ForgeCreatePlanAdapter(level).begin(plan);
        check(begin instanceof AdapterResult.Success<CreatePlanExecutionSession>,
                "C-03 write phase could not begin production plan: " + begin);
        active = new ActiveFixture(
                server,
                logger,
                "write",
                server.getTickCount(),
                plan,
                ((AdapterResult.Success<CreatePlanExecutionSession>) begin).value(),
                null);
    }

    private static void tickWrite(
            ActiveFixture fixture,
            CreatePlanExecutionUpdate update) {
        check(update instanceof CreatePlanExecutionUpdate.InProgress,
                "C-03 write phase completed before its safe recovery boundary");
        CreatePlanExecutionUpdate.InProgress progress =
                (CreatePlanExecutionUpdate.InProgress) update;
        if (progress.phase() != CreatePlanExecutionPhase.AWAITING_POWER) {
            return;
        }
        check(progress.completedPlacements() == fixture.plan().placements().size(),
                "C-03 safe boundary did not complete every placement");
        check(fixture.session() instanceof RecoverableExecutionSession,
                "C-03 production session does not expose the optional recovery contract");
        long savedTick = fixture.server().overworld().getGameTime();
        AdapterResult<RecoveryCheckpoint> captured =
                ((RecoverableExecutionSession) fixture.session()).recoveryCheckpoint(savedTick);
        check(captured instanceof AdapterResult.Success<RecoveryCheckpoint>,
                "C-03 recovery checkpoint was rejected: " + captured);
        RecoveryCheckpoint checkpoint =
                ((AdapterResult.Success<RecoveryCheckpoint>) captured).value();
        verifyBuildOnlyJournal(checkpoint.journal(), fixture.plan());
        byte[] encoded = RecoveryCheckpointCodec.encode(checkpoint);
        savedData(fixture.server().overworld()).checkpoint(encoded, fixture.plan().anchor());
        fixture.logger().info(
                "C03_RECOVERY_RELOAD_WRITE PASS session={} origin={},{},{} rotation={} currentStep={} stepState={} completedSteps={} placements={} blockChanges={} resourceChanges=0 checkpointBytes={}",
                checkpoint.session().sessionId(),
                fixture.plan().origin().x(),
                fixture.plan().origin().y(),
                fixture.plan().origin().z(),
                fixture.plan().rotation(),
                checkpoint.session().currentStepId().orElseThrow(),
                checkpoint.session().stepRunState(),
                checkpoint.session().completedStepIds().size(),
                fixture.plan().placements().size(),
                checkpoint.journal().entries().size(),
                encoded.length);
        active = null;
        fixture.server().halt(false);
    }

    private static void startRead(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        C03RecoveryReloadAcceptanceSavedData savedData = savedData(level);
        check(savedData.hasCheckpoint(), "C-03 read phase did not find SavedData");
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(savedData.checkpoint());
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(savedData.anchor());
        GenericExecutionPlan trustedPlan = WaterWheelMillstoneGenericExecutionPlan.from(plan);
        SessionRecoveryReconciler.Discovery discovery = SessionRecoveryReconciler.discover(
                checkpoint, Map.of(trustedPlan.planId(), trustedPlan));
        check(discovery instanceof RescanRequired,
                "C-03 checkpoint was not eligible for exact rescan: " + discovery);
        RescanRequired candidate = (RescanRequired) discovery;
        Map<BlockPos3i, WorldBlockSnapshot> raw = scan(level, candidate.requiredPositions());
        SessionRecoveryReconciler.Reconciliation rawInitial = candidate.reconcile(raw);
        check(rawInitial instanceof StaleSession rawStale
                        && rawStale.reason() == StaleSessionReason.WORLD_STATE_CHANGED,
                "C-03 raw reload did not expose the expected v606 transient kinetic state: "
                        + rawInitial);
        Map<BlockPos3i, WorldBlockSnapshot> exact = scanRecoveryState(
                level, plan, candidate.requiredPositions());
        long normalizedSnapshots = candidate.requiredPositions().stream()
                .filter(position -> !raw.get(position).equals(exact.get(position)))
                .count();
        check(normalizedSnapshots == 4,
                "C-03 normalized an unexpected number of kinetic snapshots: "
                        + normalizedSnapshots);
        SessionRecoveryReconciler.Reconciliation initial = candidate.reconcile(exact);
        check(initial instanceof Resumable,
                "C-03 unchanged reload was not resumable: " + initial);
        logger.info(
                "C03_RECOVERY_RELOAD_DISCOVERED PASS session={} currentStep={} stepState={} completedSteps={} verifiedPositions={} rawTransientRejected=true normalizedKineticSnapshots={} blindResume=false",
                candidate.sessionId(),
                checkpoint.session().currentStepId().orElseThrow(),
                checkpoint.session().stepRunState(),
                checkpoint.session().completedStepIds().size(),
                candidate.requiredPositions().size(),
                normalizedSnapshots);

        BlockPos3i faultPosition = plan.placements().get(0).position();
        BlockPos faultBlockPos = blockPos(faultPosition);
        BlockState restoredState = level.getBlockState(faultBlockPos);
        check(restoredState.is(Blocks.STONE), "C-03 stale probe target is not plan stone");
        check(level.setBlockAndUpdate(faultBlockPos, Blocks.DIRT.defaultBlockState()),
                "C-03 stale probe could not inject world drift");
        WorldBlockSnapshot injected = scan(level, List.of(faultPosition)).get(faultPosition);
        SessionRecoveryReconciler.Reconciliation staleResult = candidate.reconcile(
                Map.of(faultPosition, injected));
        check(staleResult instanceof StaleSession,
                "C-03 world drift did not return typed stale session: " + staleResult);
        StaleSession stale = (StaleSession) staleResult;
        check(stale.failureCode().equals(SessionRecoveryReconciler.STALE_SESSION_FAILURE)
                        && stale.reason() == StaleSessionReason.WORLD_STATE_CHANGED,
                "C-03 world drift returned the wrong typed failure: " + stale);
        check(level.getBlockState(faultBlockPos).is(Blocks.DIRT),
                "C-03 stale reconciliation mutated the fault-injected state");
        logger.info(
                "C03_RECOVERY_RELOAD_STALE PASS code={} reason={} position={} noMutation=true",
                stale.failureCode(), stale.reason(), stale.position().orElseThrow());

        check(level.setBlockAndUpdate(faultBlockPos, restoredState),
                "C-03 stale probe could not restore the exact plan block");
        Map<BlockPos3i, WorldBlockSnapshot> rescanned = scanRecoveryState(
                level, plan, candidate.requiredPositions());
        SessionRecoveryReconciler.Reconciliation reconciled = candidate.reconcile(rescanned);
        check(reconciled instanceof Resumable,
                "C-03 exact state did not become resumable after stale probe: " + reconciled);
        Resumable resumable = (Resumable) reconciled;
        AdapterResult<CreatePlanExecutionSession> resumed =
                new ForgeCreatePlanAdapter(level).resume(plan, resumable);
        check(resumed instanceof AdapterResult.Success<CreatePlanExecutionSession>,
                "C-03 production adapter rejected reconciled resume: " + resumed);
        CreatePlanExecutionSession session =
                ((AdapterResult.Success<CreatePlanExecutionSession>) resumed).value();
        verifyBuildOnlyJournal(session.worldChangeJournal(), plan);
        logger.info(
                "C03_RECOVERY_RELOAD_RESUME_READY PASS session={} placementCursor={} journalEntries={} repeatedPlacements=0 repeatedInputs=0",
                checkpoint.session().sessionId(),
                plan.placements().size(),
                session.worldChangeJournal().entries().size());
        active = new ActiveFixture(
                server,
                logger,
                "read",
                server.getTickCount(),
                plan,
                session,
                checkpoint.session().sessionId().toString());
    }

    private static void tickRead(
            ActiveFixture fixture,
            CreatePlanExecutionUpdate update) {
        if (update instanceof CreatePlanExecutionUpdate.InProgress progress) {
            check(progress.phase() != CreatePlanExecutionPhase.BUILDING,
                    "Recovered C-03 session attempted to repeat BUILD");
            check(progress.completedPlacements() == fixture.plan().placements().size(),
                    "Recovered C-03 placement cursor moved backwards");
            return;
        }
        WaterWheelMillstoneEvidence evidence =
                ((CreatePlanExecutionUpdate.Completed) update).evidence();
        WorldChangeJournal journal = fixture.session().worldChangeJournal();
        long blockChanges = journal.entries().stream().filter(BlockChange.class::isInstance).count();
        long inputChanges = journal.entries().stream().filter(InjectedResourceChange.class::isInstance).count();
        long processChanges = journal.entries().stream()
                .filter(IrreversibleProcessingChange.class::isInstance).count();
        check(blockChanges == fixture.plan().placements().size(),
                "Recovered C-03 repeated or lost placement records");
        check(inputChanges == 1, "Recovered C-03 did not record exactly one input injection");
        check(processChanges == 1, "Recovered C-03 did not record exactly one real process");
        check(journal.entries().size() == fixture.plan().placements().size() + 2,
                "Recovered C-03 journal contains unexpected changes");
        check(evidence.verifiedPlacements().equals(fixture.plan().placements()),
                "Recovered C-03 physical placement verification changed");
        check(evidence.recipeId().equals(fixture.plan().process().recipeId())
                        && evidence.consumedInputCount() == 1
                        && evidence.observedOutputCount() == 1,
                "Recovered C-03 recipe/input/output evidence is not exactly one real cycle: " + evidence);
        fixture.logger().info(
                "C03_RECOVERY_RELOAD_PROCESS PASS session={} recipe={} input={} consumed={} output={} observed={} blockChanges={} inputChanges={} processChanges={} repeatedPlacements=0 repeatedInputs=0 duplicatedOutputs=0",
                fixture.sessionId(),
                evidence.recipeId(),
                evidence.inputItem(),
                evidence.consumedInputCount(),
                evidence.outputItem(),
                evidence.observedOutputCount(),
                blockChanges,
                inputChanges,
                processChanges);
        fixture.logger().info(
                "C03_RECOVERY_RELOAD_ACCEPTANCE PASS session={} savedData=true worldReloaded=true exactRescan=true staleRejected=true handlerCursorRecovered=true stepCursorRecovered=true realRecipe=true inputConsumed=true outputVerified=true noDuplicatePlacement=true noDuplicateInput=true noDuplicateOutput=true",
                fixture.sessionId());
        active = null;
        fixture.server().halt(false);
    }

    private static void verifyBuildOnlyJournal(
            WorldChangeJournal journal,
            WaterWheelMillstonePlan plan) {
        check(journal.entries().size() == plan.placements().size(),
                "C-03 safe boundary journal size changed");
        check(journal.entries().stream().allMatch(BlockChange.class::isInstance),
                "C-03 safe boundary contains resource history");
        check(journal.modifiedPositions().equals(
                        plan.placements().stream().map(value -> value.position()).toList()),
                "C-03 safe boundary positions changed");
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> scan(
            ServerLevel level,
            List<BlockPos3i> positions) {
        AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> result =
                ForgeRecoveryWorldScanner.scan(level, positions);
        check(result instanceof AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>,
                "C-03 bounded recovery scan failed: " + result);
        return ((AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>) result).value();
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> scanRecoveryState(
            ServerLevel level,
            WaterWheelMillstonePlan plan,
            List<BlockPos3i> positions) {
        AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> result =
                new ForgeCreatePlanAdapter(level).scanRecoveryState(plan, positions);
        check(result instanceof AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>,
                "C-03 normalized recovery scan failed: " + result);
        return ((AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>) result).value();
    }

    private static C03RecoveryReloadAcceptanceSavedData savedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                C03RecoveryReloadAcceptanceSavedData::load,
                C03RecoveryReloadAcceptanceSavedData::new,
                C03RecoveryReloadAcceptanceSavedData.DATA_NAME);
    }

    private static BlockPos fixtureOrigin(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 4;
        int z = spawn.getZ() + 4;
        WaterWheelMillstonePlan horizontal = WaterWheelMillstonePlan.at(
                new BlockPos3i(x, 0, z), ROTATION);
        int y = level.getMinBuildHeight() + 1;
        for (BlockPos3i position : horizontal.preflightPositions()) {
            y = Math.max(
                    y,
                    level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING,
                            position.x(),
                            position.z()) + 2);
        }
        check(y + 6 < level.getMaxBuildHeight(), "C-03 recovery fixture is above build height");
        return new BlockPos(x, y, z);
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static BlockPos blockPos(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private record ActiveFixture(
            MinecraftServer server,
            Logger logger,
            String phase,
            int startTick,
            WaterWheelMillstonePlan plan,
            CreatePlanExecutionSession session,
            String sessionId) {
    }
}
