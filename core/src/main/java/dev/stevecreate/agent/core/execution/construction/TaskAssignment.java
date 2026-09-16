package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** Exact task-to-executor/worker assignment bound to a current ownership lease. */
public final class TaskAssignment {
    private final ResourceId assignmentId;
    private final ResourceId sessionId;
    private final ResourceId graphId;
    private final ResourceId taskId;
    private final ResourceId executorId;
    private final ExecutionMode mode;
    private final Optional<ResourceId> workerId;
    private final int attempt;
    private final long assignedTick;
    private final TaskOwnership ownership;

    private TaskAssignment(
            ResourceId assignmentId,
            ResourceId sessionId,
            ResourceId graphId,
            ResourceId taskId,
            ResourceId executorId,
            ExecutionMode mode,
            Optional<ResourceId> workerId,
            int attempt,
            long assignedTick,
            TaskOwnership ownership) {
        this.assignmentId = assignmentId;
        this.sessionId = sessionId;
        this.graphId = graphId;
        this.taskId = taskId;
        this.executorId = executorId;
        this.mode = mode;
        this.workerId = workerId;
        this.attempt = attempt;
        this.assignedTick = assignedTick;
        this.ownership = ownership;
    }

    public static TaskAssignment assign(
            ConstructionTaskGraph graph,
            ResourceId taskId,
            ResourceId assignmentId,
            TaskOwnership ownership,
            int attempt,
            long assignedTick) {
        Objects.requireNonNull(graph, "graph");
        ConstructionTask task = graph.task(taskId);
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(ownership, "ownership");
        if (!ownership.graphId().equals(graph.graphId())
                || !ownership.taskId().equals(task.taskId())) {
            throw new IllegalArgumentException("Ownership does not match the assigned graph/task");
        }
        if (!task.allowedModes().contains(ownership.mode())) {
            throw new IllegalArgumentException("Task does not permit assignment mode " + ownership.mode());
        }
        if (!ownership.validAt(assignedTick)) {
            throw new IllegalArgumentException("Task ownership is not valid at assignment time");
        }
        if (attempt < 1 || attempt > task.retryPolicy().maximumAttempts()) {
            throw new IllegalArgumentException("attempt is outside the task retry policy");
        }
        return new TaskAssignment(
                assignmentId,
                ownership.sessionId(),
                graph.graphId(),
                task.taskId(),
                ownership.executorId(),
                ownership.mode(),
                ownership.workerId(),
                attempt,
                assignedTick,
                ownership);
    }

    public ResourceId assignmentId() { return assignmentId; }
    public ResourceId sessionId() { return sessionId; }
    public ResourceId graphId() { return graphId; }
    public ResourceId taskId() { return taskId; }
    public ResourceId executorId() { return executorId; }
    public ExecutionMode mode() { return mode; }
    public Optional<ResourceId> workerId() { return workerId; }
    public int attempt() { return attempt; }
    public long assignedTick() { return assignedTick; }
    public TaskOwnership ownership() { return ownership; }
}
