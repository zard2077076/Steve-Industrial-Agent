package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.ExecutionCancellationResult;
import dev.stevecreate.agent.adapter.api.MechanicalSawExecutionSession;
import dev.stevecreate.agent.adapter.api.MechanicalSawExecutionUpdate;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.BoundedStepRunner;
import dev.stevecreate.agent.core.execution.ConditionEvaluation;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.GenericStepRunState;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.execution.StepRunResult;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.MechanicalSawGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawRole;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryPlanSnapshot;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

/** Exact Create 6.0.6 C-07 facade driven by the shared runner and typed evidence rules. */
public final class Create606MechanicalSawExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private Create606MechanicalSawExecutor() {}

    public static AdapterResult<MechanicalSawExecutionSession> begin(
            ServerLevel level,
            MechanicalSawPlan plan,
            RuntimeFingerprint runtime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Optional<Create606MechanicalSawActionHandler.FailureDetail> invalid =
                Create606MechanicalSawActionHandler.validatePlan(level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(level, plan, runtime));
    }

    static AdapterResult<MechanicalSawExecutionSession> beginGoalDriven(
            ServerLevel level,
            MechanicalSawPlan plan,
            RuntimeFingerprint runtime,
            ResourceId verifiedSessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(verifiedSessionId, "verifiedSessionId");
        Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        Optional<Create606MechanicalSawActionHandler.FailureDetail> invalid =
                Create606MechanicalSawActionHandler.validatePlan(level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, verifiedSessionId, resourceBuffer));
    }

    public static AdapterResult<MechanicalSawExecutionSession> resume(
            ServerLevel level,
            MechanicalSawPlan plan,
            RuntimeFingerprint runtime,
            Resumable resumable) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(resumable, "resumable");
        String invalid = validateRecoveryBoundary(
                plan, resumable.session(), resumable.journal());
        if (invalid != null) {
            return failure(AdapterFailureCode.PROCESSING_FAILED, invalid);
        }
        if (!resumable.verifiedPositions().equals(resumable.journal().modifiedPositions())) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Recovered C-07 positions were not all reconciled");
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, resumable.session(), resumable.journal()));
    }

    static AdapterResult<MechanicalSawExecutionSession> resumeGoalDriven(
            ServerLevel level,
            MechanicalSawPlan plan,
            RuntimeFingerprint runtime,
            Resumable resumable,
            Create606WorldResourceBuffer resourceBuffer) {
        Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        String invalid = validateRecoveryBoundary(
                plan, resumable.session(), resumable.journal());
        if (invalid != null
                || !resumable.verifiedPositions().equals(
                        resumable.journal().modifiedPositions())) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    invalid == null
                            ? "Recovered C-07 positions were not all reconciled"
                            : invalid);
        }
        return new AdapterResult.Success<>(new Session(
                level,
                plan,
                runtime,
                resumable.session(),
                resumable.journal(),
                resourceBuffer));
    }

    private static final class Session implements MechanicalSawExecutionSession {
        private final ServerLevel level;
        private final MechanicalSawPlan physicalPlan;
        private final Create606MechanicalSawActionHandler actionHandler;
        private final BoundedStepRunner runner;
        private GenericExecutionSession genericSession;

        private Session(
                ServerLevel level,
                MechanicalSawPlan physicalPlan,
                RuntimeFingerprint runtime) {
            this(level, physicalPlan, runtime, sessionId(level, physicalPlan), null);
        }

        private Session(
                ServerLevel level,
                MechanicalSawPlan physicalPlan,
                RuntimeFingerprint runtime,
                ResourceId sessionId,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = level;
            this.physicalPlan = physicalPlan;
            GenericExecutionPlan genericPlan =
                    MechanicalSawGenericExecutionPlan.from(physicalPlan);
            this.actionHandler = resourceBuffer == null
                    ? new Create606MechanicalSawActionHandler(
                            level, physicalPlan, runtime, sessionId)
                    : new Create606MechanicalSawActionHandler(
                            level, physicalPlan, runtime, sessionId, resourceBuffer);
            this.runner = runner(physicalPlan, actionHandler);
            this.genericSession = GenericExecutionSession.start(
                    sessionId, genericPlan, executionTick(level));
            LOGGER.info(
                    "CREATE_MECHANICAL_SAW_GENERIC_EXECUTION plan={} graph={} nodes={} steps={} handler={} worldCutting=forbidden",
                    genericPlan.planId(),
                    genericPlan.machineGraph().id(),
                    genericPlan.machineGraph().nodes().size(),
                    genericPlan.steps().size(),
                    MechanicalSawGenericExecutionPlan.ACTION_HANDLER_ID);
        }

        private Session(
                ServerLevel level,
                MechanicalSawPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal) {
            this(level, physicalPlan, runtime, recoveredSession, recoveredJournal, null);
        }

        private Session(
                ServerLevel level,
                MechanicalSawPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = level;
            this.physicalPlan = physicalPlan;
            this.genericSession = recoveredSession;
            this.actionHandler = new Create606MechanicalSawActionHandler(
                    level,
                    physicalPlan,
                    runtime,
                    recoveredSession.sessionId(),
                    recoveredJournal,
                    recoveredPlacementCursor(physicalPlan, recoveredJournal),
                    recoveredPilotFlowCursor(physicalPlan, recoveredJournal),
                    resourceBuffer);
            this.runner = runner(physicalPlan, actionHandler);
            LOGGER.info(
                    "CREATE_MECHANICAL_SAW_RECOVERED session={} currentStep={} completedSteps={} placementCursor={} journalEntries={} resourceEntries=0",
                    recoveredSession.sessionId(),
                    recoveredSession.currentStep().orElseThrow().stepId(),
                    recoveredSession.completedStepIds().size(),
                    recoveredPlacementCursor(physicalPlan, recoveredJournal),
                    recoveredJournal.entries().size());
        }

        private BoundedStepRunner runner(
                MechanicalSawPlan plan,
                Create606MechanicalSawActionHandler handler) {
            return new BoundedStepRunner(
                    Map.of(MechanicalSawGenericExecutionPlan.ACTION_HANDLER_ID, handler),
                    Map.of(
                            MechanicalSawGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                            this::evaluatePreflightCondition),
                    MechanicalSawGenericExecutionPlan.verificationRules(plan));
        }

        @Override
        public AdapterResult<RecoveryCheckpoint> recoveryCheckpoint(long savedTick) {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "Mechanical-saw recovery checkpoint must be captured on the authoritative server thread");
            }
            try {
                actionHandler.stabilizeRecoveryAfterStates();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-07 recovery state stabilization was rejected: "
                                + exception.getMessage());
            }
            String invalid = validateRecoveryBoundary(
                    physicalPlan, genericSession, actionHandler.worldChangeJournal());
            if (invalid != null) {
                return failure(AdapterFailureCode.PROCESSING_FAILED, invalid);
            }
            try {
                return new AdapterResult.Success<>(RecoveryCheckpoint.capture(
                        genericSession, actionHandler.worldChangeJournal(), savedTick));
            } catch (IllegalArgumentException exception) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-07 recovery checkpoint was rejected: " + exception.getMessage());
            }
        }

        @Override
        public AdapterResult<MechanicalSawExecutionUpdate> tick() {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "Mechanical-saw execution tick must run on the authoritative server thread");
            }
            if (genericSession.status() == GenericExecutionSessionStatus.COMPLETED) {
                return completed();
            }
            if (genericSession.status().terminal()) {
                return terminalFailure();
            }
            Optional<ChunkPos> unloaded = actionHandler.firstUnloadedChunk();
            if (unloaded.isPresent()) {
                ChunkPos chunk = unloaded.orElseThrow();
                return failure(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "Mechanical-saw preflight area unloaded during execution: "
                                + chunk.x + "," + chunk.z);
            }
            StepRunResult result = runner.tick(genericSession, executionTick(level));
            genericSession = result.session();
            if (genericSession.status() == GenericExecutionSessionStatus.COMPLETED) {
                return completed();
            }
            if (genericSession.status().terminal()) {
                return terminalFailure();
            }
            return progress();
        }

        @Override
        public AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason) {
            Objects.requireNonNull(reason, "reason");
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "Mechanical-saw cancellation must run on the authoritative server thread");
            }
            if (genericSession.status() != GenericExecutionSessionStatus.RUNNING) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Mechanical-saw session is already terminal: " + genericSession.status());
            }
            StepRunResult result = runner.tick(
                    genericSession, executionTick(level), Optional.of(reason));
            genericSession = result.session();
            if (genericSession.status() != GenericExecutionSessionStatus.CANCELLED) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Generic runner rejected C-07 cancellation: " + result.outcome());
            }
            WorldChangeJournal.RollbackReport rollback = actionHandler.rollback();
            WorldChangeJournal journal = actionHandler.worldChangeJournal();
            LOGGER.info(
                    "CREATE_MECHANICAL_SAW_CANCELLED session={} changes={} modifiedPositions={} restoredPositions={} warnings={} noResourceCompensation=true",
                    genericSession.sessionId(),
                    journal.entries().size(),
                    journal.modifiedPositions().size(),
                    rollback.restoredPositions().size(),
                    rollback.warnings().size());
            return new AdapterResult.Success<>(new ExecutionCancellationResult(
                    genericSession.sessionId(), reason, journal, rollback));
        }

        @Override
        public WorldChangeJournal worldChangeJournal() {
            return actionHandler.worldChangeJournal();
        }

        private ConditionEvaluation evaluatePreflightCondition(
                StepCondition condition,
                dev.stevecreate.agent.core.execution.StepRunnerContext context) {
            if (!condition.parameters().isEmpty()) {
                return ConditionEvaluation.error(
                        failureId(AdapterFailureCode.PLAN_REJECTED),
                        "C-07 preflight condition parameters must be empty");
            }
            boolean unloaded = actionHandler.firstUnloadedChunk().isPresent();
            if (condition.conditionType().equals(
                    MechanicalSawGenericExecutionPlan.PREFLIGHT_LOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.error(
                                failureId(AdapterFailureCode.CHUNK_NOT_LOADED),
                                "C-07 preflight area is no longer loaded")
                        : ConditionEvaluation.satisfied();
            }
            if (condition.conditionType().equals(
                    MechanicalSawGenericExecutionPlan.PREFLIGHT_UNLOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.satisfied()
                        : ConditionEvaluation.unsatisfied();
            }
            return ConditionEvaluation.error(
                    failureId(AdapterFailureCode.PLAN_REJECTED),
                    "Unknown C-07 preflight condition type " + condition.conditionType());
        }

        private AdapterResult<MechanicalSawExecutionUpdate> completed() {
            return actionHandler.completedEvidence()
                    .<AdapterResult<MechanicalSawExecutionUpdate>>map(value ->
                            new AdapterResult.Success<>(
                                    new MechanicalSawExecutionUpdate.Completed(value)))
                    .orElseGet(() -> failure(
                            AdapterFailureCode.CREATE_API_FAILURE,
                            "Generic session completed without physical mechanical-saw evidence"));
        }

        private AdapterResult<MechanicalSawExecutionUpdate> terminalFailure() {
            Optional<Create606MechanicalSawActionHandler.FailureDetail> handlerFailure =
                    actionHandler.lastFailure();
            if (handlerFailure.isPresent()) {
                var value = handlerFailure.orElseThrow();
                return new AdapterResult.Failure<>(value.code(), value.detail());
            }
            AdapterFailureCode code = genericSession.status()
                    == GenericExecutionSessionStatus.TIMED_OUT
                    ? AdapterFailureCode.EXECUTION_TIMEOUT
                    : AdapterFailureCode.PROCESSING_FAILED;
            String detail = genericSession.failure()
                    .map(value -> value.failureCode() + ": " + value.detail())
                    .orElse("Generic C-07 session ended without typed failure detail");
            return failure(code, detail);
        }

        private AdapterResult<MechanicalSawExecutionUpdate> progress() {
            GenericExecutionPhase phase = genericSession.currentPhase().orElseThrow();
            return new AdapterResult.Success<>(new MechanicalSawExecutionUpdate.InProgress(
                    legacyPhase(phase),
                    actionHandler.placementIndex(),
                    physicalPlan.placements().size(),
                    executionTick(level)));
        }
    }

    private static String validateRecoveryBoundary(
            MechanicalSawPlan plan,
            GenericExecutionSession session,
            WorldChangeJournal journal) {
        GenericExecutionPlan trustedPlan = MechanicalSawGenericExecutionPlan.from(plan);
        if (!RecoveryPlanSnapshot.capture(session.plan()).equals(
                RecoveryPlanSnapshot.capture(trustedPlan))) {
            return "Recovered C-07 generic plan does not match the trusted physical plan";
        }
        if (session.status() != GenericExecutionSessionStatus.RUNNING
                || session.currentStep().isEmpty()) {
            return "C-07 recovery requires one running BUILD or pre-resource POWER step";
        }
        ResourceId currentStep = session.currentStep().orElseThrow().stepId();
        boolean partialBuild =
                currentStep.equals(MechanicalSawGenericExecutionPlan.BUILD_STEP_ID)
                        && session.completedStepIds().isEmpty();
        boolean buildComplete =
                currentStep.equals(MechanicalSawGenericExecutionPlan.POWER_STEP_ID)
                        && session.stepRunState() == GenericStepRunState.READY
                        && session.completedStepIds().equals(
                                List.of(MechanicalSawGenericExecutionPlan.BUILD_STEP_ID));
        if (!partialBuild && !buildComplete) {
            return "C-07 can be recovered only at an atomic BUILD boundary or BUILD-complete POWER/READY";
        }
        if (!session.sessionId().equals(journal.sessionId())
                || !session.worldChanges().equals(journal.references())) {
            return "Recovered C-07 session and world-change journal differ";
        }
        List<BlockPos3i> expected = recoveryBuildPositions(plan);
        if (journal.entries().size() > expected.size()
                || journal.entries().stream().anyMatch(entry -> !(entry instanceof BlockChange))) {
            return "C-07 recovery boundary must contain a bounded block-only build prefix";
        }
        List<BlockPos3i> actual = journal.entries().stream()
                .map(BlockChange.class::cast)
                .map(BlockChange::position)
                .toList();
        if (!actual.equals(expected.subList(0, actual.size()))) {
            return "Recovered C-07 block-change sequence does not match the physical build prefix";
        }
        if (!journal.modifiedPositions().equals(List.copyOf(new LinkedHashSet<>(actual)))) {
            return "Recovered C-07 modified positions do not match the physical build prefix";
        }
        if (buildComplete && actual.size() < plan.placements().size()) {
            return "Recovered C-07 BUILD-complete journal omits physical placements";
        }
        for (WorldChangeJournal.Entry entry : journal.entries()) {
            BlockChange block = (BlockChange) entry;
            if (!block.sourceStepId().equals(MechanicalSawGenericExecutionPlan.BUILD_STEP_ID)) {
                return "Recovered C-07 block journal contains a non-BUILD change";
            }
        }
        return null;
    }

    private static int recoveredPlacementCursor(
            MechanicalSawPlan plan,
            WorldChangeJournal journal) {
        return recoveredPlacementCursor(plan, journal.entries().size());
    }

    static int recoveredPlacementCursor(MechanicalSawPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.min(journalEntries, plan.placements().size());
    }

    private static int recoveredPilotFlowCursor(
            MechanicalSawPlan plan,
            WorldChangeJournal journal) {
        return recoveredPilotFlowCursor(plan, journal.entries().size());
    }

    static int recoveredPilotFlowCursor(MechanicalSawPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.max(0, journalEntries - plan.placements().size());
    }

    private static void validateRecoveredBuildEntryCount(
            MechanicalSawPlan plan, int journalEntries) {
        if (journalEntries < 0 || journalEntries > plan.placements().size() + 2) {
            throw new IllegalArgumentException("Recovered C-07 placement prefix is too large");
        }
    }

    private static List<BlockPos3i> recoveryBuildPositions(MechanicalSawPlan plan) {
        List<BlockPos3i> expected = new java.util.ArrayList<>(plan.placements().stream()
                .map(value -> value.position())
                .toList());
        BlockPos3i source = plan.placement(MechanicalSawRole.WATER_SOURCE).position();
        expected.add(source.translate(0, -1, 0));
        expected.add(source.translate(0, -2, 0));
        return List.copyOf(expected);
    }

    private static CreatePlanExecutionPhase legacyPhase(GenericExecutionPhase phase) {
        return switch (phase) {
            case BUILD -> CreatePlanExecutionPhase.BUILDING;
            case POWER -> CreatePlanExecutionPhase.AWAITING_POWER;
            case FEED_INPUT -> CreatePlanExecutionPhase.FEEDING;
            case PROCESS -> CreatePlanExecutionPhase.PROCESSING;
            default -> throw new IllegalStateException(
                    "Unexpected C-07 generic execution phase: " + phase);
        };
    }

    private static ResourceId sessionId(ServerLevel level, MechanicalSawPlan plan) {
        ResourceLocation dimension = level.dimension().location();
        String dimensionPath = dimension.getNamespace() + "_"
                + dimension.getPath().replace('/', '_');
        return new ResourceId(
                "steve_industrial",
                "c07/session/" + dimensionPath + "/"
                        + plan.origin().x() + "_" + plan.origin().y() + "_"
                        + plan.origin().z() + "/" + executionTick(level) + "_"
                        + SESSION_SEQUENCE.incrementAndGet());
    }

    private static long executionTick(ServerLevel level) {
        return Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                ? Integer.toUnsignedLong(level.getServer().getTickCount())
                : level.getGameTime();
    }

    private static ResourceId failureId(AdapterFailureCode code) {
        return new ResourceId(
                "create",
                "v606/failure/" + code.name().toLowerCase(Locale.ROOT));
    }

    private static <T> AdapterResult<T> failure(
            AdapterFailureCode code,
            String detail) {
        return new AdapterResult.Failure<>(
                code,
                "Create 6.0.6 mechanical-saw executor: " + detail);
    }
}
