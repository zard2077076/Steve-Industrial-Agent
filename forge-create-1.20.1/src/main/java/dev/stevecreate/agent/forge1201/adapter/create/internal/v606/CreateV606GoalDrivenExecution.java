package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionSession;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionUpdate;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionPhase;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionSession;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionUpdate;
import dev.stevecreate.agent.adapter.api.ExecutionCancellationResult;
import dev.stevecreate.agent.adapter.api.RecoverableExecutionSession;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.BoundedStepRunner;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Goal-driven v606 entry that accepts only a complete ExecutionReadyPlan. */
public final class CreateV606GoalDrivenExecution {
    private static final Set<ResourceId> ACTIVE_SESSIONS =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private CreateV606GoalDrivenExecution() {}

    /** Drops process-local ownership when an integrated/dedicated server stops; world recovery is persisted separately. */
    public static void clearServerState() {
        ACTIVE_SESSIONS.clear();
    }

    public static StartResult begin(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i resourceBufferPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(resourceBufferPosition, "resourceBufferPosition");
        if (!level.getServer().isSameThread()) {
            return rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Goal-driven execution must begin on the authoritative server thread");
        }
        if (!ACTIVE_SESSIONS.add(ready.sessionId())) {
            return rejected(ExecutionReadinessFailureCode.SESSION_ALREADY_EXISTS,
                    "The verified root session is already active: " + ready.sessionId());
        }
        try {
            CreateV606ExecutionPlanAdapter.MaterializationResult materialized =
                    new CreateV606ExecutionPlanAdapter().materialize(ready);
            if (materialized instanceof CreateV606ExecutionPlanAdapter.MaterializationFailure failure) {
                ACTIVE_SESSIONS.remove(ready.sessionId());
                return rejected(failure.code(), failure.detail());
            }
            Create606WorldResourceBuffer buffer = new Create606WorldResourceBuffer(
                    level, resourceBufferPosition);
            AdapterResult<Map<ResourceId, Long>> observed = buffer.snapshot();
            if (observed instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
                ACTIVE_SESSIONS.remove(ready.sessionId());
                return rejected(ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING, failure.detail());
            }
            Map<ResourceId, Long> available =
                    ((AdapterResult.Success<Map<ResourceId, Long>>) observed).value();
            Map<ResourceId, Long> required = requiredInputs(ready);
            for (Map.Entry<ResourceId, Long> entry : required.entrySet()) {
                if (available.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                    ACTIVE_SESSIONS.remove(ready.sessionId());
                    return rejected(ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                            "World resource buffer lacks " + entry.getKey() + ": required="
                                    + entry.getValue() + " available="
                                    + available.getOrDefault(entry.getKey(), 0L));
                }
            }
            List<CreateV606ExecutionPlanAdapter.ExecutableNode> nodes =
                    ((CreateV606ExecutionPlanAdapter.MaterializationSuccess) materialized).nodes();
            Rejected targetFailure = validateLiveTargets(level, ready);
            if (targetFailure != null) {
                ACTIVE_SESSIONS.remove(ready.sessionId());
                return targetFailure;
            }
            return new Started(new Session(level, ready, runtime, buffer, nodes));
        } catch (RuntimeException exception) {
            ACTIVE_SESSIONS.remove(ready.sessionId());
            return rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Goal-driven session construction failed closed: " + exception.getMessage());
        }
    }

