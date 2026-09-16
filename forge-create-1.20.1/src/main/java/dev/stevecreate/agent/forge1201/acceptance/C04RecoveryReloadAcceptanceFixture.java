package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BeltPressEvidence;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionSession;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionUpdate;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.RecoverableExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BeltPressPlacement;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
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
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateBeltPressPlanAdapter;
import dev.stevecreate.agent.forge1201.recovery.ForgeRecoveryWorldScanner;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/** Two-process acceptance for a real C-04 production handler checkpoint and resume. */
public final class C04RecoveryReloadAcceptanceFixture {
    private static final int TIMEOUT_TICKS = 1_800;
    private static final QuarterTurn ROTATION = QuarterTurn.CLOCKWISE_270;
    private static final int MIN_SETTLE_TICKS = 10;
    private static final int REQUIRED_STABLE_OBSERVATIONS = 5;
    private static ActiveFixture active;
    private static int writeBoundaryTick = -1;
    private static int stableObservations;
    private static Map<BlockPos3i, WorldBlockSnapshot> lastBoundaryObservation;

    private C04RecoveryReloadAcceptanceFixture() {
    }

    public static void start(MinecraftServer server, String phase, Logger logger) {
        try {
            check(active == null, "C-04 recovery fixture was started twice");
            check(!FMLLoader.isProduction(), "C-04 recovery fixture is disabled in production");
            check(server.isSameThread(), "C-04 recovery setup is not on the server thread");
            if ("write".equals(phase)) {
                startWrite(server, logger);
            } else if ("read".equals(phase)) {
                startRead(server, logger);
            } else {
                throw new IllegalArgumentException("Unknown C-04 recovery phase: " + phase);
            }
        } catch (RuntimeException exception) {
            logger.error("C04_RECOVERY_RELOAD_ACCEPTANCE FAIL phase={}", phase, exception);
            active = null;
            resetWriteSettlement();
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
            // Vanilla skips entity and block-entity dispatch after 300 empty-world ticks,
            // even in loaded spawn chunks. Keep this dev-only fixture awake; normal world
            // ticking still owns all Create processing (no manual machine ticks).
            server.overworld().resetEmptyTime();
            int elapsed = server.getTickCount() - fixture.startTick();
            check(elapsed <= TIMEOUT_TICKS,
                    "C-04 recovery fixture exceeded " + TIMEOUT_TICKS + " ticks");
            if (fixture.phase().equals("write") && writeBoundaryTick >= 0) {
                tickSettledWriteBoundary(fixture);
                return;
            }
            AdapterResult<BeltPressExecutionUpdate> result = fixture.session().tick();
            check(result instanceof AdapterResult.Success<BeltPressExecutionUpdate>,
                    "C-04 production session failed: " + result);
            BeltPressExecutionUpdate update =
                    ((AdapterResult.Success<BeltPressExecutionUpdate>) result).value();
            if (fixture.phase().equals("write")) {
                tickWrite(fixture, update);
            } else {
                tickRead(fixture, update);
            }
        } catch (RuntimeException exception) {
            fixture.logger().error(
                    "C04_RECOVERY_RELOAD_ACCEPTANCE FAIL phase={}",
                    fixture.phase(),
                    exception);
            active = null;
            resetWriteSettlement();
            server.halt(false);
            throw exception;
        }
    }

    private static void startWrite(MinecraftServer server, Logger logger) {
        resetWriteSettlement();
        ServerLevel level = server.overworld();
        C04RecoveryReloadAcceptanceSavedData savedData = savedData(level);
        check(!savedData.hasCheckpoint(), "C-04 write phase found an existing checkpoint");
        BlockPos origin = fixtureOrigin(level);
        BeltPressPlan plan = BeltPressPlan.at(position(origin), ROTATION);
        AdapterResult<BeltPressExecutionSession> begin =
                new ForgeCreateBeltPressPlanAdapter(level).begin(plan);
        check(begin instanceof AdapterResult.Success<BeltPressExecutionSession>,
                "C-04 write phase could not begin production plan: " + begin);
        active = new ActiveFixture(
                server,
                logger,
                "write",
                server.getTickCount(),
                plan,
                ((AdapterResult.Success<BeltPressExecutionSession>) begin).value(),
                null,
                null);
    }

