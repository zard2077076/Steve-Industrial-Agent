package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionSession;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionUpdate;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.ExecutionCancellationResult;
import dev.stevecreate.agent.adapter.api.RecoverableExecutionSession;
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
import dev.stevecreate.agent.core.plan.BeltPressBuildStep;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryPlanSnapshot;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

/** Exact Create 6.0.6 C-04 facade driven by the shared graph/session/runner/rule path. */
public final class Create606BeltPressExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private Create606BeltPressExecutor() {
    }

    public static AdapterResult<BeltPressExecutionSession> begin(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Optional<Create606BeltPressActionHandler.FailureDetail> invalid =
                Create606BeltPressActionHandler.validatePlan(level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(level, plan, runtime));
    }

    static AdapterResult<BeltPressExecutionSession> beginGoalDriven(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime,
            ResourceId verifiedSessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(verifiedSessionId, "verifiedSessionId");
        Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        Optional<Create606BeltPressActionHandler.FailureDetail> invalid =
                Create606BeltPressActionHandler.validatePlan(level, plan);
        if (invalid.isPresent()) {
            var failure = invalid.orElseThrow();
            return new AdapterResult.Failure<>(failure.code(), failure.detail());
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, verifiedSessionId, resourceBuffer));
    }

    public static AdapterResult<BeltPressExecutionSession> resume(
            ServerLevel level,
            BeltPressPlan plan,
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
                    "Recovered C-04 positions were not all reconciled");
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, resumable.session(), resumable.journal()));
    }

    static AdapterResult<BeltPressExecutionSession> resumeGoalDriven(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime,
            Resumable resumable,
            Create606WorldResourceBuffer resourceBuffer) {
        Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        String invalid = validateRecoveryBoundary(plan, resumable.session(), resumable.journal());
        if (invalid != null || !resumable.verifiedPositions().equals(
                resumable.journal().modifiedPositions())) {
            return failure(AdapterFailureCode.PROCESSING_FAILED,
                    invalid == null ? "Recovered C-04 positions were not all reconciled" : invalid);
        }
        return new AdapterResult.Success<>(new Session(
                level, plan, runtime, resumable.session(), resumable.journal(), resourceBuffer));
    }

    private static final class Session
            implements BeltPressExecutionSession, RecoverableExecutionSession {
        private final ServerLevel level;
        private final BeltPressPlan physicalPlan;
        private final Create606BeltPressActionHandler actionHandler;
        private final BoundedStepRunner runner;
        private GenericExecutionSession genericSession;

        private Session(
                ServerLevel level,
                BeltPressPlan physicalPlan,
                RuntimeFingerprint runtime) {
            this(level, physicalPlan, runtime, sessionId(level, physicalPlan), null);
        }

        private Session(
                ServerLevel level,
                BeltPressPlan physicalPlan,
                RuntimeFingerprint runtime,
                ResourceId sessionId,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = level;
            this.physicalPlan = physicalPlan;
            GenericExecutionPlan genericPlan =
                    BeltPressGenericExecutionPlan.from(physicalPlan);
            this.actionHandler = resourceBuffer == null
                    ? new Create606BeltPressActionHandler(
                            level, physicalPlan, runtime, sessionId)
                    : new Create606BeltPressActionHandler(
                            level, physicalPlan, runtime, sessionId, resourceBuffer);
            this.runner = new BoundedStepRunner(
                    Map.of(
                            BeltPressGenericExecutionPlan.ACTION_HANDLER_ID,
                            actionHandler),
                    Map.of(
                            BeltPressGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                            this::evaluatePreflightCondition),
                    BeltPressGenericExecutionPlan.verificationRules(physicalPlan));
            this.genericSession = GenericExecutionSession.start(
                    sessionId,
                    genericPlan,
                    executionTick(level));
            LOGGER.info(
                    "CREATE_BELT_PRESS_GENERIC_EXECUTION plan={} graph={} nodes={} steps={} runner={} verifier={} handler={}",
                    genericPlan.planId(),
                    genericPlan.machineGraph().id(),
                    genericPlan.machineGraph().nodes().size(),
                    genericPlan.steps().size(),
                    BoundedStepRunner.class.getSimpleName(),
                    "GenericVerificationRule",
                    BeltPressGenericExecutionPlan.ACTION_HANDLER_ID);
        }

        private Session(
                ServerLevel level,
                BeltPressPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal) {
            this(level, physicalPlan, runtime, recoveredSession, recoveredJournal, null);
        }

        private Session(
                ServerLevel level,
                BeltPressPlan physicalPlan,
                RuntimeFingerprint runtime,
                GenericExecutionSession recoveredSession,
                WorldChangeJournal recoveredJournal,
                Create606WorldResourceBuffer resourceBuffer) {
            this.level = level;
            this.physicalPlan = physicalPlan;
            this.genericSession = recoveredSession;
            this.actionHandler = new Create606BeltPressActionHandler(
                    level,
                    physicalPlan,
                    runtime,
                    recoveredSession.sessionId(),
                    recoveredJournal,
                    recoveredBuildCursor(physicalPlan, recoveredJournal),
                    recoveredPilotFlowCursor(physicalPlan, recoveredJournal),
                    resourceBuffer);
            this.runner = new BoundedStepRunner(
                    Map.of(
                            BeltPressGenericExecutionPlan.ACTION_HANDLER_ID,
                            actionHandler),
                    Map.of(
                            BeltPressGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                            this::evaluatePreflightCondition),
                    BeltPressGenericExecutionPlan.verificationRules(physicalPlan));
            LOGGER.info(
                    "CREATE_BELT_PRESS_RECOVERED session={} currentStep={} completedSteps={} buildCursor={} journalEntries={} resourceEntries=0",
                    recoveredSession.sessionId(),
                    recoveredSession.currentStep().orElseThrow().stepId(),
                    recoveredSession.completedStepIds().size(),
                    recoveredBuildCursor(physicalPlan, recoveredJournal),
                    recoveredJournal.entries().size());
        }

        @Override
        public AdapterResult<RecoveryCheckpoint> recoveryCheckpoint(long savedTick) {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "Belt/press recovery checkpoint must be captured on the authoritative server thread");
            }
            try {
                actionHandler.stabilizeRecoveryAfterStates();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-04 recovery state stabilization was rejected: " + exception.getMessage());
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
                        "C-04 recovery checkpoint was rejected: " + exception.getMessage());
            }
        }

        @Override
        public AdapterResult<BeltPressExecutionUpdate> tick() {
            if (!level.getServer().isSameThread()) {
                return failure(
                        AdapterFailureCode.WRONG_THREAD,
                        "Belt/press execution tick must run on the authoritative server thread");
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
                        "Belt/press preflight area unloaded during execution: "
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
                        "Belt/press cancellation must run on the authoritative server thread");
            }
            if (genericSession.status() != GenericExecutionSessionStatus.RUNNING) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Belt/press session is already terminal: " + genericSession.status());
            }
            StepRunResult result = runner.tick(
                    genericSession,
                    executionTick(level),
                    Optional.of(reason));
            genericSession = result.session();
            if (genericSession.status() != GenericExecutionSessionStatus.CANCELLED) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Generic runner rejected C-04 cancellation: " + result.outcome());
            }
            WorldChangeJournal.RollbackReport rollback = actionHandler.rollback();
            WorldChangeJournal journal = actionHandler.worldChangeJournal();
            LOGGER.info(
                    "CREATE_BELT_PRESS_CANCELLED session={} changes={} modifiedPositions={} restoredPositions={} warnings={} noResourceCompensation=true",
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
                        "C-04 preflight condition parameters must be empty");
            }
            boolean unloaded = actionHandler.firstUnloadedChunk().isPresent();
            if (condition.conditionType().equals(
                    BeltPressGenericExecutionPlan.PREFLIGHT_LOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.error(
                                failureId(AdapterFailureCode.CHUNK_NOT_LOADED),
                                "C-04 preflight area is no longer loaded")
                        : ConditionEvaluation.satisfied();
            }
            if (condition.conditionType().equals(
                    BeltPressGenericExecutionPlan.PREFLIGHT_UNLOADED_CONDITION)) {
                return unloaded
                        ? ConditionEvaluation.satisfied()
                        : ConditionEvaluation.unsatisfied();
            }
            return ConditionEvaluation.error(
                    failureId(AdapterFailureCode.PLAN_REJECTED),
                    "Unknown C-04 preflight condition type " + condition.conditionType());
        }

        private AdapterResult<BeltPressExecutionUpdate> completed() {
            return actionHandler.completedEvidence()
                    .<AdapterResult<BeltPressExecutionUpdate>>map(value ->
                            new AdapterResult.Success<>(
                                    new BeltPressExecutionUpdate.Completed(value)))
                    .orElseGet(() -> failure(
                            AdapterFailureCode.CREATE_API_FAILURE,
                            "Generic session completed without physical belt/press evidence"));
        }

        private AdapterResult<BeltPressExecutionUpdate> terminalFailure() {
            Optional<Create606BeltPressActionHandler.FailureDetail> handlerFailure =
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
                    .orElse("Generic C-04 session ended without typed failure detail");
            return failure(code, detail);
        }

        private AdapterResult<BeltPressExecutionUpdate> progress() {
            GenericExecutionPhase phase = genericSession.currentPhase().orElseThrow();
            return new AdapterResult.Success<>(new BeltPressExecutionUpdate.InProgress(
                    legacyPhase(phase),
                    actionHandler.buildStepIndex(),
                    physicalPlan.buildSteps().size(),
                    executionTick(level)));
        }
    }

    private static String validateRecoveryBoundary(
            BeltPressPlan plan,
            GenericExecutionSession session,
            WorldChangeJournal journal) {
        GenericExecutionPlan trustedPlan = BeltPressGenericExecutionPlan.from(plan);
        if (!RecoveryPlanSnapshot.capture(session.plan()).equals(
                RecoveryPlanSnapshot.capture(trustedPlan))) {
            return "Recovered C-04 generic plan does not match the trusted physical plan";
        }
        if (session.status() != GenericExecutionSessionStatus.RUNNING
                || session.currentStep().isEmpty()) {
            return "C-04 recovery requires one running BUILD or pre-resource POWER step";
        }
        ResourceId currentStep = session.currentStep().orElseThrow().stepId();
        boolean partialBuild = currentStep.equals(BeltPressGenericExecutionPlan.BUILD_STEP_ID)
                && session.completedStepIds().isEmpty();
        boolean buildComplete = currentStep.equals(BeltPressGenericExecutionPlan.POWER_STEP_ID)
                && session.stepRunState() == GenericStepRunState.READY
                && session.completedStepIds().equals(List.of(
                        BeltPressGenericExecutionPlan.BUILD_STEP_ID));
        if (!partialBuild && !buildComplete) {
            return "C-04 can be recovered only at an atomic BUILD boundary or BUILD-complete POWER/READY";
        }
        if (!session.sessionId().equals(journal.sessionId())
                || !session.worldChanges().equals(journal.references())) {
            return "Recovered C-04 session and world-change journal differ";
        }
        List<dev.stevecreate.agent.core.model.BlockPos3i> expectedChanges =
                expectedBuildChangePositions(plan);
        if (journal.entries().size() > expectedChanges.size()
                || journal.entries().stream().anyMatch(entry -> !(entry instanceof BlockChange))) {
            return "C-04 recovery boundary must contain a bounded block-only build prefix";
        }
        List<dev.stevecreate.agent.core.model.BlockPos3i> actualChanges = journal.entries().stream()
                .map(BlockChange.class::cast)
                .map(BlockChange::position)
                .toList();
        if (!actualChanges.equals(expectedChanges.subList(0, actualChanges.size()))) {
            return "Recovered C-04 block-change sequence does not match the physical build prefix";
        }
        if (!journal.modifiedPositions().equals(List.copyOf(new LinkedHashSet<>(actualChanges)))) {
            return "Recovered C-04 modified positions do not match the physical build prefix";
        }
        int cursor = recoveredBuildCursor(plan, journal);
        if (cursor < 0 || (buildComplete && cursor != plan.buildSteps().size())) {
            return "Recovered C-04 journal stops inside a build action or omits completed BUILD work";
        }
        for (WorldChangeJournal.Entry entry : journal.entries()) {
            BlockChange block = (BlockChange) entry;
            if (!block.sourceStepId().equals(BeltPressGenericExecutionPlan.BUILD_STEP_ID)) {
                return "Recovered C-04 block journal contains a non-BUILD change";
            }
        }
        return null;
    }

    private static int recoveredBuildCursor(BeltPressPlan plan, WorldChangeJournal journal) {
        int changes = 0;
        if (journal.entries().isEmpty()) return 0;
        int billableEntries = plan.buildSteps().stream()
                .mapToInt(step -> step instanceof BeltPressBuildStep.PlaceBlock ? 1 : 3)
                .sum();
        if (journal.entries().size() >= billableEntries) return plan.buildSteps().size();
        for (int index = 0; index < plan.buildSteps().size(); index++) {
            changes += plan.buildSteps().get(index) instanceof BeltPressBuildStep.PlaceBlock ? 1 : 3;
            if (changes == journal.entries().size()) return index + 1;
            if (changes > journal.entries().size()) return -1;
        }
        return -1;
    }

    private static int recoveredPilotFlowCursor(
            BeltPressPlan plan, WorldChangeJournal journal) {
        int billableEntries = plan.buildSteps().stream()
                .mapToInt(step -> step instanceof BeltPressBuildStep.PlaceBlock ? 1 : 3)
                .sum();
        int flow = journal.entries().size() - billableEntries;
        if (flow < 0) return 0;
        if (flow > 2) throw new IllegalArgumentException(
                "Recovered C-04 journal exceeds the two bounded water channels");
        return flow;
    }

    private static List<dev.stevecreate.agent.core.model.BlockPos3i> expectedBuildChangePositions(
            BeltPressPlan plan) {
        List<dev.stevecreate.agent.core.model.BlockPos3i> positions = new ArrayList<>();
        for (BeltPressBuildStep step : plan.buildSteps()) {
            if (step instanceof BeltPressBuildStep.PlaceBlock place) {
                positions.add(place.position());
            } else {
                BeltPressBuildStep.ConnectBelt connection = (BeltPressBuildStep.ConnectBelt) step;
                positions.add(connection.startPosition());
                positions.add(plan.placement(BeltPressRole.BELT_PRESSING).position());
                positions.add(connection.endPosition());
            }
        }
        BlockPos3i beltSource = plan.placement(BeltPressRole.BELT_WATER_SOURCE).position();
        positions.add(beltSource.translate(0, -1, 0));
        BlockPos3i pressSource = plan.placement(BeltPressRole.PRESS_WATER_SOURCE).position();
        positions.add(pressSource.translate(0, -1, 0));
        return List.copyOf(positions);
    }

    private static CreatePlanExecutionPhase legacyPhase(GenericExecutionPhase phase) {
        return switch (phase) {
            case BUILD -> CreatePlanExecutionPhase.BUILDING;
            case POWER -> CreatePlanExecutionPhase.AWAITING_POWER;
            case FEED_INPUT -> CreatePlanExecutionPhase.FEEDING;
            case PROCESS -> CreatePlanExecutionPhase.PROCESSING;
            default -> throw new IllegalStateException(
                    "Unexpected C-04 generic execution phase: " + phase);
        };
    }

    private static ResourceId sessionId(ServerLevel level, BeltPressPlan plan) {
        ResourceLocation dimension = level.dimension().location();
        String dimensionPath = dimension.getNamespace() + "_"
                + dimension.getPath().replace('/', '_');
        return new ResourceId(
                "steve_industrial",
                "c04/session/" + dimensionPath + "/"
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
                "v606/failure/" + code.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static <T> AdapterResult<T> failure(
            AdapterFailureCode code,
            String detail) {
        return new AdapterResult.Failure<>(
                code,
                "Create 6.0.6 belt/press executor: " + detail);
    }
}