    public static StartResult resume(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i resourceBufferPosition,
            Resumable resumable) {
        Objects.requireNonNull(resumable, "resumable");
        if (!level.getServer().isSameThread()) {
            return rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Goal-driven recovery must run on the authoritative server thread");
        }
        if (!resumable.session().sessionId().equals(childSessionId(ready.sessionId(), 0))) {
            return rejected(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    "Recovered child session does not belong to the verified root session");
        }
        CreateExecutionWorldGuard.GuardResult guard = CreateExecutionWorldGuard.verify(
                level, dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification
                        .ISOLATED_REPOSITORY_TEST);
        if (guard instanceof CreateExecutionWorldGuard.GuardFailure failure) {
            return rejected(failure.code(), failure.detail());
        }
        if (!ACTIVE_SESSIONS.add(ready.sessionId())) {
            return rejected(ExecutionReadinessFailureCode.SESSION_ALREADY_EXISTS,
                    "The verified root session is already active: " + ready.sessionId());
        }
        try {
            Resumable rebased = resumable.rebaseRecoveryTiming(executionTick(level));
            var materialized = new CreateV606ExecutionPlanAdapter().materialize(ready);
            if (!(materialized instanceof CreateV606ExecutionPlanAdapter.MaterializationSuccess success)
                    || success.nodes().size() != 1) {
                ACTIVE_SESSIONS.remove(ready.sessionId());
                return rejected(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        "Initial goal recovery supports one exactly reconciled process node");
            }
            Create606WorldResourceBuffer buffer = new Create606WorldResourceBuffer(
                    level, resourceBufferPosition);
            Object child;
            var node = success.nodes().get(0);
            if (node instanceof CreateV606ExecutionPlanAdapter.MillstoneNode millstone) {
                AdapterResult<CreatePlanExecutionSession> result =
                        Create606WaterWheelMillstoneExecutor.resumeGoalDriven(
                                level, millstone.plan(), runtime, rebased, buffer);
                if (result instanceof AdapterResult.Failure<CreatePlanExecutionSession> failure) {
                    ACTIVE_SESSIONS.remove(ready.sessionId());
                    return rejected(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                            failure.detail());
                }
                child = ((AdapterResult.Success<CreatePlanExecutionSession>) result).value();
            } else {
                var press = (CreateV606ExecutionPlanAdapter.PressNode) node;
                AdapterResult<BeltPressExecutionSession> result =
                        Create606BeltPressExecutor.resumeGoalDriven(
                                level, press.plan(), runtime, rebased, buffer);
                if (result instanceof AdapterResult.Failure<BeltPressExecutionSession> failure) {
                    ACTIVE_SESSIONS.remove(ready.sessionId());
                    return rejected(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                            failure.detail());
                }
                child = ((AdapterResult.Success<BeltPressExecutionSession>) result).value();
            }
            Session session = new Session(level, ready, runtime, buffer, success.nodes());
            session.child = child;
            session.trace.add("execution:recovered_child=" + rebased.session().sessionId()
                    + ",timeoutRebasedAt=" + rebased.savedTick());
            return new Started(session);
        } catch (RuntimeException exception) {
            ACTIVE_SESSIONS.remove(ready.sessionId());
            return rejected(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    "Goal-driven recovery failed closed: " + exception.getMessage());
        }
    }

