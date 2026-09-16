package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.RetryPolicy;
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
import dev.stevecreate.agent.core.execution.construction.DirectWorldExecutor;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidence;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidenceKind;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ModeCapabilityDeclaration;
import dev.stevecreate.agent.core.execution.construction.RecoveryPolicy;
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskConditionKind;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskFailure;
import dev.stevecreate.agent.core.execution.construction.TaskFailureCategory;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskOwnership;
import dev.stevecreate.agent.core.execution.construction.TaskPostcondition;
import dev.stevecreate.agent.core.execution.construction.TaskSourceKind;
import dev.stevecreate.agent.core.execution.construction.VerifiedPlanTaskSource;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact v606 binding of the accepted root session to the loader-neutral Direct executor. */
final class CreateV606DirectWorldExecutor {
    static final ResourceId EXECUTOR_ID = id("construction:direct_world_v606");
    static final ResourceId BOUNDED_WORLD_ACTION = id("construction:bounded_server_world_action");
    private static final ResourceId CAPABILITY_ID = id("construction:create_goal_driven_v606");
    private static final ResourceId IMPLEMENTATION_ID = id("construction:create_v606_root_session");
    private static final ResourceId ADAPTER_ID = id("construction:forge_create_v606");
    private static final ResourceId MINIMUM_QUANTITY = id("construction:minimum_quantity");
    private static final ResourceId OBSERVED_QUANTITY = id("construction:observed_quantity");
    private static final long OWNERSHIP_TICKS = ConstructionTask.MAXIMUM_TASK_TICKS;

    private final ConstructionTaskGraph graph;
    private final ConstructionTask task;
    private final TaskAssignment assignment;
    private final DirectWorldExecutor executor;
    private final BoundedRootBackend backend;
    private final TaskPostcondition outputPostcondition;
    private boolean started;
    private TaskExecutionResult lastResult;