    private static void tickWrite(
            ActiveFixture fixture,
            BeltPressExecutionUpdate update) {
        check(update instanceof BeltPressExecutionUpdate.InProgress,
                "C-04 write phase completed before its safe recovery boundary");
        BeltPressExecutionUpdate.InProgress progress =
                (BeltPressExecutionUpdate.InProgress) update;
        if (progress.phase() != CreatePlanExecutionPhase.AWAITING_POWER) {
            return;
        }
        check(progress.completedBuildSteps() == fixture.plan().buildSteps().size(),
                "C-04 safe boundary did not complete every build action");
        writeBoundaryTick = fixture.server().getTickCount();
    }

    private static void tickSettledWriteBoundary(ActiveFixture fixture) {
        Map<BlockPos3i, WorldBlockSnapshot> observed = scan(
                fixture.server().overworld(),
                fixture.session().worldChangeJournal().modifiedPositions());
        if (observed.equals(lastBoundaryObservation)) {
            stableObservations++;
        } else {
            lastBoundaryObservation = observed;
            stableObservations = 0;
        }
        int settleTicks = fixture.server().getTickCount() - writeBoundaryTick;
        if (settleTicks < MIN_SETTLE_TICKS
                || stableObservations < REQUIRED_STABLE_OBSERVATIONS) {
            return;
        }
        check(fixture.session() instanceof RecoverableExecutionSession,
                "C-04 production session does not expose the optional recovery contract");
        long savedTick = fixture.server().overworld().getGameTime();
        AdapterResult<RecoveryCheckpoint> captured =
                ((RecoverableExecutionSession) fixture.session()).recoveryCheckpoint(savedTick);
        check(captured instanceof AdapterResult.Success<RecoveryCheckpoint>,
                "C-04 recovery checkpoint was rejected: " + captured);
        RecoveryCheckpoint checkpoint =
                ((AdapterResult.Success<RecoveryCheckpoint>) captured).value();
        verifyBuildOnlyJournal(checkpoint.journal(), fixture.plan());
        byte[] encoded = RecoveryCheckpointCodec.encode(checkpoint);
        savedData(fixture.server().overworld()).checkpoint(encoded, fixture.plan().anchor());
        fixture.logger().info(
                "C04_RECOVERY_RELOAD_WRITE PASS session={} origin={},{},{} rotation={} currentStep={} stepState={} completedSteps={} buildCursor={} blockChanges={} modifiedPositions={} resourceChanges=0 settleTicks={} stableObservations={} checkpointBytes={}",
                checkpoint.session().sessionId(),
                fixture.plan().origin().x(),
                fixture.plan().origin().y(),
                fixture.plan().origin().z(),
                fixture.plan().rotation(),
                checkpoint.session().currentStepId().orElseThrow(),
                checkpoint.session().stepRunState(),
                checkpoint.session().completedStepIds().size(),
                fixture.plan().buildSteps().size(),
                checkpoint.journal().entries().size(),
                checkpoint.journal().modifiedPositions().size(),
                settleTicks,
                stableObservations,
                encoded.length);
        active = null;
        resetWriteSettlement();
        fixture.server().halt(false);
    }

