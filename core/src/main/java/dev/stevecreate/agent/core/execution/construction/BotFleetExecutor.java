package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact task-to-worker Bot backend; scheduling remains in {@link BotFleetCoordinator}. */
public final class BotFleetExecutor implements ConstructionExecutor {
    private final ResourceId executorId;
    private final Set<ResourceId> executorCapabilities;
    private final Map<ResourceId, BotWorker> workers;

    public BotFleetExecutor(
            ResourceId executorId,
            Set<ResourceId> executorCapabilities,
            List<? extends BotWorker> workers) {
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(executorCapabilities, "executorCapabilities");
        LinkedHashSet<ResourceId> copiedCapabilities = new LinkedHashSet<>();
        executorCapabilities.stream()
                .map(value -> Objects.requireNonNull(value, "executorCapabilities element"))
                .sorted(Comparator.comparing(ResourceId::toString))
                .forEach(copiedCapabilities::add);
        if (copiedCapabilities.isEmpty() || copiedCapabilities.size() > 256) {
            throw new IllegalArgumentException("executorCapabilities count must be between 1 and 256");
        }
        this.executorCapabilities = Collections.unmodifiableSet(copiedCapabilities);
        Objects.requireNonNull(workers, "workers");
        if (workers.size() < BotFleetCoordinator.MIN_WORKERS
                || workers.size() > BotFleetCoordinator.MAX_WORKERS) {
            throw new IllegalArgumentException("Bot fleet size must be between "
                    + BotFleetCoordinator.MIN_WORKERS + " and " + BotFleetCoordinator.MAX_WORKERS);
        }
        Map<ResourceId, BotWorker> indexed = new LinkedHashMap<>();
        workers.stream()
                .map(value -> Objects.requireNonNull(value, "workers element"))
                .sorted(Comparator.comparing(value -> value.workerId().toString()))
                .forEach(worker -> {
                    if (indexed.putIfAbsent(worker.workerId(), worker) != null) {
                        throw new IllegalArgumentException("Duplicate Bot worker " + worker.workerId());
                    }
                });
        this.workers = Collections.unmodifiableMap(indexed);
    }

    @Override
    public ResourceId executorId() {
        return executorId;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.BOTS;
    }

    @Override
    public boolean supports(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment) {
        return ConstructionExecutor.super.supports(graph, task, assignment)
                && executorCapabilities.containsAll(graph.descriptor(task.capabilityId())
                .modeCapability(ExecutionMode.BOTS)
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
            return unsupported(task, assignment, context.currentTick(),
                    "Bot fleet executor does not support the exact graph/task/assignment declaration");
        }
        if (task.taskClass() == ConstructionTaskClass.HIGH_RISK_INTERACTION) {
            String detail = "High-risk interaction is outside the Bot action whitelist";
            return TaskExecutionResult.failure(
                    task, assignment, context.currentTick(), context.priorEvidence(),
                    failure(ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED,
                            task, assignment, detail,
                            "Use no automatic executor until a separately approved safety gate exists"),
                    0, 0, detail);
        }
        ResourceId workerId = assignment.workerId().orElseThrow(() ->
                new IllegalArgumentException("Bot task assignment lacks a worker identity"));
        BotWorker worker = workers.get(workerId);
        if (worker == null) {
            return unavailable(task, assignment, context, "Assigned Bot is not registered: " + workerId);
        }
        BotWorkerSnapshot snapshot = Objects.requireNonNull(
                worker.snapshot(context.currentTick()), "Bot worker snapshot");
        if (!snapshot.workerId().equals(workerId) || snapshot.observedTick() != context.currentTick()) {
            throw new IllegalStateException("Bot worker returned another identity or observation tick");
        }
        if (!snapshot.healthyAndLoaded()) {
            return unavailable(task, assignment, context,
                    "Assigned Bot is offline, dead, unloaded or unhealthy: " + snapshot.status());
        }
        if (snapshot.status() == BotWorkerStatus.BUSY && !snapshot.assignedTo(assignment)) {
            return unavailable(task, assignment, context,
                    "Assigned Bot is busy with another exact assignment");
        }
        Set<BotWorkerCapability> required = BotWorkerCapability.requiredFor(task.kind());
        if (!snapshot.capabilities().containsAll(required)) {
            return unsupported(task, assignment, context.currentTick(),
                    "Bot worker lacks required safe actions for " + task.kind());
        }
        if (context.command() != ConstructionExecutionCommand.CANCEL
                && !assignment.ownership().validAt(context.currentTick())) {
            String detail = "Bot task ownership is not current at tick " + context.currentTick();
            return TaskExecutionResult.failure(
                    task, assignment, context.currentTick(), context.priorEvidence(),
                    failure(ConstructionFailureCode.OWNERSHIP_LOST, task, assignment, detail,
                            "Reassign the task with a new exact ownership generation"),
                    0, 0, detail);
        }
        if (context.command() == ConstructionExecutionCommand.CANCEL && !task.cancellable()) {
            throw new IllegalArgumentException("Bot cancellation requires a cancellable task");
        }
        if (context.command() == ConstructionExecutionCommand.RECOVER
                && task.recoveryPolicy().strategy() == RecoveryStrategy.REFUSE) {
            String detail = "Bot task recovery policy refuses replay";
            return TaskExecutionResult.recoveryRequired(
                    task, assignment, context.currentTick(), context.priorEvidence(),
                    failure(ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION,
                            task, assignment, detail,
                            "Reconcile or return carried materials before reassignment"),
                    detail);
        }
        TaskExecutionResult result = Objects.requireNonNull(
                worker.execute(graph, task, assignment, context), "Bot worker result");
        validateResultIdentity(assignment, context, result);
        if (context.command() == ConstructionExecutionCommand.CANCEL
                && result.outcome() != TaskExecutionOutcome.CANCELLED) {
            throw new IllegalStateException("Bot cancellation must return CANCELLED");
        }
        return result;
    }

    public Map<ResourceId, BotWorker> workers() {
        return workers;
    }

    private static void validatePriorEvidence(
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        for (ExecutionEvidence evidence : context.priorEvidence()) {
            if (!evidence.belongsTo(assignment) || evidence.observedTick() > context.currentTick()) {
                throw new IllegalArgumentException(
                        "Prior Bot evidence is future-dated or belongs to another assignment");
            }
        }
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
                || result.mode() != ExecutionMode.BOTS
                || result.resultTick() != context.currentTick()) {
            throw new IllegalStateException(
                    "Bot worker returned a result for another assignment, mode or tick");
        }
    }

    private static TaskExecutionResult unavailable(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            String detail) {
        return TaskExecutionResult.failure(
                task, assignment, context.currentTick(), context.priorEvidence(),
                failure(ConstructionFailureCode.WORKER_UNAVAILABLE, task, assignment, detail,
                        "Wait for a healthy loaded Bot or reassign within the retry budget"),
                0, 0, detail);
    }

    private static TaskExecutionResult unsupported(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            String detail) {
        return TaskExecutionResult.unsupported(
                task,
                assignment,
                tick,
                failure(ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED,
                        task, assignment, detail,
                        "Route only to an explicitly declared safe Direct fallback"),
                detail);
    }

    private static TaskFailure failure(
            ConstructionFailureCode code,
            ConstructionTask task,
            TaskAssignment assignment,
            String detail,
            String safeNextStep) {
        return new TaskFailure(
                code.id(), code.category(), false, Optional.empty(), Optional.of(task.taskId()),
                detail, List.of(assignment.assignmentId()), safeNextStep);
    }
}