    public static ReconcileResult reconcileReload(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RecoveryCheckpoint checkpoint) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            var materialized = new CreateV606ExecutionPlanAdapter().materialize(ready);
            if (!(materialized instanceof CreateV606ExecutionPlanAdapter.MaterializationSuccess success)
                    || success.nodes().size() != 1) {
                return new ReconcileRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        "Initial goal recovery requires one trusted process node");
            }
            var genericPlan = success.nodes().get(0).genericPlan();
            SessionRecoveryReconciler.Discovery discovery = SessionRecoveryReconciler.discover(
                    checkpoint, Map.of(genericPlan.planId(), genericPlan));
            if (!(discovery instanceof SessionRecoveryReconciler.RescanRequired required)) {
                var stale = (SessionRecoveryReconciler.StaleSession) discovery;
                return new ReconcileRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        stale.detail());
            }
            var initial = required.reconcile(Map.of());
            if (!(initial instanceof SessionRecoveryReconciler.StaleSession)) {
                return new ReconcileRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        "Recovery did not require an exact world rescan");
            }
            Create606WorldChangeJournal snapshots = new Create606WorldChangeJournal(
                    level, checkpoint.session().sessionId());
            Map<BlockPos3i, WorldBlockSnapshot> observed = new LinkedHashMap<>();
            for (BlockPos3i position : required.requiredPositions()) {
                BlockPos block = new BlockPos(position.x(), position.y(), position.z());
                if (!level.hasChunkAt(block)) {
                    return new ReconcileRefused(ExecutionReadinessFailureCode.REQUIRED_CHUNK_UNLOADED,
                            "Recovery rescan chunk is unloaded at " + position);
                }
                observed.put(position, snapshots.capture(block));
            }
            var reconciled = required.reconcile(observed);
            if (reconciled instanceof Resumable resumable) {
                return new ReconcileReady(resumable);
            }
            var stale = (SessionRecoveryReconciler.StaleSession) reconciled;
            return new ReconcileRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    stale.detail());
        } catch (RuntimeException exception) {
            return new ReconcileRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    "Recovery reconciliation failed closed: " + exception.getMessage());
        }
    }

    public static VerifyRecoveryResult recoverVerify(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i resourceBufferPosition,
            List<WorldChangeJournal> journals) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(resourceBufferPosition, "resourceBufferPosition");
        journals = List.copyOf(Objects.requireNonNull(journals, "journals"));
        if (!level.getServer().isSameThread()) {
            return new VerifyRecoveryRefused(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "VERIFY recovery must run on the authoritative server thread");
        }
        CreateExecutionWorldGuard.GuardResult guard = CreateExecutionWorldGuard.verify(
                level, dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification
                        .ISOLATED_REPOSITORY_TEST);
        if (guard instanceof CreateExecutionWorldGuard.GuardFailure failure) {
            return new VerifyRecoveryRefused(failure.code(), failure.detail());
        }
        try {
            var materialized = new CreateV606ExecutionPlanAdapter().materialize(ready);
            if (!(materialized instanceof CreateV606ExecutionPlanAdapter.MaterializationSuccess success)
                    || success.nodes().size() != 1) {
                return new VerifyRecoveryRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        "VERIFY recovery supports one exactly verified process node");
            }
            Create606WorldResourceBuffer buffer = new Create606WorldResourceBuffer(
                    level, resourceBufferPosition);
            var goal = ready.physicalPlan().candidate().boundPlan().graph()
                    .logicalPlan().candidate().goal();
            AdapterResult<Map<ResourceId, Long>> beforeResult = buffer.snapshot();
            if (beforeResult instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
                return new VerifyRecoveryRefused(
                        ExecutionReadinessFailureCode.OUTPUT_NOT_PRODUCED, failure.detail());
            }
            Map<ResourceId, Long> before =
                    ((AdapterResult.Success<Map<ResourceId, Long>>) beforeResult).value();
            boolean alreadyCollected = before.getOrDefault(goal.target(), 0L) >= goal.quantity();
            if (!alreadyCollected) {
                var node = success.nodes().get(0);
                AdapterResult<Integer> collected = node
                        instanceof CreateV606ExecutionPlanAdapter.MillstoneNode millstone
                        ? buffer.collectMillstoneOutput(millstone.plan())
                        : buffer.collectPressOutput(
                                ((CreateV606ExecutionPlanAdapter.PressNode) node).plan());
                if (collected instanceof AdapterResult.Failure<Integer> failure) {
                    return new VerifyRecoveryRefused(
                            ExecutionReadinessFailureCode.OUTPUT_NOT_PRODUCED, failure.detail());
                }
            }
            AdapterResult<Map<ResourceId, Long>> afterResult = buffer.snapshot();
            if (afterResult instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
                return new VerifyRecoveryRefused(
                        ExecutionReadinessFailureCode.OUTPUT_NOT_PRODUCED, failure.detail());
            }
            Map<ResourceId, Long> after =
                    ((AdapterResult.Success<Map<ResourceId, Long>>) afterResult).value();
            long observed = after.getOrDefault(goal.target(), 0L);
            if (observed < goal.quantity()) {
                return new VerifyRecoveryRefused(
                        ExecutionReadinessFailureCode.OUTPUT_QUANTITY_MISMATCH,
                        "Recovered VERIFY output=" + observed + " required=" + goal.quantity());
            }
            List<String> trace = new ArrayList<>(ready.trace());
            trace.add("execution:verify_recovered=true");
            trace.add("execution:verify_already_collected=" + alreadyCollected);
            trace.add("execution:goal_verified=" + goal.target() + "x" + observed);
            return new VerifyRecovered(new Completed(
                    ready.sessionId(), goal.target(), goal.quantity(), observed,
                    after, journals, trace), alreadyCollected);
        } catch (RuntimeException exception) {
            return new VerifyRecoveryRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    "VERIFY recovery failed closed: " + exception.getMessage());
        }
    }

    public sealed interface StartResult permits Started, Rejected {}

    public sealed interface VerifyRecoveryResult permits VerifyRecovered, VerifyRecoveryRefused {}

    public record VerifyRecovered(Completed completed, boolean alreadyCollected)
            implements VerifyRecoveryResult {
        public VerifyRecovered { Objects.requireNonNull(completed, "completed"); }
    }

    public record VerifyRecoveryRefused(
            ExecutionReadinessFailureCode code,
            String detail) implements VerifyRecoveryResult {
        public VerifyRecoveryRefused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record Started(Session session) implements StartResult {
        public Started { Objects.requireNonNull(session, "session"); }
    }

    public record Rejected(
            ExecutionReadinessFailureCode code,
            String detail) implements StartResult {
        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank() || detail.length() > 2_048) {
                throw new IllegalArgumentException("Execution rejection detail is blank or too long");
            }
        }
    }

    public enum Phase { BUILD, CONNECT, FEED, PROCESS, VERIFY, COMPLETED }

    public sealed interface TickResult permits Progress, Completed, Failed {}

    public record Progress(
            ResourceId rootSessionId,
            int processNodeIndex,
            int processNodeCount,
            Phase phase,
            int maximumHandlerInvocationsThisTick,
            long gameTick) implements TickResult {
        public Progress {
            Objects.requireNonNull(rootSessionId, "rootSessionId");
            Objects.requireNonNull(phase, "phase");
            if (processNodeIndex < 0 || processNodeIndex >= processNodeCount
                    || maximumHandlerInvocationsThisTick < 0
                    || maximumHandlerInvocationsThisTick > BoundedStepRunner.MAX_ACTION_INVOCATIONS_PER_TICK
                    || gameTick < 0) {
                throw new IllegalArgumentException("Invalid bounded goal-driven progress");
            }
        }
    }

    public record Completed(
            ResourceId rootSessionId,
            ResourceId target,
            long requiredQuantity,
            long observedQuantity,
            Map<ResourceId, Long> finalResourceBuffer,
            List<WorldChangeJournal> journals,
            List<String> trace) implements TickResult {
        public Completed {
            Objects.requireNonNull(rootSessionId, "rootSessionId");
            Objects.requireNonNull(target, "target");
            finalResourceBuffer = Map.copyOf(finalResourceBuffer);
            journals = List.copyOf(journals);
            trace = List.copyOf(trace);
            if (requiredQuantity < 1 || observedQuantity < requiredQuantity || trace.isEmpty()) {
                throw new IllegalArgumentException("Completed goal evidence is incomplete");
            }
        }
    }

    public record Failed(
            ResourceId rootSessionId,
            ExecutionReadinessFailureCode code,
            String detail,
            List<String> trace) implements TickResult {
        public Failed {
            Objects.requireNonNull(rootSessionId, "rootSessionId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            trace = List.copyOf(trace);
            if (detail.isBlank() || trace.isEmpty()) {
                throw new IllegalArgumentException("Execution failure evidence is incomplete");
            }
        }
    }

    public record Cancellation(
            ResourceId rootSessionId,
            ExecutionReadinessFailureCode code,
            List<WorldChangeJournal> journals,
            List<String> trace) {
        public Cancellation {
            Objects.requireNonNull(rootSessionId, "rootSessionId");
            if (code != ExecutionReadinessFailureCode.EXECUTION_CANCELLED) {
                throw new IllegalArgumentException("Cancellation must use EXECUTION_CANCELLED");
            }
            journals = List.copyOf(journals);
            trace = List.copyOf(trace);
        }
    }

    public sealed interface ReloadResult permits ReloadReady, ReloadRefused {}

    public sealed interface ReconcileResult permits ReconcileReady, ReconcileRefused {}
    public record ReconcileReady(Resumable resumable) implements ReconcileResult {}
    public record ReconcileRefused(
            ExecutionReadinessFailureCode code,
            String detail) implements ReconcileResult {}

    public record ReloadReady(
            RecoveryCheckpoint checkpoint,
            List<String> trace) implements ReloadResult {
        public ReloadReady {
            Objects.requireNonNull(checkpoint, "checkpoint");
            trace = List.copyOf(trace);
        }
    }

    public record ReloadRefused(
            ExecutionReadinessFailureCode code,
            String detail,
            List<String> trace) implements ReloadResult {
        public ReloadRefused {
            if (code != ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE) {
                throw new IllegalArgumentException("Reload refusal must be RELOAD_RECOVERY_UNSAFE");
            }
            Objects.requireNonNull(detail, "detail");
            trace = List.copyOf(trace);
        }
    }

    public static final class Session {
        private final ServerLevel level;
        private final ExecutionReadyPlan ready;
        private final RuntimeFingerprint runtime;
        private final Create606WorldResourceBuffer buffer;
        private final List<CreateV606ExecutionPlanAdapter.ExecutableNode> nodes;
        private final Create606PhysicalItemRouteBuilder routeBuilder;
        private final List<WorldChangeJournal> journals = new ArrayList<>();
        private final List<String> trace;
        private int nodeIndex;
        private Object child;
        private boolean outputCollectionPending;
        private boolean routeJournalRecorded;
        private boolean terminal;
        private TickResult terminalResult;

        private Session(
                ServerLevel level,
                ExecutionReadyPlan ready,
                RuntimeFingerprint runtime,
                Create606WorldResourceBuffer buffer,
                List<CreateV606ExecutionPlanAdapter.ExecutableNode> nodes) {
            this.level = level;
            this.ready = ready;
            this.runtime = runtime;
            this.buffer = buffer;
            this.nodes = List.copyOf(nodes);
            this.routeBuilder = new Create606PhysicalItemRouteBuilder(
                    level, ready.sessionId(), ready.physicalPlan().routes());
            this.trace = new ArrayList<>(ready.trace());
            this.trace.add("execution:adapter=create-v606");
            this.trace.add("execution:resource_buffer=" + buffer.position());
            this.trace.add("execution:process_nodes=" + nodes.size());
            this.trace.add("execution:item_route_cells=" + routeBuilder.cellCount());
        }

        public TickResult tick() {
            if (terminal) return terminalResult;
            if (!level.getServer().isSameThread()) {
                return fail(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Goal-driven tick left the authoritative server thread");
            }
            if (outputCollectionPending) return collectOutput();
            if (nodeIndex > 0 && !routeBuilder.complete()) {
                Create606PhysicalItemRouteBuilder.BuildResult route = routeBuilder.tick();
                if (route instanceof Create606PhysicalItemRouteBuilder.RouteFailure failure) {
                    return fail(failure.code(), failure.detail());
                }
                if (route instanceof Create606PhysicalItemRouteBuilder.RouteComplete complete) {
                    recordRouteJournal();
                    trace.add("execution:item_routes_constructed=" + complete.total());
                }
                return progress(Phase.CONNECT, 0);
            }
            if (nodeIndex >= nodes.size()) return finishGoal();
            if (child == null) {
                Rejected failure = startChild();
                if (failure != null) return fail(failure.code(), failure.detail());
            }
            if (child instanceof CreatePlanExecutionSession millstone) {
                AdapterResult<CreatePlanExecutionUpdate> result = millstone.tick();
                if (result instanceof AdapterResult.Failure<CreatePlanExecutionUpdate> failure) {
                    return fail(map(failure.code(), failure.detail()), failure.detail());
                }
                CreatePlanExecutionUpdate update =
                        ((AdapterResult.Success<CreatePlanExecutionUpdate>) result).value();
                if (update instanceof CreatePlanExecutionUpdate.Completed) {
                    journals.add(millstone.worldChangeJournal());
                    outputCollectionPending = true;
                    return progress(Phase.VERIFY, 1);
                }
                return progress(phase(((CreatePlanExecutionUpdate.InProgress) update).phase()), 1);
            }
            BeltPressExecutionSession press = (BeltPressExecutionSession) child;
            AdapterResult<BeltPressExecutionUpdate> result = press.tick();
            if (result instanceof AdapterResult.Failure<BeltPressExecutionUpdate> failure) {
                return fail(map(failure.code(), failure.detail()), failure.detail());
            }
            BeltPressExecutionUpdate update =
                    ((AdapterResult.Success<BeltPressExecutionUpdate>) result).value();
            if (update instanceof BeltPressExecutionUpdate.Completed) {
                journals.add(press.worldChangeJournal());
                outputCollectionPending = true;
                return progress(Phase.VERIFY, 1);
            }
            return progress(phase(((BeltPressExecutionUpdate.InProgress) update).phase()), 1);
        }

        public Cancellation cancel(ResourceId reason) {
            Objects.requireNonNull(reason, "reason");
            if (terminal) throw new IllegalStateException("Goal-driven session is terminal");
            if (child instanceof CreatePlanExecutionSession millstone) {
                AdapterResult<ExecutionCancellationResult> result = millstone.cancel(reason);
                if (result instanceof AdapterResult.Success<ExecutionCancellationResult> success) {
                    journals.add(success.value().journal());
                }
            } else if (child instanceof BeltPressExecutionSession press) {
                AdapterResult<ExecutionCancellationResult> result = press.cancel(reason);
                if (result instanceof AdapterResult.Success<ExecutionCancellationResult> success) {
                    journals.add(success.value().journal());
                }
            }
            if (!routeBuilder.journal().entries().isEmpty()) {
                routeBuilder.rollback();
                if (!routeJournalRecorded) journals.add(routeBuilder.journal());
            }
            trace.add("execution:cancelled=" + reason);
            terminal = true;
            ACTIVE_SESSIONS.remove(ready.sessionId());
            terminalResult = new Failed(ready.sessionId(),
                    ExecutionReadinessFailureCode.EXECUTION_CANCELLED,
                    "Goal-driven execution was cancelled: " + reason, trace);
            return new Cancellation(ready.sessionId(),
                    ExecutionReadinessFailureCode.EXECUTION_CANCELLED, journals, trace);
        }

        public ResourceId rootSessionId() { return ready.sessionId(); }
        public int processNodeIndex() { return nodeIndex; }
        public List<String> trace() { return List.copyOf(trace); }
        public List<WorldChangeJournal> journals() { return List.copyOf(journals); }

        public List<WorldChangeJournal> journalsSnapshot() {
            List<WorldChangeJournal> snapshot = new ArrayList<>(journals);
            if (child instanceof CreatePlanExecutionSession millstone) {
                WorldChangeJournal journal = millstone.worldChangeJournal();
                if (!journal.entries().isEmpty() && !snapshot.contains(journal)) snapshot.add(journal);
            } else if (child instanceof BeltPressExecutionSession press) {
                WorldChangeJournal journal = press.worldChangeJournal();
                if (!journal.entries().isEmpty() && !snapshot.contains(journal)) snapshot.add(journal);
            }
            if (!routeBuilder.journal().entries().isEmpty()
                    && !snapshot.contains(routeBuilder.journal())) {
                snapshot.add(routeBuilder.journal());
            }
            return List.copyOf(snapshot);
        }

        public ReloadResult captureReloadCheckpoint() {
            if (terminal || child == null || !(child instanceof RecoverableExecutionSession recoverable)) {
                return new ReloadRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        "No active recoverable child is at a pre-resource boundary", trace);
            }
            AdapterResult<RecoveryCheckpoint> result = recoverable.recoveryCheckpoint(executionTick(level));
            if (result instanceof AdapterResult.Failure<RecoveryCheckpoint> failure) {
                return new ReloadRefused(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                        failure.detail(), trace);
            }
            RecoveryCheckpoint checkpoint =
                    ((AdapterResult.Success<RecoveryCheckpoint>) result).value();
            return new ReloadReady(checkpoint, trace);
        }

        public ReloadResult prepareReload() {
            ReloadResult captured = captureReloadCheckpoint();
            if (!(captured instanceof ReloadReady readyCheckpoint)) return captured;
            RecoveryCheckpoint checkpoint = readyCheckpoint.checkpoint();
            trace.add("execution:reload_checkpoint=" + checkpoint.session().sessionId());
            terminal = true;
            ACTIVE_SESSIONS.remove(ready.sessionId());
            terminalResult = new Failed(ready.sessionId(),
                    ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    "Detached after a safe BUILD-complete reload checkpoint", trace);
            return new ReloadReady(checkpoint, trace);
        }

        private Rejected startChild() {
            CreateV606ExecutionPlanAdapter.ExecutableNode node = nodes.get(nodeIndex);
            ResourceId childId = childSessionId(ready.sessionId(), nodeIndex);
            if (node instanceof CreateV606ExecutionPlanAdapter.MillstoneNode millstone) {
                AdapterResult<CreatePlanExecutionSession> result =
                        Create606WaterWheelMillstoneExecutor.beginGoalDriven(
                                level, millstone.plan(), runtime, childId, buffer);
                if (result instanceof AdapterResult.Failure<CreatePlanExecutionSession> failure) {
                    return rejected(map(failure.code(), failure.detail()), failure.detail());
                }
                child = ((AdapterResult.Success<CreatePlanExecutionSession>) result).value();
            } else {
                CreateV606ExecutionPlanAdapter.PressNode press =
                        (CreateV606ExecutionPlanAdapter.PressNode) node;
                AdapterResult<BeltPressExecutionSession> result =
                        Create606BeltPressExecutor.beginGoalDriven(
                                level, press.plan(), runtime, childId, buffer);
                if (result instanceof AdapterResult.Failure<BeltPressExecutionSession> failure) {
                    return rejected(map(failure.code(), failure.detail()), failure.detail());
                }
                child = ((AdapterResult.Success<BeltPressExecutionSession>) result).value();
            }
            trace.add("execution:node_start=" + node.boundNode().logicalNodeId()
                    + ",recipe=" + node.boundNode().recipeId()
                    + ",implementation=" + node.boundNode().implementationId()
                    + ",anchor=" + node.physicalPlacement().anchor()
                    + ",orientation=" + node.physicalPlacement().orientation());
            return null;
        }

        private TickResult collectOutput() {
            CreateV606ExecutionPlanAdapter.ExecutableNode node = nodes.get(nodeIndex);
            AdapterResult<Integer> collected = node instanceof CreateV606ExecutionPlanAdapter.MillstoneNode millstone
                    ? buffer.collectMillstoneOutput(millstone.plan())
                    : buffer.collectPressOutput(((CreateV606ExecutionPlanAdapter.PressNode) node).plan());
            if (collected instanceof AdapterResult.Failure<Integer> failure) {
                return fail(map(failure.code(), failure.detail()), failure.detail());
            }
            int amount = ((AdapterResult.Success<Integer>) collected).value();
            trace.add("execution:node_output=" + node.boundNode().quantityConversion()
                    .outputs().get(0).resourceId() + "x" + amount);
            nodeIndex++;
            child = null;
            outputCollectionPending = false;
            if (nodeIndex < nodes.size() || !routeBuilder.complete()) {
                return progress(Phase.CONNECT, 0);
            }
            return finishGoal();
        }

        private TickResult finishGoal() {
            AdapterResult<Map<ResourceId, Long>> observed = buffer.snapshot();
            if (observed instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
                return fail(ExecutionReadinessFailureCode.OUTPUT_NOT_PRODUCED, failure.detail());
            }
            Map<ResourceId, Long> values =
                    ((AdapterResult.Success<Map<ResourceId, Long>>) observed).value();
            var goal = ready.physicalPlan().candidate().boundPlan().graph()
                    .logicalPlan().candidate().goal();
            long output = values.getOrDefault(goal.target(), 0L);
            if (output < goal.quantity()) {
                return fail(ExecutionReadinessFailureCode.OUTPUT_QUANTITY_MISMATCH,
                        "Goal output quantity=" + output + " required=" + goal.quantity());
            }
            trace.add("execution:goal_verified=" + goal.target() + "x" + output);
            terminal = true;
            ACTIVE_SESSIONS.remove(ready.sessionId());
            terminalResult = new Completed(ready.sessionId(), goal.target(), goal.quantity(),
                    output, values, journals, trace);
            return terminalResult;
        }

        private void recordRouteJournal() {
            if (!routeJournalRecorded && !routeBuilder.journal().entries().isEmpty()) {
                journals.add(routeBuilder.journal());
                routeJournalRecorded = true;
            }
        }

        private Progress progress(Phase phase, int invocations) {
            int boundedIndex = Math.min(nodeIndex, nodes.size() - 1);
            return new Progress(ready.sessionId(), boundedIndex, nodes.size(), phase,
                    invocations, executionTick(level));
        }

        private Failed fail(ExecutionReadinessFailureCode code, String detail) {
            trace.add("execution:failure=" + code + ",detail=" + detail);
            terminal = true;
            ACTIVE_SESSIONS.remove(ready.sessionId());
            terminalResult = new Failed(ready.sessionId(), code, detail, trace);
            return (Failed) terminalResult;
        }
    }

    private static long executionTick(ServerLevel level) {
        return Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                ? Integer.toUnsignedLong(level.getServer().getTickCount())
                : level.getGameTime();
    }

    private static Map<ResourceId, Long> requiredInputs(ExecutionReadyPlan ready) {
        Map<ResourceId, Long> required = new LinkedHashMap<>();
        var candidate = ready.physicalPlan().candidate().boundPlan().graph().logicalPlan().candidate();
        java.util.stream.Stream.concat(candidate.rawMaterials().stream(), candidate.ownedResourcesUsed().stream())
                .forEach(value -> required.merge(value.resourceId(), value.amount(), Math::addExact));
        return required;
    }

    private static Rejected validateLiveTargets(ServerLevel level, ExecutionReadyPlan ready) {
        Set<BlockPos3i> targets = new LinkedHashSet<>();
        ready.physicalPlan().placements().forEach(placement -> placement.components()
                .forEach(component -> targets.add(component.position())));
        ready.physicalPlan().routes().forEach(route -> targets.addAll(route.positions()));
        for (BlockPos3i target : targets) {
            BlockPos position = new BlockPos(target.x(), target.y(), target.z());
            if (!level.hasChunkAt(position)) {
                return rejected(ExecutionReadinessFailureCode.REQUIRED_CHUNK_UNLOADED,
                        "Verified target chunk is no longer loaded at " + target);
            }
            if (!level.getBlockState(position).canBeReplaced()) {
                return rejected(ExecutionReadinessFailureCode.BLOCK_PLACEMENT_BLOCKED,
                        "Verified target changed before execution at " + target);
            }
        }
        return null;
    }

    private static ResourceId childSessionId(ResourceId root, int index) {
        return new ResourceId(root.namespace(), root.path() + "/node_" + index);
    }

    private static Phase phase(CreatePlanExecutionPhase value) {
        return switch (value) {
            case BUILDING -> Phase.BUILD;
            case AWAITING_POWER -> Phase.CONNECT;
            case FEEDING -> Phase.FEED;
            case PROCESSING -> Phase.PROCESS;
        };
    }

    private static ExecutionReadinessFailureCode map(AdapterFailureCode code, String detail) {
        if (code == AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN) {
            return ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN;
        }
        if (code == AdapterFailureCode.CHUNK_NOT_LOADED) {
            return ExecutionReadinessFailureCode.REQUIRED_CHUNK_UNLOADED;
        }
        if (code == AdapterFailureCode.EXECUTION_TIMEOUT) {
            return ExecutionReadinessFailureCode.PROCESS_TIMEOUT;
        }
        if (code == AdapterFailureCode.PLACEMENT_FAILED || code == AdapterFailureCode.PLAN_REJECTED) {
            return ExecutionReadinessFailureCode.BLOCK_PLACEMENT_BLOCKED;
        }
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("overstress")) {
            return ExecutionReadinessFailureCode.STRESS_CAPACITY_INSUFFICIENT;
        }
        if (lower.contains("power") || lower.contains("kinetic") || lower.contains("speed")) {
            return ExecutionReadinessFailureCode.POWER_SOURCE_MISSING;
        }
        if (lower.contains("input") && (lower.contains("lack") || lower.contains("missing"))) {
            return ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING;
        }
        if (lower.contains("output") && lower.contains("quantity")) {
            return ExecutionReadinessFailureCode.OUTPUT_QUANTITY_MISMATCH;
        }
        if (lower.contains("output")) return ExecutionReadinessFailureCode.OUTPUT_NOT_PRODUCED;
        return ExecutionReadinessFailureCode.EXECUTION_NOT_READY;
    }

    private static Rejected rejected(ExecutionReadinessFailureCode code, String detail) {
        return new Rejected(code, detail);
    }
}