    private static void startRead(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        C04RecoveryReloadAcceptanceSavedData savedData = savedData(level);
        check(savedData.hasCheckpoint(), "C-04 read phase did not find SavedData");
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(savedData.checkpoint());
        BeltPressPlan plan = BeltPressPlan.at(savedData.anchor());
        GenericExecutionPlan trustedPlan = BeltPressGenericExecutionPlan.from(plan);
        SessionRecoveryReconciler.Discovery discovery = SessionRecoveryReconciler.discover(
                checkpoint, Map.of(trustedPlan.planId(), trustedPlan));
        check(discovery instanceof RescanRequired,
                "C-04 checkpoint was not eligible for exact rescan: " + discovery);
        RescanRequired candidate = (RescanRequired) discovery;
        Map<BlockPos3i, WorldBlockSnapshot> raw = scan(level, candidate.requiredPositions());
        SessionRecoveryReconciler.Reconciliation rawInitial = candidate.reconcile(raw);
        // A raw reload must look stale, and it must look stale at a kinetic block: those
        // carry v606 transient state that a restart cannot reproduce, which is the whole
        // reason the normalized recovery scan exists. This named BELT_START specifically
        // until the survival-power topology added water wheels, and the wheel is scanned
        // first — the assertion broke while the property it guards still held.
        check(rawInitial instanceof StaleSession rawStale
                        && rawStale.reason() == StaleSessionReason.WORLD_STATE_CHANGED
                        && isKineticPosition(plan, rawStale.position().orElseThrow()),
                "C-04 raw reload did not expose a v606 transient kinetic state: "
                        + rawInitial);
        ForgeCreateBeltPressPlanAdapter recoveryAdapter =
                new ForgeCreateBeltPressPlanAdapter(level);
        Map<BlockPos3i, WorldBlockSnapshot> exact = recoveryScan(
                recoveryAdapter, plan, candidate.requiredPositions());
        List<BlockPos3i> normalizedPositions = candidate.requiredPositions().stream()
                .filter(position -> !raw.get(position).equals(exact.get(position)))
                .toList();
        long normalizedSnapshots = normalizedPositions.size();
        // What matters is not how many snapshots normalization rewrote but where: it may
        // only touch kinetic blocks. Rewriting a stone or a chest would be normalization
        // hiding a real world change. The old assertion counted six, which was the
        // creative-motor topology's number and said nothing about which blocks.
        check(normalizedSnapshots > 0,
                "C-04 v606 recovery normalization changed nothing, so the raw/normalized"
                        + " distinction this gate rests on was not exercised");
        check(normalizedPositions.stream().allMatch(
                        position -> isKineticPosition(plan, position)),
                "C-04 v606 recovery normalization rewrote a non-kinetic snapshot: "
                        + normalizedPositions.stream()
                                .filter(position -> !isKineticPosition(plan, position))
                                .toList());
        SessionRecoveryReconciler.Reconciliation initial = candidate.reconcile(exact);
        check(initial instanceof Resumable,
                "C-04 unchanged reload was not resumable: " + initial);
        logger.info(
                "C04_RECOVERY_RELOAD_DISCOVERED PASS session={} currentStep={} stepState={} completedSteps={} verifiedPositions={} rawTransientRejected=true normalizedKineticSnapshots={} blindResume=false",
                candidate.sessionId(),
                checkpoint.session().currentStepId().orElseThrow(),
                checkpoint.session().stepRunState(),
                checkpoint.session().completedStepIds().size(),
                candidate.requiredPositions().size(),
                normalizedSnapshots);

        BlockPos3i faultPosition = plan.placement(BeltPressRole.OUTPUT_CHEST).position();
        BlockPos faultBlockPos = blockPos(faultPosition);
        BlockState restoredState = level.getBlockState(faultBlockPos);
        BlockEntity originalEntity = level.getBlockEntity(faultBlockPos);
        check(originalEntity != null, "C-04 stale probe chest has no block entity");
        CompoundTag restoredData = originalEntity.saveWithFullMetadata().copy();
        check(level.setBlockAndUpdate(faultBlockPos, Blocks.DIRT.defaultBlockState()),
                "C-04 stale probe could not inject world drift");
        Map<BlockPos3i, WorldBlockSnapshot> injected = recoveryScan(
                recoveryAdapter, plan, candidate.requiredPositions());
        SessionRecoveryReconciler.Reconciliation staleResult = candidate.reconcile(injected);
        check(staleResult instanceof StaleSession,
                "C-04 world drift did not return typed stale session: " + staleResult);
        StaleSession stale = (StaleSession) staleResult;
        check(stale.failureCode().equals(SessionRecoveryReconciler.STALE_SESSION_FAILURE)
                        && stale.reason() == StaleSessionReason.WORLD_STATE_CHANGED
                        && stale.position().orElseThrow().equals(faultPosition),
                "C-04 world drift returned the wrong typed failure: " + stale);
        check(level.getBlockState(faultBlockPos).is(Blocks.DIRT),
                "C-04 stale reconciliation mutated the fault-injected state");
        logger.info(
                "C04_RECOVERY_RELOAD_STALE PASS code={} reason={} position={} noMutation=true",
                stale.failureCode(), stale.reason(), stale.position().orElseThrow());

        check(level.setBlockAndUpdate(faultBlockPos, restoredState),
                "C-04 stale probe could not restore the chest block");
        BlockEntity restoredEntity = level.getBlockEntity(faultBlockPos);
        check(restoredEntity != null, "C-04 stale probe could not restore the chest entity");
        restoredEntity.load(restoredData);
        restoredEntity.setChanged();
        Map<BlockPos3i, WorldBlockSnapshot> rescanned = recoveryScan(
                recoveryAdapter, plan, candidate.requiredPositions());
        SessionRecoveryReconciler.Reconciliation reconciled = candidate.reconcile(rescanned);
        check(reconciled instanceof Resumable,
                "C-04 exact state did not become resumable after stale probe: " + reconciled);
        Resumable resumable = (Resumable) reconciled;
        AdapterResult<BeltPressExecutionSession> resumed =
                new ForgeCreateBeltPressPlanAdapter(level).resume(plan, resumable);
        check(resumed instanceof AdapterResult.Success<BeltPressExecutionSession>,
                "C-04 production adapter rejected reconciled resume: " + resumed);
        BeltPressExecutionSession session =
                ((AdapterResult.Success<BeltPressExecutionSession>) resumed).value();
        verifyBuildOnlyJournal(session.worldChangeJournal(), plan);
        logger.info(
                "C04_RECOVERY_RELOAD_RESUME_READY PASS session={} buildCursor={} journalEntries={} modifiedPositions={} repeatedPlacements=0 repeatedInputs=0",
                checkpoint.session().sessionId(),
                plan.buildSteps().size(),
                session.worldChangeJournal().entries().size(),
                session.worldChangeJournal().modifiedPositions().size());
        active = new ActiveFixture(
                server,
                logger,
                "read",
                server.getTickCount(),
                plan,
                session,
                session.worldChangeJournal(),
                checkpoint.session().sessionId().toString());
    }

