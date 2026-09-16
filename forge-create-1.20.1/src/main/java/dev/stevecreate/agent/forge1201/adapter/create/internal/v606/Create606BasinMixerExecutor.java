package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BasinMixerExecutionSession;
import dev.stevecreate.agent.adapter.api.BasinMixerExecutionUpdate;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.ExecutionCancellationResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.BoundedStepRunner;
import dev.stevecreate.agent.core.execution.ConditionEvaluation;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.execution.StepRunResult;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.plan.BasinMixerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import dev.stevecreate.agent.core.plan.BasinMixerRole;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

/** Exact Create 6.0.6 C-08 facade driven by the shared runner. */
public final class Create606BasinMixerExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSION_SEQUENCE =
            new AtomicLong();

    private Create606BasinMixerExecutor() {}

    public static AdapterResult<BasinMixerExecutionSession> begin(
            ServerLevel level,
            BasinMixerPlan plan,
            RuntimeFingerprint runtime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Optional<Create606BasinMixerActionHandler.FailureDetail>
                invalid =
                Create606BasinMixerActionHandler.validatePlan(
                        level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(
                    failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(
                new Session(
                        level, plan, runtime,
                        sessionId(level, plan), null, null));
    }

    static AdapterResult<BasinMixerExecutionSession>
            beginGoalDriven(
                    ServerLevel level,
                    BasinMixerPlan plan,
                    RuntimeFingerprint runtime,
                    ResourceId verifiedSessionId,
                    Create606WorldResourceBuffer resourceBuffer) {
        return beginGoalDriven(
                level, plan, runtime, verifiedSessionId,
                resourceBuffer, null);
    }

    static AdapterResult<BasinMixerExecutionSession>
            beginGoalDriven(
                    ServerLevel level,
                    BasinMixerPlan plan,
                    RuntimeFingerprint runtime,
                    ResourceId verifiedSessionId,
                    Create606WorldResourceBuffer resourceBuffer,
                    CreateV606ReservedFuelAccess reservedFuel) {
        Objects.requireNonNull(
                verifiedSessionId, "verifiedSessionId");
        Objects.requireNonNull(
                resourceBuffer, "resourceBuffer");
        Optional<Create606BasinMixerActionHandler.FailureDetail>
                invalid =
                Create606BasinMixerActionHandler.validatePlan(
                        level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(
                    failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime,
                verifiedSessionId, resourceBuffer, reservedFuel));
    }

    static AdapterResult<BasinMixerExecutionSession>
            resumeGoalDriven(
                    ServerLevel level,
                    BasinMixerPlan plan,
                    RuntimeFingerprint runtime,
                    Resumable resumable,
                    Create606WorldResourceBuffer resourceBuffer) {
        return resumeGoalDriven(
                level, plan, runtime, resumable, resourceBuffer, null);
    }

    static AdapterResult<BasinMixerExecutionSession>
            resumeGoalDriven(
                    ServerLevel level,
                    BasinMixerPlan plan,
                    RuntimeFingerprint runtime,
                    Resumable resumable,
                    Create606WorldResourceBuffer resourceBuffer,
                    CreateV606ReservedFuelAccess reservedFuel) {
        Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        String invalid = validateRecoveryBoundary(
                plan, resumable.session(), resumable.journal());
        if (invalid != null
                || !resumable.verifiedPositions().equals(
                        resumable.journal().modifiedPositions())) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    invalid == null
                            ? "Recovered C-08 positions were not all reconciled"
                            : invalid);
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, resumable.session(),
                resumable.journal(), resourceBuffer, reservedFuel));
    }

    private static final class Session
            implements BasinMixerExecutionSession {
        private final ServerLevel level;
        private final BasinMixerPlan physicalPlan;
        private final Create606BasinMixerActionHandler actionHandler;
        private final BoundedStepRunner runner;
        private GenericExecutionSession genericSession;

        private Session(
                ServerLevel level,
                BasinMixerPlan physicalPlan,
                RuntimeFingerprint runtime,
                ResourceId sessionId,
                Create606WorldResourceBuffer resourceBuffer,
                CreateV606ReservedFuelAccess reservedFuel) {
            this.level = Objects.requireNonNull(level, "level");
            this.physicalPlan = Objects.requireNonNull(
                    physicalPlan, "physicalPlan");
            GenericExecutionPlan genericPlan =
                    BasinMixerGenericExecutionPlan.from(
                            physicalPlan);
            this.actionHandler = resourceBuffer == null
                    ? new Create606BasinMixerActionHandler(
                            level, physicalPlan, runtime, sessionId)
                    : new Create606BasinMixerActionHandler(
                            level, physicalPlan, runtime,
                            sessionId, resourceBuffer, reservedFuel);
            this.runner = runner(physicalPlan, actionHandler);
            this.genericSession = GenericExecutionSession.start(
                    sessionId, genericPlan, executionTick(level));
            LOGGER.info(
                    "CREATE_BASIN_MIXER_GENERIC_EXECUTION plan={} graph={} nodes={} steps={} handler={} recipe={} inputs={} heat={}",
                    genericPlan.planId(),
                    genericPlan.machineGraph().id(),
                    genericPlan.machineGraph().nodes().size(),
                    genericPlan.steps().size(),
                    BasinMixerGenericExecutionPlan.ACTION_HANDLER_ID,
                    physicalPlan.process().recipeId(),
                    physicalPlan.process().itemInputs().size(),
                    physicalPlan.process().heatMode());
        }

        private Session(
                ServerLevel level,
                BasinMixerPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal,
                Create606WorldResourceBuffer resourceBuffer,
                CreateV606ReservedFuelAccess reservedFuel) {
            this.level = Objects.requireNonNull(level, "level");
            this.physicalPlan = Objects.requireNonNull(
                    physicalPlan, "physicalPlan");
            this.genericSession = Objects.requireNonNull(
                    recoveredSession, "recoveredSession");
            this.actionHandler = new Create606BasinMixerActionHandler(
                    level, physicalPlan, runtime, recoveredSession.sessionId(),
                    recoveredJournal,
                    recoveredPlacementCursor(physicalPlan, recoveredJournal.entries().size()),
                    recoveredPilotFlowCursor(physicalPlan, recoveredJournal.entries().size()),
                    resourceBuffer,
                    reservedFuel);
            this.runner = runner(physicalPlan, actionHandler);
            LOGGER.info(
                    "CREATE_BASIN_MIXER_RECOVERED session={} currentStep={} completedSteps={} placementCursor={} journalEntries={}",
                    recoveredSession.sessionId(),
                    recoveredSession.currentStep().orElseThrow().stepId(),
                    recoveredSession.completedStepIds().size(),
                    actionHandler.placementIndex(),
                    recoveredJournal.entries().size());
        }

        private BoundedStepRunner runner(
                BasinMixerPlan plan,
                Create606BasinMixerActionHandler handler) {
            return new BoundedStepRunner(
                    Map.of(
                            BasinMixerGenericExecutionPlan.ACTION_HANDLER_ID,
                            handler),
                    Map.of(
                            BasinMixerGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                            this::evaluatePreflightCondition),
                    BasinMixerGenericExecutionPlan.verificationRules(plan));
        }

        @Override
        public AdapterResult<BasinMixerExecutionUpdate> tick() {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-08 tick must run on the authoritative server thread");
            }
            if (genericSession.status()
                    == GenericExecutionSessionStatus.COMPLETED) {
                return completed();
            }
            if (genericSession.status().terminal()) {
                return terminalFailure();
            }
            Optional<ChunkPos> unloaded =
                    actionHandler.firstUnloadedChunk();
            if (unloaded.isPresent()) {
                ChunkPos chunk = unloaded.orElseThrow();
                return failure(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "C-08 preflight area unloaded: "
                                + chunk.x + "," + chunk.z);
            }
            StepRunResult result = runner.tick(
                    genericSession, executionTick(level));
            genericSession = result.session();
            if (genericSession.status()
                    == GenericExecutionSessionStatus.COMPLETED) {
                return completed();
            }
            if (genericSession.status().terminal()) {
                return terminalFailure();
            }
            GenericExecutionPhase phase =
                    genericSession.currentPhase().orElseThrow();
            return new AdapterResult.Success<>(
                    new BasinMixerExecutionUpdate.InProgress(
                            legacyPhase(phase),
                            actionHandler.placementIndex(),
                            physicalPlan.placements().size(),
                            executionTick(level)));
        }

        @Override
        public AdapterResult<ExecutionCancellationResult> cancel(
                ResourceId reason) {
            Objects.requireNonNull(reason, "reason");
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-08 cancellation must run on the server thread");
            }
            if (genericSession.status()
                    != GenericExecutionSessionStatus.RUNNING) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-08 session is already terminal: "
                                + genericSession.status());
            }
            StepRunResult result = runner.tick(
                    genericSession,
                    executionTick(level),
                    Optional.of(reason));
            genericSession = result.session();
            if (genericSession.status()
                    != GenericExecutionSessionStatus.CANCELLED) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Generic runner rejected C-08 cancellation");
            }
            WorldChangeJournal.RollbackReport rollback =
                    actionHandler.rollback();
            WorldChangeJournal journal =
                    actionHandler.worldChangeJournal();
            return new AdapterResult.Success<>(
                    new ExecutionCancellationResult(
                            genericSession.sessionId(),
                            reason, journal, rollback));
        }

        @Override
        public AdapterResult<RecoveryCheckpoint>
                recoveryCheckpoint(long savedTick) {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-08 checkpoint must run on the server thread");
            }
            try {
                actionHandler.stabilizeRecoveryAfterStates();
                return new AdapterResult.Success<>(
                        RecoveryCheckpoint.capture(
                                genericSession,
                                actionHandler.worldChangeJournal(),
                                savedTick));
            } catch (IllegalArgumentException
                    | IllegalStateException exception) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-08 checkpoint was rejected: "
                                + exception.getMessage());
            }
        }

        @Override
        public WorldChangeJournal worldChangeJournal() {
            return actionHandler.worldChangeJournal();
        }

        private ConditionEvaluation evaluatePreflightCondition(
                StepCondition condition,
                dev.stevecreate.agent.core.execution
                        .StepRunnerContext context) {
            if (!condition.parameters().isEmpty()) {
                return ConditionEvaluation.error(
                        failureId(
                                AdapterFailureCode.PLAN_REJECTED),
                        "C-08 preflight parameters must be empty");
            }
            boolean unloaded =
                    actionHandler.firstUnloadedChunk().isPresent();
            if (condition.conditionType().equals(
                    BasinMixerGenericExecutionPlan
                            .PREFLIGHT_LOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.error(
                                failureId(
                                        AdapterFailureCode
                                                .CHUNK_NOT_LOADED),
                                "C-08 preflight area is no longer loaded")
                        : ConditionEvaluation.satisfied();
            }
            if (condition.conditionType().equals(
                    BasinMixerGenericExecutionPlan
                            .PREFLIGHT_UNLOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.satisfied()
                        : ConditionEvaluation.unsatisfied();
            }
            return ConditionEvaluation.error(
                    failureId(AdapterFailureCode.PLAN_REJECTED),
                    "Unknown C-08 preflight condition");
        }

        private AdapterResult<BasinMixerExecutionUpdate>
                completed() {
            return actionHandler.completedEvidence()
                    .<AdapterResult<BasinMixerExecutionUpdate>>map(
                            value -> new AdapterResult.Success<>(
                                    new BasinMixerExecutionUpdate
                                            .Completed(value)))
                    .orElseGet(() -> failure(
                            AdapterFailureCode.CREATE_API_FAILURE,
                            "Session completed without C-08 physical evidence"));
        }

        private AdapterResult<BasinMixerExecutionUpdate>
                terminalFailure() {
            Optional<Create606BasinMixerActionHandler.FailureDetail>
                    handler = actionHandler.lastFailure();
            if (handler.isPresent()) {
                var value = handler.orElseThrow();
                return new AdapterResult.Failure<>(
                        value.code(), value.detail());
            }
            AdapterFailureCode code =
                    genericSession.status()
                            == GenericExecutionSessionStatus.TIMED_OUT
                    ? AdapterFailureCode.EXECUTION_TIMEOUT
                    : AdapterFailureCode.PROCESSING_FAILED;
            String detail = genericSession.failure()
                    .map(value -> value.failureCode()
                            + ": " + value.detail())
                    .orElse(
                            "C-08 ended without typed failure detail");
            return failure(code, detail);
        }
    }

    private static String validateRecoveryBoundary(
            BasinMixerPlan plan,
            GenericExecutionSession session,
            WorldChangeJournal journal) {
        return Create606PreResourceRecovery.validate(
                "C-08",
                BasinMixerGenericExecutionPlan.from(plan),
                session,
                journal,
                recoveryBuildPositions(plan),
                BasinMixerGenericExecutionPlan.BUILD_STEP_ID,
                BasinMixerGenericExecutionPlan.POWER_STEP_ID);
    }

    static int recoveredPlacementCursor(BasinMixerPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.min(journalEntries, plan.placements().size());
    }

    static int recoveredPilotFlowCursor(BasinMixerPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.max(0, journalEntries - plan.placements().size());
    }

    private static void validateRecoveredBuildEntryCount(
            BasinMixerPlan plan, int journalEntries) {
        if (journalEntries < 0 || journalEntries > plan.placements().size() + 2) {
            throw new IllegalArgumentException("Recovered C-08 placement prefix is too large");
        }
    }

    private static List<BlockPos3i> recoveryBuildPositions(BasinMixerPlan plan) {
        List<BlockPos3i> expected = new ArrayList<>(plan.placements().stream()
                .map(value -> value.position()).toList());
        BlockPos3i source = plan.placement(BasinMixerRole.WATER_SOURCE).position();
        expected.add(source.translate(0, -1, 0));
        expected.add(source.translate(0, -2, 0));
        return List.copyOf(expected);
    }

    private static CreatePlanExecutionPhase legacyPhase(
            GenericExecutionPhase phase) {
        return switch (phase) {
            case BUILD -> CreatePlanExecutionPhase.BUILDING;
            case POWER ->
                    CreatePlanExecutionPhase.AWAITING_POWER;
            case FEED_INPUT ->
                    CreatePlanExecutionPhase.FEEDING;
            case PROCESS ->
                    CreatePlanExecutionPhase.PROCESSING;
            default -> throw new IllegalStateException(
                    "Unexpected C-08 phase " + phase);
        };
    }

    private static ResourceId sessionId(
            ServerLevel level, BasinMixerPlan plan) {
        ResourceLocation dimension =
                level.dimension().location();
        String dimensionPath =
                dimension.getNamespace() + "_"
                        + dimension.getPath().replace('/', '_');
        return new ResourceId(
                "steve_industrial",
                "c08/session/" + dimensionPath + "/"
                        + plan.origin().x() + "_"
                        + plan.origin().y() + "_"
                        + plan.origin().z() + "/"
                        + executionTick(level) + "_"
                        + SESSION_SEQUENCE.incrementAndGet());
    }

    private static long executionTick(ServerLevel level) {
        return Boolean.getBoolean(
                DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                ? Integer.toUnsignedLong(
                        level.getServer().getTickCount())
                : level.getGameTime();
    }

    private static ResourceId failureId(
            AdapterFailureCode code) {
        return new ResourceId(
                "create",
                "v606/failure/"
                        + code.name().toLowerCase(Locale.ROOT));
    }

    private static <T> AdapterResult<T> failure(
            AdapterFailureCode code, String detail) {
        return new AdapterResult.Failure<>(
                code,
                "Create 6.0.6 basin/mixer executor: " + detail);
    }
}
