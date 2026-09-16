package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loader-neutral Direct backend that guards one server-authoritative bounded action.
 *
 * <p>The backend implementation may capture loader or world services, but those objects never cross
 * this API. The trusted backend remains responsible for the physical read/write itself; this class
 * enforces the frozen graph, assignment, capability, recovery and result-identity boundary around
 * every call.</p>
 */
public final class DirectWorldExecutor implements ConstructionExecutor {
    private final ResourceId executorId;
    private final Set<ResourceId> executorCapabilities;
    private final BoundedDirectWorldBackend backend;

    public DirectWorldExecutor(
            ResourceId executorId,
            Set<ResourceId> executorCapabilities,
            BoundedDirectWorldBackend backend) {
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(executorCapabilities, "executorCapabilities");
        LinkedHashSet<ResourceId> copied = new LinkedHashSet<>();
        executorCapabilities.stream()
                .map(value -> Objects.requireNonNull(value, "executorCapabilities element"))
                .sorted(java.util.Comparator.comparing(ResourceId::toString))
                .forEach(copied::add);
        if (copied.isEmpty() || copied.size() > 256) {
            throw new IllegalArgumentException("executorCapabilities count must be between 1 and 256");
        }
        this.executorCapabilities = Collections.unmodifiableSet(copied);
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public ResourceId executorId() {
        return executorId;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.DIRECT;
    }

    public Set<ResourceId> executorCapabilities() {
        return executorCapabilities;
    }

    @Override
    public boolean supports(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment) {
        if (!ConstructionExecutor.super.supports(graph, task, assignment)) {
            return false;
        }
        return executorCapabilities.containsAll(graph.descriptor(task.capabilityId())
                .modeCapability(ExecutionMode.DIRECT)
                .requiredExecutorCapabilities());
    }

    @Override
    public TaskExecutionResult execute(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(context, "context");
        validatePriorEvidence(assignment, context);

        if (!supports(graph, task, assignment)) {
            String detail = "Direct executor does not support the exact graph/task/assignment capability";
            return TaskExecutionResult.unsupported(
                    task,
                    assignment,
                    context.currentTick(),
                    failure(ConstructionFailureCode.EXECUTOR_MODE_UNSUPPORTED, task, assignment,
                            detail, "Select an explicitly declared executor for this task"),
                    detail);
        }
        if (context.command() != ConstructionExecutionCommand.CANCEL
                && !assignment.ownership().validAt(context.currentTick())) {
            String detail = "Direct task ownership is not current at tick " + context.currentTick();
            return TaskExecutionResult.failure(
                    task,
                    assignment,
                    context.currentTick(),
                    context.priorEvidence(),
                    failure(ConstructionFailureCode.OWNERSHIP_LOST, task, assignment,
                            detail, "Acquire a new exact ownership generation before retrying"),
                    0,
                    0,
                    detail);
        }
        if (context.command() == ConstructionExecutionCommand.CANCEL && !task.cancellable()) {
            String detail = "The assigned construction task is not cancellable";
            return TaskExecutionResult.failure(
                    task,
                    assignment,
                    context.currentTick(),
                    context.priorEvidence(),
                    contractFailure("construction:task_not_cancellable", task, assignment, detail,
                            "Allow the bounded task to reach its verified terminal state"),
                    0,
                    0,
                    detail);
        }
        if (context.command() == ConstructionExecutionCommand.RECOVER) {
            TaskExecutionResult refused = validateRecovery(task, assignment, context);
            if (refused != null) {
                return refused;
            }
        }

        TaskExecutionResult result = Objects.requireNonNull(
                backend.execute(graph, task, assignment, context),
                "Direct backend result");
        validateResultIdentity(assignment, context, result);
        if (context.command() == ConstructionExecutionCommand.CANCEL
                && result.outcome() != TaskExecutionOutcome.CANCELLED) {
            throw new IllegalStateException("Direct cancellation backend must return CANCELLED");
        }
        return result;
    }

    private static void validatePriorEvidence(
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        for (ExecutionEvidence evidence : context.priorEvidence()) {
            if (!evidence.belongsTo(assignment) || evidence.observedTick() > context.currentTick()) {
                throw new IllegalArgumentException(
                        "Prior evidence is future-dated or belongs to another assignment");
            }
        }
    }

    private static TaskExecutionResult validateRecovery(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        RecoveryPolicy recovery = task.recoveryPolicy();
        if (recovery.strategy() == RecoveryStrategy.REFUSE) {
            String detail = "The task recovery policy refuses replay";
            return TaskExecutionResult.recoveryRequired(
                    task,
                    assignment,
                    context.currentTick(),
                    context.priorEvidence(),
                    failure(ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION,
                            task, assignment, detail,
                            "Cancel or reconcile through the existing typed recovery path"),
                    detail);
        }
        Set<ResourceId> reconciled = new LinkedHashSet<>();
        for (ExecutionEvidence evidence : context.priorEvidence()) {
            if (evidence.passed()
                    && evidence.kind() == ExecutionEvidenceKind.RECOVERY_RECONCILED) {
                reconciled.add(evidence.requirementId());
            }
        }
        if (!reconciled.containsAll(recovery.requiredReconciliationEvidence())) {
            String detail = "Recovery is missing exact reconciliation evidence";
            return TaskExecutionResult.recoveryRequired(
                    task,
                    assignment,
                    context.currentTick(),
                    context.priorEvidence(),
                    failure(ConstructionFailureCode.RECOVERY_RECONCILIATION_REQUIRED,
                            task, assignment, detail,
                            "Perform the bounded authoritative rescan before recovery"),
                    detail);
        }
        return null;
    }

    private static void validateResultIdentity(
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            TaskExecutionResult result) {
        if (!result.assignmentId().equals(assignment.assignmentId())
                || !result.sessionId().equals(assignment.sessionId())
                || !result.graphId().equals(assignment.graphId())
                || !result.taskId().equals(assignment.taskId())
                || !result.executorId().equals(assignment.executorId())
                || result.mode() != ExecutionMode.DIRECT
                || result.resultTick() != context.currentTick()) {
            throw new IllegalStateException(
                    "Direct backend returned a result for another assignment, mode or tick");
        }
    }

    private static TaskFailure failure(
            ConstructionFailureCode code,
            ConstructionTask task,
            TaskAssignment assignment,
            String detail,
            String safeNextStep) {
        return new TaskFailure(
                code.id(),
                code.category(),
                false,
                Optional.empty(),
                Optional.of(task.taskId()),
                detail,
                List.of(assignment.assignmentId()),
                safeNextStep);
    }

    private static TaskFailure contractFailure(
            String code,
            ConstructionTask task,
            TaskAssignment assignment,
            String detail,
            String safeNextStep) {
        return new TaskFailure(
                ResourceId.parse(code),
                TaskFailureCategory.INTERNAL_CONTRACT_VIOLATION,
                false,
                Optional.empty(),
                Optional.of(task.taskId()),
                detail,
                List.of(assignment.assignmentId()),
                safeNextStep);
    }

    /** One trusted bounded action; implementations may capture world objects but may not expose them here. */
    @FunctionalInterface
    public interface BoundedDirectWorldBackend {
        TaskExecutionResult execute(
                ConstructionTaskGraph graph,
                ConstructionTask task,
                TaskAssignment assignment,
                ConstructionExecutionContext context);
    }
}