    private static void tickRead(
            ActiveFixture fixture,
            BeltPressExecutionUpdate update) {
        if (update instanceof BeltPressExecutionUpdate.InProgress progress) {
            check(progress.phase() != CreatePlanExecutionPhase.BUILDING,
                    "Recovered C-04 session attempted to repeat BUILD");
            check(progress.completedBuildSteps() == fixture.plan().buildSteps().size(),
                    "Recovered C-04 build cursor moved backwards");
            return;
        }
        BeltPressEvidence evidence = ((BeltPressExecutionUpdate.Completed) update).evidence();
        WorldChangeJournal journal = fixture.session().worldChangeJournal();
        long blockChanges = journal.entries().stream().filter(BlockChange.class::isInstance).count();
        long inputChanges = journal.entries().stream().filter(InjectedResourceChange.class::isInstance).count();
        long processChanges = journal.entries().stream()
                .filter(IrreversibleProcessingChange.class::isInstance).count();
        check(journal.entries().stream().filter(BlockChange.class::isInstance).toList()
                        .equals(fixture.recoveredBuildJournal().entries()),
                "Recovered C-04 repeated, lost or changed its exact BUILD journal");
        check(journal.modifiedPositions().equals(fixture.recoveredBuildJournal().modifiedPositions()),
                "Recovered C-04 changed its owned BUILD position set");
        check(inputChanges == 1, "Recovered C-04 did not record exactly one input injection");
        check(processChanges == 1, "Recovered C-04 did not record exactly one real process");
        check(journal.entries().size() == fixture.recoveredBuildJournal().entries().size() + 2,
                "Recovered C-04 journal contains unexpected changes");
        check(evidence.verifiedPlacements().equals(fixture.plan().finalPlacements()),
                "Recovered C-04 physical placement verification changed");
        check(evidence.recipeId().equals(fixture.plan().process().recipeId())
                        && evidence.consumedInputCount() == 1
                        && evidence.observedOutputCount() == 1
                        && evidence.pressCycleTicks() == 240
                        && evidence.inputObservedOnBelt()
                        && evidence.pressCycleObserved()
                        && evidence.outputObservedInChest(),
                "Recovered C-04 recipe/input/press/output evidence is not one real cycle: " + evidence);
        fixture.logger().info(
                "C04_RECOVERY_BUILD_JOURNAL PASS exactEntries={} exactPositions={} unchanged=true readTicks={} naturalWorldTicks=true",
                blockChanges, journal.modifiedPositions().size(),
                fixture.server().getTickCount() - fixture.startTick());
        fixture.logger().info(
                "C04_RECOVERY_RELOAD_PROCESS PASS session={} recipe={} input={} consumed={} output={} observed={} pressCycleTicks={} beltInput=true pressCycle=true chestOutput=true blockChanges={} inputChanges={} processChanges={} repeatedPlacements=0 repeatedInputs=0 duplicatedOutputs=0",
                fixture.sessionId(),
                evidence.recipeId(),
                evidence.inputItem(),
                evidence.consumedInputCount(),
                evidence.outputItem(),
                evidence.observedOutputCount(),
                evidence.pressCycleTicks(),
                blockChanges,
                inputChanges,
                processChanges);
        fixture.logger().info(
                "C04_RECOVERY_RELOAD_ACCEPTANCE PASS session={} savedData=true worldReloaded=true exactRescan=true staleRejected=true handlerCursorRecovered=true stepCursorRecovered=true realRecipe=true inputConsumed=true pressCycleObserved=true outputVerified=true noDuplicatePlacement=true noDuplicateInput=true noDuplicateOutput=true",
                fixture.sessionId());
        active = null;
        fixture.server().halt(false);
    }

