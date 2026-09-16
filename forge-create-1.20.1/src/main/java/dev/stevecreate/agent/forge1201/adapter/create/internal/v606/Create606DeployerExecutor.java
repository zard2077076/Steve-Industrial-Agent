package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.DeployerExecutionSession;
import dev.stevecreate.agent.adapter.api.DeployerExecutionUpdate;
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
import dev.stevecreate.agent.core.plan.DeployerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.DeployerPlan;
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

/** Exact Create 6.0.6 C-10 facade driven by the shared bounded runner. */
public final class Create606DeployerExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSION_SEQUENCE =
            new AtomicLong();

    private Create606DeployerExecutor() {}

    public static AdapterResult<DeployerExecutionSession> begin(
            ServerLevel level,
            DeployerPlan plan,
            RuntimeFingerprint runtime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Optional<Create606DeployerActionHandler.FailureDetail>
                invalid =
                        Create606DeployerActionHandler.validatePlan(
                                level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(
                    failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(
                level,
                plan,
                runtime,
                sessionId(level, plan),
                null));
    }

    static AdapterResult<DeployerExecutionSession>
            beginGoalDriven(
                    ServerLevel level,
                    DeployerPlan plan,
                    RuntimeFingerprint runtime,
                    ResourceId verifiedSessionId,
                    Create606WorldResourceBuffer resourceBuffer) {
        Objects.requireNonNull(
                verifiedSessionId, "verifiedSessionId");
        Objects.requireNonNull(
                resourceBuffer, "resourceBuffer");
        Optional<Create606DeployerActionHandler.FailureDetail>
                invalid =
                        Create606DeployerActionHandler.validatePlan(
                                level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(
                    failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(
                level,
                plan,
                runtime,
                verifiedSessionId,
                resourceBuffer));
    }

    static AdapterResult<DeployerExecutionSession>
            resumeGoalDriven(
                    ServerLevel level,
                    DeployerPlan plan,
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
                            ? "Recovered C-10 positions were not all reconciled"
                            : invalid);
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, resumable.session(),
                resumable.journal(), resourceBuffer));
    }

    private static final class Session
            implements DeployerExecutionSession {
        private final ServerLevel level;
        private final DeployerPlan physicalPlan;
        private final Create606DeployerActionHandler actionHandler;
        private final BoundedStepRunner runner;
        private GenericExecutionSession genericSession;

        private Session(
                ServerLevel level,
                DeployerPlan physicalPlan,
                RuntimeFingerprint runtime,
                ResourceId sessionId,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = Objects.requireNonNull(level, "level");
            this.physicalPlan = Objects.requireNonNull(
                    physicalPlan, "physicalPlan");
            GenericExecutionPlan genericPlan =
                    DeployerGenericExecutionPlan.from(physicalPlan);
            this.actionHandler = resourceBuffer == null
                    ? new Create606DeployerActionHandler(
                            level,
                            physicalPlan,
                            runtime,
                            sessionId)
                    : new Create606DeployerActionHandler(
                            level,
                            physicalPlan,
                            runtime,
                            sessionId,
                            resourceBuffer);
            this.runner = runner(physicalPlan, actionHandler);
            this.genericSession = GenericExecutionSession.start(
                    sessionId,
                    genericPlan,
                    executionTick(level));
            LOGGER.info(
                    "CREATE_DEPLOYER_GENERIC_EXECUTION plan={} graph={} nodes={} steps={} handler={} recipe={} held={} disposition={} target=owned_depot_item face=down",
                    genericPlan.planId(),
                    genericPlan.machineGraph().id(),
                    genericPlan.machineGraph().nodes().size(),
                    genericPlan.steps().size(),
                    DeployerGenericExecutionPlan.ACTION_HANDLER_ID,
                    physicalPlan.process().recipeId(),
                    physicalPlan.process().heldItem(),
                    physicalPlan.process().heldItemDisposition());
        }

        private Session(
                ServerLevel level,
                DeployerPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = Objects.requireNonNull(level, "level");
            this.physicalPlan = Objects.requireNonNull(
                    physicalPlan, "physicalPlan");
            this.genericSession = Objects.requireNonNull(
                    recoveredSession, "recoveredSession");
            this.actionHandler = new Create606DeployerActionHandler(
                    level, physicalPlan, runtime, recoveredSession.sessionId(),
                    recoveredJournal,
                    recoveredPlacementCursor(physicalPlan, recoveredJournal.entries().size()),
                    recoveredPilotFlowCursor(physicalPlan, recoveredJournal.entries().size()),
                    resourceBuffer);
            this.runner = runner(physicalPlan, actionHandler);
            LOGGER.info(
                    "CREATE_DEPLOYER_RECOVERED session={} currentStep={} completedSteps={} placementCursor={} journalEntries={}",
                    recoveredSession.sessionId(),
                    recoveredSession.currentStep().orElseThrow().stepId(),
                    recoveredSession.completedStepIds().size(),
                    actionHandler.placementIndex(),
                    recoveredJournal.entries().size());
        }

        private BoundedStepRunner runner(
                DeployerPlan plan,
                Create606DeployerActionHandler handler) {
            return new BoundedStepRunner(
                    Map.of(
                            DeployerGenericExecutionPlan.ACTION_HANDLER_ID,
                            handler),
                    Map.of(
                            DeployerGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                            this::evaluatePreflightCondition),
                    DeployerGenericExecutionPlan.verificationRules(plan));
        }

        @Override
        public AdapterResult<DeployerExecutionUpdate> tick() {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-10 tick must run on the authoritative server thread");
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
                        "C-10 preflight area unloaded: "
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
                    new DeployerExecutionUpdate.InProgress(
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
                        "C-10 cancellation must run on the server thread");
            }
            if (genericSession.status()
                    != GenericExecutionSessionStatus.RUNNING) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-10 session is already terminal: "
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
                        "Generic runner rejected C-10 cancellation");
            }
            WorldChangeJournal.RollbackReport rollback =
                    actionHandler.rollback();
            return new AdapterResult.Success<>(
                    new ExecutionCancellationResult(
                            genericSession.sessionId(),
                            reason,
                            actionHandler.worldChangeJournal(),
                            rollback));
        }

        @Override
        public AdapterResult<RecoveryCheckpoint>
                recoveryCheckpoint(long savedTick) {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "C-10 checkpoint must run on the server thread");
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
                        "C-10 checkpoint was rejected: "
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
                                .StepRunnerContext
                        context) {
            if (!condition.parameters().isEmpty()) {
                return ConditionEvaluation.error(
                        failureId(
                                AdapterFailureCode.PLAN_REJECTED),
                        "C-10 preflight parameters must be empty");
            }
            boolean unloaded =
                    actionHandler.firstUnloadedChunk().isPresent();
            if (condition.conditionType().equals(
                    DeployerGenericExecutionPlan
                            .PREFLIGHT_LOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.error(
                                failureId(
                                        AdapterFailureCode
                                                .CHUNK_NOT_LOADED),
                                "C-10 preflight area is no longer loaded")
                        : ConditionEvaluation.satisfied();
            }
            if (condition.conditionType().equals(
                    DeployerGenericExecutionPlan
                            .PREFLIGHT_UNLOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.satisfied()
                        : ConditionEvaluation.unsatisfied();
            }
            return ConditionEvaluation.error(
                    failureId(AdapterFailureCode.PLAN_REJECTED),
                    "Unknown C-10 preflight condition");
        }

        private AdapterResult<DeployerExecutionUpdate>
                completed() {
            return actionHandler.completedEvidence()
                    .<AdapterResult<DeployerExecutionUpdate>>map(
                            value -> new AdapterResult.Success<>(
                                    new DeployerExecutionUpdate
                                            .Completed(value)))
                    .orElseGet(() -> failure(
                            AdapterFailureCode.CREATE_API_FAILURE,
                            "Session completed without C-10 physical evidence"));
        }

        private AdapterResult<DeployerExecutionUpdate>
                terminalFailure() {
            Optional<Create606DeployerActionHandler.FailureDetail>
                    handler = actionHandler.lastFailure();
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
                    .map(value -> value.failureCode()
                            + ": " + value.detail())
                    .orElse(
                            "C-10 ended without typed failure detail");
            return failure(code, detail);
        }
    }

    private static String validateRecoveryBoundary(
            DeployerPlan plan,
            GenericExecutionSession session,
            WorldChangeJournal journal) {
        return Create606PreResourceRecovery.validate(
                "C-10",
                DeployerGenericExecutionPlan.from(plan),
                session,
                journal,
                recoveryBuildPositions(plan),
                DeployerGenericExecutionPlan.BUILD_STEP_ID,
                DeployerGenericExecutionPlan.POWER_STEP_ID);
    }

    static int recoveredPlacementCursor(DeployerPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.min(journalEntries, plan.placements().size());
    }

    static int recoveredPilotFlowCursor(DeployerPlan plan, int journalEntries) {
        validateRecoveredBuildEntryCount(plan, journalEntries);
        return Math.max(0, journalEntries - plan.placements().size());
    }

    private static void validateRecoveredBuildEntryCount(
            DeployerPlan plan, int journalEntries) {
        if (journalEntries < 0 || journalEntries > plan.placements().size() + 2) {
            throw new IllegalArgumentException("Recovered C-10 placement prefix is too large");
        }
    }

    private static List<BlockPos3i> recoveryBuildPositions(DeployerPlan plan) {
        List<BlockPos3i> expected = new ArrayList<>(plan.placements().stream()
                .map(value -> value.position()).toList());
        BlockPos3i source = plan.placement(
                dev.stevecreate.agent.core.plan.DeployerRole.WATER_SOURCE).position();
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
                    "Unexpected C-10 phase " + phase);
        };
    }

    private static ResourceId sessionId(
            ServerLevel level, DeployerPlan plan) {
        ResourceLocation dimension =
                level.dimension().location();
        String dimensionPath = dimension.getNamespace() + "_"
                + dimension.getPath().replace('/', '_');
        return new ResourceId(
                "steve_industrial",
                "c10/session/" + dimensionPath + "/"
                        + plan.origin().x() + "_"
                        + plan.origin().y() + "_"
                        + plan.origin().z() + "/"
                        + executionTick(level) + "_"
                        + SESSION_SEQUENCE.incrementAndGet());
    }

    private static long executionTick(ServerLevel level) {
        return Boolean.getBoolean(
                        DeceasedCraftExecutionPilotFixture
                                .ENABLE_PROPERTY)
                ? Integer.toUnsignedLong(
                        level.getServer().getTickCount())
                : level.getGameTime();
    }

    private static ResourceId failureId(
            AdapterFailureCode code) {
        return new ResourceId(
                "create",
                "v606/failure/"
                        + code.name()
                                .toLowerCase(Locale.ROOT));
    }

    private static <T> AdapterResult<T> failure(
            AdapterFailureCode code, String detail) {
        return new AdapterResult.Failure<>(
                code,
                "Create 6.0.6 Deployer executor: " + detail);
    }
}
