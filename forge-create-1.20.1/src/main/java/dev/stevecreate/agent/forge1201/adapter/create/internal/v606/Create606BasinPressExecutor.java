package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BasinPressExecutionSession;
import dev.stevecreate.agent.adapter.api.BasinPressExecutionUpdate;
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
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BasinPressPlan;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import java.util.ArrayList;
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

/** Exact Create 6.0.6 C-09 facade driven by the shared bounded runner. */
public final class Create606BasinPressExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private Create606BasinPressExecutor() {}

    public static AdapterResult<BasinPressExecutionSession> begin(
            ServerLevel level,
            BasinPressPlan plan,
            RuntimeFingerprint runtime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Optional<Create606BasinPressActionHandler.FailureDetail> invalid =
                Create606BasinPressActionHandler.validatePlan(level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(
                    failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(
                new Session(level, plan, runtime, sessionId(level, plan), null));
    }

    static AdapterResult<BasinPressExecutionSession> beginGoalDriven(
            ServerLevel level,
            BasinPressPlan plan,
            RuntimeFingerprint runtime,
            ResourceId verifiedSessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        Objects.requireNonNull(verifiedSessionId, "verifiedSessionId");
        Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        Optional<Create606BasinPressActionHandler.FailureDetail> invalid =
                Create606BasinPressActionHandler.validatePlan(level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(
                    failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, verifiedSessionId, resourceBuffer));
    }

    static AdapterResult<BasinPressExecutionSession> resumeGoalDriven(
            ServerLevel level,
            BasinPressPlan plan,
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
                            ? "Recovered C-09 positions were not all reconciled"
                            : invalid);
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, resumable.session(),
                resumable.journal(), resourceBuffer));
    }

    private static final class Session implements BasinPressExecutionSession {
        private final ServerLevel level;
        private final BasinPressPlan physicalPlan;
        private final Create606BasinPressActionHandler actionHandler;
        private final BoundedStepRunner runner;
        private GenericExecutionSession genericSession;

        private Session(
                ServerLevel level,
                BasinPressPlan physicalPlan,
                RuntimeFingerprint runtime,
                ResourceId sessionId,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = Objects.requireNonNull(level, "level");
            this.physicalPlan = Objects.requireNonNull(
                    physicalPlan, "physicalPlan");
            GenericExecutionPlan genericPlan =
                    BasinPressGenericExecutionPlan.from(physicalPlan);
            this.actionHandler = resourceBuffer == null
                    ? new Create606BasinPressActionHandler(
                            level, physicalPlan, runtime, sessionId)
                    : new Create606BasinPressActionHandler(
                            level, physicalPlan, runtime, sessionId,
                            resourceBuffer);
            this.runner = runner(physicalPlan, actionHandler);
            this.genericSession = GenericExecutionSession.start(
                    sessionId, genericPlan, executionTick(level));
            LOGGER.info(
                    "CREATE_BASIN_PRESS_GENERIC_EXECUTION plan={} graph={} nodes={} steps={} handler={} recipe={} inputs={} heat={}",
                    genericPlan.planId(),
                    genericPlan.machineGraph().id(),
                    genericPlan.machineGraph().nodes().size(),
                    genericPlan.steps().size(),
                    BasinPressGenericExecutionPlan.ACTION_HANDLER_ID,
                    physicalPlan.process().recipeId(),
                    physicalPlan.process().itemInputs().size(),
                    physicalPlan.process().heatMode());
        }

        private Session(
                ServerLevel level,
                BasinPressPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = Objects.requireNonNull(level, "level");
            this.physicalPlan = Objects.requireNonNull(
                    physicalPlan, "physicalPlan");
            this.genericSession = Objects.requireNonNull(
                    recoveredSession, "recoveredSession");
            this.actionHandler = new Create606BasinPressActionHandler(
                    level, physicalPlan, runtime, recoveredSession.sessionId(),
                    recoveredJournal,
                    recoveredPlacementCursor(physicalPlan, recoveredJournal.entries().size()),
                    recoveredPilotFlowCursor(physicalPlan, recoveredJournal.entries().size()),
                    resourceBuffer);
            this.runner = runner(physicalPlan, actionHandler);
            LOGGER.info(
                    "CREATE_BASIN_PRESS_RECOVERED session={} currentStep={} completedSteps={} placementCursor={} journalEntries={}",
                    recoveredSession.sessionId(),
                    recoveredSession.currentStep().orElseThrow().stepId(),
                    recoveredSession.completedStepIds().size(),
                    actionHandler.placementIndex(),
                    recoveredJournal.entries().size());
        }

        private BoundedStepRunner runner(
                BasinPressPlan plan,
                Create606BasinPressActionHandler handler) {
            return new BoundedStepRunner(
                    Map.of(
                            BasinPressGenericExecutionPlan.ACTION_HANDLER_ID,
                            handler),
                    Map.of(
                            BasinPressGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                            this::evaluatePreflightCondition),
                    BasinPressGenericExecutionPlan.verificationRules(plan));
        }

        @Override
        public AdapterResult<BasinPressExecutionUpdate> tick() {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-09 tick must run on the authoritative server thread");
            }
            if (genericSession.status()
                    == GenericExecutionSessionStatus.COMPLETED) {
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
                        "C-09 preflight area unloaded: "
                                + chunk.x + "," + chunk.z);
            }
            StepRunResult result =
                    runner.tick(genericSession, executionTick(level));
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
                    new BasinPressExecutionUpdate.InProgress(
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
                        "C-09 cancellation must run on the server thread");
            }
            if (genericSession.status()
                    != GenericExecutionSessionStatus.RUNNING) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-09 session is already terminal: "
                                + genericSession.status());
            }
            StepRunResult result = runner.tick(
                    genericSession, executionTick(level), Optional.of(reason));
            genericSession = result.session();
            if (genericSession.status()
                    != GenericExecutionSessionStatus.CANCELLED) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Generic runner rejected C-09 cancellation");
            }
            WorldChangeJournal.RollbackReport rollback =
                    actionHandler.rollback();
            WorldChangeJournal journal =
                    actionHandler.worldChangeJournal();
            return new AdapterResult.Success<>(
                    new ExecutionCancellationResult(
                            genericSession.sessionId(), reason,
                            journal, rollback));
        }

        @Override
        public AdapterResult<RecoveryCheckpoint> recoveryCheckpoint(
                long savedTick) {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-09 checkpoint must run on the server thread");
            }
            try {
                actionHandler.stabilizeRecoveryAfterStates();
                return new AdapterResult.Success<>(RecoveryCheckpoint.capture(
                        genericSession,
                        actionHandler.worldChangeJournal(),
                        savedTick));
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-09 checkpoint was rejected: "
                                + exception.getMessage());
            }
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
                        "C-09 preflight parameters must be empty");
            }
            boolean unloaded =
                    actionHandler.firstUnloadedChunk().isPresent();
            if (condition.conditionType().equals(
                    BasinPressGenericExecutionPlan
                            .PREFLIGHT_LOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.error(
                                failureId(AdapterFailureCode.CHUNK_NOT_LOADED),
                                "C-09 preflight area is no longer loaded")
                        : ConditionEvaluation.satisfied();
            }
            if (condition.conditionType().equals(
                    BasinPressGenericExecutionPlan
                            .PREFLIGHT_UNLOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.satisfied()
                        : ConditionEvaluation.unsatisfied();
            }
            return ConditionEvaluation.error(
                    failureId(AdapterFailureCode.PLAN_REJECTED),
                    "Unknown C-09 preflight condition");
        }

        private AdapterResult<BasinPressExecutionUpdate> completed() {
            return actionHandler.completedEvidence()
                    .<AdapterResult<BasinPressExecutionUpdate>>map(value ->
                            new AdapterResult.Success<>(
                                    new BasinPressExecutionUpdate.Completed(value)))
                    .orElseGet(() -> failure(
                            AdapterFailureCode.CREATE_API_FAILURE,
                            "Session completed without C-09 physical evidence"));
        }

        private AdapterResult<BasinPressExecutionUpdate> terminalFailure() {
            Optional<Create606BasinPressActionHandler.FailureDetail> handler =
                    actionHandler.lastFailure();
            if (handler.isPresent()) {
                var value = handler.orElseThrow();
                return new AdapterResult.Failure<>(
                        value.code(), value.detail());
            }
            AdapterFailureCode code = genericSession.status()
                    == GenericExecutionSessionStatus.TIMED_OUT
                    ? AdapterFailureCode.EXECUTION_TIMEOUT
                    : AdapterFailureCode.PROCESSING_FAILED;
            String detail = genericSession.failure()
                    .map(value -> value.failureCode() + ": " + value.detail())
                    .orElse("C-09 ended without typed failure detail");
            return failure(code, detail);
        }
    }

    private static String validateRecoveryBoundary(
            BasinPressPlan plan,
            GenericExecutionSession session,
            WorldChangeJournal journal) {
        return Create606PreResourceRecovery.validate(
                "C-09",
                BasinPressGenericExecutionPlan.from(plan),
                session,
                journal,
                recoveryBuildPositions(plan),
                BasinPressGenericExecutionPlan.BUILD_STEP_ID,
                BasinPressGenericExecutionPlan.POWER_STEP_ID);
    }

    static int recoveredPlacementCursor(BasinPressPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.min(journalEntries, plan.placements().size());
    }

    static int recoveredPilotFlowCursor(BasinPressPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.max(0, journalEntries - plan.placements().size());
    }

    private static void validateRecoveredBuildEntryCount(
            BasinPressPlan plan, int journalEntries) {
        if (journalEntries < 0 || journalEntries > plan.placements().size() + 2) {
            throw new IllegalArgumentException("Recovered C-09 placement prefix is too large");
        }
    }

    private static List<BlockPos3i> recoveryBuildPositions(BasinPressPlan plan) {
        List<BlockPos3i> expected = new ArrayList<>(plan.placements().stream()
                .map(value -> value.position()).toList());
        BlockPos3i source = plan.placement(
                dev.stevecreate.agent.core.plan.BasinPressRole.WATER_SOURCE).position();
        expected.add(source.translate(0, -1, 0));
        expected.add(source.translate(0, -2, 0));
        return List.copyOf(expected);
    }

    private static CreatePlanExecutionPhase legacyPhase(
            GenericExecutionPhase phase) {
        return switch (phase) {
            case BUILD -> CreatePlanExecutionPhase.BUILDING;
            case POWER -> CreatePlanExecutionPhase.AWAITING_POWER;
            case FEED_INPUT -> CreatePlanExecutionPhase.FEEDING;
            case PROCESS -> CreatePlanExecutionPhase.PROCESSING;
            default -> throw new IllegalStateException(
                    "Unexpected C-09 phase " + phase);
        };
    }

    private static ResourceId sessionId(
            ServerLevel level, BasinPressPlan plan) {
        ResourceLocation dimension = level.dimension().location();
        String dimensionPath = dimension.getNamespace() + "_"
                + dimension.getPath().replace('/', '_');
        return new ResourceId(
                "steve_industrial",
                "c09/session/" + dimensionPath + "/"
                        + plan.origin().x() + "_" + plan.origin().y() + "_"
                        + plan.origin().z() + "/" + executionTick(level) + "_"
                        + SESSION_SEQUENCE.incrementAndGet());
    }

    private static long executionTick(ServerLevel level) {
        return Boolean.getBoolean(
                DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                ? Integer.toUnsignedLong(
                        level.getServer().getTickCount())
                : level.getGameTime();
    }

    private static ResourceId failureId(AdapterFailureCode code) {
        return new ResourceId(
                "create", "v606/failure/"
                        + code.name().toLowerCase(Locale.ROOT));
    }

    private static <T> AdapterResult<T> failure(
            AdapterFailureCode code, String detail) {
        return new AdapterResult.Failure<>(
                code, "Create 6.0.6 basin/press executor: " + detail);
    }
}