    private static void verifyBuildOnlyJournal(
            WorldChangeJournal journal,
            BeltPressPlan plan) {
        check(journal.entries().stream().allMatch(BlockChange.class::isInstance),
                "C-04 safe boundary contains resource history");
        check(new HashSet<>(journal.modifiedPositions()).equals(expectedBuildPositions(plan)),
                "C-04 safe boundary touched positions the plan does not own");
    }

    /** Whether a position holds a plan block whose v606 state is transient across a restart. */
    private static boolean isKineticPosition(BeltPressPlan plan, BlockPos3i position) {
        return plan.finalPlacements().stream()
                .anyMatch(placement -> placement.position().equals(position)
                        && placement.role().isKinetic());
    }

    /**
     * Every position the BUILD prefix is allowed to have touched.
     *
     * <p>The plan's own placements, plus one pilot flow cell directly below each water
     * source: the cells {@code Create606BeltPressActionHandler.placePilotFlowCell} writes
     * and journals without billing them as placements.
     *
     * <p>This replaces two counted assertions, {@code entries().size() == 10} and
     * {@code modifiedPositions().size() == finalPlacements().size()}. Both held the
     * creative-motor topology's numbers; the survival-power promotion moved the real
     * values to 30 entries and 28 positions against 26 placements, and neither count
     * followed. Comparing the position set is strictly stronger than comparing sizes,
     * and it needs no editing the next time the topology changes. The entry count itself
     * is deliberately not asserted: a planned position is journalled more than once
     * during a normal build, which was already true of the old topology (10 = 8 + 2),
     * so the number describes the build sequence rather than any safety property. What
     * matters is that nothing outside this set was touched, and that no resource history
     * appears at a BUILD boundary; both are asserted above.</p>
     */
    private static Set<BlockPos3i> expectedBuildPositions(BeltPressPlan plan) {
        return new HashSet<>(plan.ownedPositions());
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> scan(
            ServerLevel level,
            List<BlockPos3i> positions) {
        AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> result =
                ForgeRecoveryWorldScanner.scan(level, positions);
        check(result instanceof AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>,
                "C-04 bounded recovery scan failed: " + result);
        return ((AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>) result).value();
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> recoveryScan(
            ForgeCreateBeltPressPlanAdapter adapter,
            BeltPressPlan plan,
            List<BlockPos3i> positions) {
        AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> result =
                adapter.scanRecoveryState(plan, positions);
        check(result instanceof AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>,
                "C-04 normalized recovery scan failed: " + result);
        return ((AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>) result).value();
    }

    private static C04RecoveryReloadAcceptanceSavedData savedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                C04RecoveryReloadAcceptanceSavedData::load,
                C04RecoveryReloadAcceptanceSavedData::new,
                C04RecoveryReloadAcceptanceSavedData.DATA_NAME);
    }

    private static BlockPos fixtureOrigin(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 4;
        int z = spawn.getZ() + 4;
        BeltPressPlan horizontal = BeltPressPlan.at(new BlockPos3i(x, 0, z), ROTATION);
        int y = level.getMinBuildHeight() + 1;
        for (BlockPos3i position : horizontal.preflightPositions()) {
            y = Math.max(
                    y,
                    level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING,
                            position.x(),
                            position.z()) + 2);
        }
        check(y + 4 < level.getMaxBuildHeight(), "C-04 recovery fixture is above build height");
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

    private static void resetWriteSettlement() {
        writeBoundaryTick = -1;
        stableObservations = 0;
        lastBoundaryObservation = null;
    }

    private record ActiveFixture(
            MinecraftServer server,
            Logger logger,
            String phase,
            int startTick,
            BeltPressPlan plan,
            BeltPressExecutionSession session,
            WorldChangeJournal recoveredBuildJournal,
            String sessionId) {
    }
}