    CreateV606DirectWorldExecutor(
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            List<CreateV606ExecutionPlanAdapter.ExecutableNode> nodes,
            long assignedTick,
            BoundedRootBackend backend) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Direct root binding requires executable nodes");
        }
        this.backend = Objects.requireNonNull(backend, "backend");
        var goal = ready.physicalPlan().candidate().boundPlan().graph()
                .logicalPlan().candidate().goal();
        ResourceId taskId = childId(ready.sessionId(), "direct_goal");
        ResourceId postconditionId = childId(ready.sessionId(), "direct_goal_output");
        this.outputPostcondition = new TaskPostcondition(
                postconditionId,
                TaskConditionKind.OUTPUT_PRESENT,
                goal.target(),
                ExecutionEvidenceKind.OUTPUT_VERIFIED,
                Map.of(MINIMUM_QUANTITY, Long.toString(goal.quantity())));
        ResourceId placementReservationId = childId(ready.sessionId(), "direct_placement_reservation");
        ResourceId materialReservationId = childId(ready.sessionId(), "direct_material_reservation");
        this.task = new ConstructionTask(
                taskId,
                new VerifiedPlanTaskSource(
                        ready.physicalPlan().id(),
                        TaskSourceKind.VERIFIED_PLACEMENT,
                        nodes.get(0).physicalPlacement().logicalNodeId(),
                        0),
                CAPABILITY_ID,
                TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.CREATE_MACHINE,
                Set.of(ExecutionMode.DIRECT),
                List.of(),
                List.of(outputPostcondition),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                new CleanupPolicy(
                        CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                        true,
                        true,
                        CleanupPolicy.MAX_CLEANUP_MUTATIONS),
                Set.of(placementReservationId),
                Set.of(materialReservationId),
                Set.of(),
                OWNERSHIP_TICKS,
                Map.of(
                        id("construction:verified_physical_plan"), ready.physicalPlan().id().toString(),
                        id("construction:process_node_count"), Integer.toString(nodes.size()),
                        id("construction:route_count"), Integer.toString(ready.physicalPlan().routes().size())));
        CapabilityExecutionDescriptor descriptor = descriptor(nodes.get(0).boundNode().runtimeFingerprint());
        this.graph = new ConstructionTaskGraph(
                childId(ready.sessionId(), "direct_graph"),
                ready.physicalPlan().id(),
                descriptor.runtimeFingerprint(),
                ready.physicalPlan().candidate().snapshotFingerprint(),
                List.of(descriptor),
                List.of(task),
                List.of());
        TaskOwnership ownership = new TaskOwnership(
                ready.sessionId(),
                graph.graphId(),
                task.taskId(),
                EXECUTOR_ID,
                ExecutionMode.DIRECT,
                Optional.empty(),
                0,
                assignedTick,
                Math.addExact(assignedTick, OWNERSHIP_TICKS + 1),
                sha256(ready.sessionId() + "|" + graph.graphId() + "|" + task.taskId()));
        this.assignment = TaskAssignment.assign(
                graph,
                task.taskId(),
                childId(ready.sessionId(), "direct_assignment"),
                ownership,
                1,
                assignedTick);
        this.executor = new DirectWorldExecutor(
                EXECUTOR_ID,
                Set.of(BOUNDED_WORLD_ACTION),
                this::executeBackend);
    }

    Dispatch tick(long currentTick) {
        ConstructionExecutionCommand command = started
                ? ConstructionExecutionCommand.CONTINUE
                : ConstructionExecutionCommand.START;
        started = true;
        TaskExecutionResult result = executor.execute(
                graph,
                task,
                assignment,
                context(command, currentTick, Optional.empty()));
        lastResult = result;
        return new Dispatch(result, backend.lastTickResult(), Optional.empty());
    }

    Dispatch cancel(long currentTick, ResourceId reason) {
        Objects.requireNonNull(reason, "reason");
        TaskExecutionResult result = executor.execute(
                graph,
                task,
                assignment,
                context(ConstructionExecutionCommand.CANCEL, currentTick, Optional.of(reason)));
        lastResult = result;
        return new Dispatch(result, backend.lastTickResult(), backend.lastCancellation());
    }

    ConstructionTaskGraph graph() {
        return graph;
    }

    TaskAssignment assignment() {
        return assignment;
    }

    Optional<TaskExecutionResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    private ConstructionExecutionContext context(
            ConstructionExecutionCommand command,
            long currentTick,
            Optional<ResourceId> reason) {
        List<ExecutionEvidence> evidence = lastResult == null ? List.of() : lastResult.evidence();
        Optional<TaskFailure> failure = lastResult == null ? Optional.empty() : lastResult.failure();
        return new ConstructionExecutionContext(command, currentTick, evidence, failure, reason);
    }

    private TaskExecutionResult executeBackend(
            ConstructionTaskGraph ignoredGraph,
            ConstructionTask ignoredTask,
            TaskAssignment ignoredAssignment,
            ConstructionExecutionContext context) {
        if (context.command() == ConstructionExecutionCommand.CANCEL) {
            CreateV606GoalDrivenExecution.Cancellation cancellation =
                    backend.cancel(context.reason().orElseThrow());
            TaskFailure failure = new TaskFailure(
                    ConstructionFailureCode.CANCELLED.id(),
                    TaskFailureCategory.CANCELLED,
                    false,
                    Optional.empty(),
                    Optional.of(task.taskId()),
                    "Direct v606 root execution was cancelled",
                    List.of(assignment.assignmentId()),
                    "Inspect the exact journals and rollback reports before starting another session");
            ExecutionEvidence evidence = new ExecutionEvidence(
                    childId(assignment.assignmentId(), "cancelled"),
                    childId(task.taskId(), "cancelled"),
                    assignment.sessionId(),
                    assignment.graphId(),
                    assignment.taskId(),
                    assignment.assignmentId(),
                    assignment.executorId(),
                    assignment.mode(),
                    ExecutionEvidenceKind.CANCELLATION_CONFIRMED,
                    cancellation.rootSessionId(),
                    Map.of(id("construction:state"), "cancelled"),
                    Map.of(id("construction:state"), "cancelled"),
                    context.currentTick(),
                    true,
                    "create-v606:typed-cancel-and-journal-rollback");
            return TaskExecutionResult.cancelled(
                    task, assignment, context.currentTick(), List.of(evidence), failure,
                    "Direct v606 cancellation completed through the existing typed journal path");
        }
        BoundedRootUpdate update = backend.tick();
        CreateV606GoalDrivenExecution.TickResult raw = update.tickResult();
        if (raw instanceof CreateV606GoalDrivenExecution.Progress progress) {
            return TaskExecutionResult.pending(
                    task,
                    assignment,
                    context.currentTick(),
                    List.of(),
                    update.worldMutationCount(),
                    update.materialMutationCount(),
                    "Direct v606 bounded progress phase=" + progress.phase());
        }
        if (raw instanceof CreateV606GoalDrivenExecution.Completed completed) {
            ExecutionEvidence evidence = new ExecutionEvidence(
                    childId(assignment.assignmentId(), "output"),
                    outputPostcondition.conditionId(),
                    assignment.sessionId(),
                    assignment.graphId(),
                    assignment.taskId(),
                    assignment.assignmentId(),
                    assignment.executorId(),
                    assignment.mode(),
                    outputPostcondition.requiredEvidenceKind(),
                    outputPostcondition.subjectId(),
                    Map.of(
                            MINIMUM_QUANTITY, Long.toString(completed.requiredQuantity()),
                            OBSERVED_QUANTITY, Long.toString(completed.observedQuantity())),
                    outputPostcondition.expectedValues(),
                    context.currentTick(),
                    true,
                    "create-v606:real-resource-buffer-readback");
            return TaskExecutionResult.success(
                    task,
                    assignment,
                    context.currentTick(),
                    List.of(evidence),
                    update.worldMutationCount(),
                    update.materialMutationCount(),
                    "Direct v606 goal output verified from the real resource buffer");
        }
        CreateV606GoalDrivenExecution.Failed failed =
                (CreateV606GoalDrivenExecution.Failed) raw;
        TaskFailure failure = mapFailure(failed, assignment);
        return TaskExecutionResult.failure(
                task,
                assignment,
                context.currentTick(),
                List.of(),
                failure,
                update.worldMutationCount(),
                update.materialMutationCount(),
                failed.detail());
    }

    private static CapabilityExecutionDescriptor descriptor(String runtimeFingerprint) {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, new ModeCapabilityDeclaration(
                ExecutionMode.DIRECT,
                CapabilitySupport.SUPPORTED,
                Set.of(BOUNDED_WORLD_ACTION),
                ""));
        modes.put(ExecutionMode.BOTS, new ModeCapabilityDeclaration(
                ExecutionMode.BOTS,
                CapabilitySupport.UNSUPPORTED,
                Set.of(),
                "C-03/C-04 Bot execution is introduced by the later fleet checkpoint"));
        modes.put(ExecutionMode.HYBRID, new ModeCapabilityDeclaration(
                ExecutionMode.HYBRID,
                CapabilitySupport.UNSUPPORTED,
                Set.of(),
                "Hybrid routing is introduced only after Direct and Bot equivalence"));
        return new CapabilityExecutionDescriptor(
                CAPABILITY_ID,
                IMPLEMENTATION_ID,
                ADAPTER_ID,
                runtimeFingerprint,
                EnumSet.of(TaskKind.PLACE_COMPONENT),
                modes,
                Map.of(id("construction:contract"), "executor-v1"));
    }

    private static TaskFailure mapFailure(
            CreateV606GoalDrivenExecution.Failed failed,
            TaskAssignment assignment) {
        ConstructionFailureCode code = switch (failed.code()) {
            case FORMAL_WORLD_FORBIDDEN ->
                    ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED;
            case REQUIRED_CHUNK_UNLOADED -> ConstructionFailureCode.CHUNK_NOT_LOADED;
            case INPUT_RESOURCE_MISSING -> ConstructionFailureCode.MATERIAL_INSUFFICIENT;
            case BLOCK_PLACEMENT_BLOCKED, ROUTE_CONSTRUCTION_FAILED,
                    PHYSICAL_PLAN_STALE, TARGET_AREA_CHANGED ->
                    ConstructionFailureCode.WORLD_STATE_CHANGED;
            case EXECUTION_CANCELLED -> ConstructionFailureCode.CANCELLED;
            default -> ConstructionFailureCode.VERIFICATION_FAILED;
        };
        return new TaskFailure(
                code.id(),
                code.category(),
                false,
                Optional.empty(),
                Optional.of(assignment.taskId()),
                failed.detail(),
                List.of(assignment.assignmentId()),
                "Inspect the typed v606 failure and exact journal before retrying");
    }

    private static ResourceId childId(ResourceId parent, String suffix) {
        return new ResourceId(
                parent.namespace(),
                parent.path() + "/" + suffix);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime lacks SHA-256", exception);
        }
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    record Dispatch(
            TaskExecutionResult taskResult,
            CreateV606GoalDrivenExecution.TickResult tickResult,
            Optional<CreateV606GoalDrivenExecution.Cancellation> cancellation) {
        Dispatch {
            Objects.requireNonNull(taskResult, "taskResult");
            cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    record BoundedRootUpdate(
            CreateV606GoalDrivenExecution.TickResult tickResult,
            int worldMutationCount,
            int materialMutationCount) {
        BoundedRootUpdate {
            Objects.requireNonNull(tickResult, "tickResult");
            if (worldMutationCount < 0 || worldMutationCount > 1
                    || materialMutationCount < 0 || materialMutationCount > 1) {
                throw new IllegalArgumentException("A root update may report at most one mutation of each kind");
            }
        }
    }

    interface BoundedRootBackend {
        BoundedRootUpdate tick();

        CreateV606GoalDrivenExecution.Cancellation cancel(ResourceId reason);

        CreateV606GoalDrivenExecution.TickResult lastTickResult();

        Optional<CreateV606GoalDrivenExecution.Cancellation> lastCancellation();
    }
}
