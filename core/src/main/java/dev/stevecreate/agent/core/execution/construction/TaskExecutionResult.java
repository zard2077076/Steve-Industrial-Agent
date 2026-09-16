package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One bounded executor call result; success requires complete task postcondition evidence. */
public final class TaskExecutionResult {
    private final ResourceId assignmentId;
    private final ResourceId sessionId;
    private final ResourceId graphId;
    private final ResourceId taskId;
    private final ResourceId executorId;
    private final ExecutionMode mode;
    private final TaskExecutionOutcome outcome;
    private final List<ExecutionEvidence> evidence;
    private final Optional<TaskFailure> failure;
    private final long resultTick;
    private final int worldMutationCount;
    private final int materialMutationCount;
    private final String detail;

    private TaskExecutionResult(
            ConstructionTask task,
            TaskAssignment assignment,
            TaskExecutionOutcome outcome,
            List<ExecutionEvidence> evidence,
            Optional<TaskFailure> failure,
            long resultTick,
            int worldMutationCount,
            int materialMutationCount,
            String detail) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(assignment, "assignment");
        if (!task.taskId().equals(assignment.taskId())) {
            throw new IllegalArgumentException("Task and assignment identities differ");
        }
        this.assignmentId = assignment.assignmentId();
        this.sessionId = assignment.sessionId();
        this.graphId = assignment.graphId();
        this.taskId = assignment.taskId();
        this.executorId = assignment.executorId();
        this.mode = assignment.mode();
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.evidence = ConstructionContractValues.uniqueSorted(
                evidence, "evidence", ExecutionEvidence::evidenceId, false);
        this.failure = Objects.requireNonNull(failure, "failure");
        if (resultTick < assignment.assignedTick()) {
            throw new IllegalArgumentException("resultTick cannot predate assignment");
        }
        if (worldMutationCount < 0 || worldMutationCount > 1
                || materialMutationCount < 0 || materialMutationCount > 1) {
            throw new IllegalArgumentException("One executor call may report at most one world/material mutation");
        }
        this.resultTick = resultTick;
        this.worldMutationCount = worldMutationCount;
        this.materialMutationCount = materialMutationCount;
        this.detail = ConstructionContractValues.boundedText(
                detail, "detail", ConstructionContractValues.MAX_DETAIL_LENGTH, false);

        for (ExecutionEvidence item : this.evidence) {
            if (!item.belongsTo(assignment) || item.observedTick() > resultTick) {
                throw new IllegalArgumentException("Evidence is stale, future-dated or belongs to another assignment");
            }
        }
        if ((outcome == TaskExecutionOutcome.PENDING || outcome == TaskExecutionOutcome.SUCCEEDED)
                && !assignment.ownership().validAt(resultTick)) {
            throw new IllegalArgumentException("A pending/success result requires current task ownership");
        }
        validateOutcome(task, assignment);
    }

    public static TaskExecutionResult pending(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            List<ExecutionEvidence> evidence,
            int worldMutationCount,
            int materialMutationCount,
            String detail) {
        return new TaskExecutionResult(task, assignment, TaskExecutionOutcome.PENDING,
                evidence, Optional.empty(), tick, worldMutationCount, materialMutationCount, detail);
    }

    public static TaskExecutionResult success(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            List<ExecutionEvidence> evidence,
            int worldMutationCount,
            int materialMutationCount,
            String detail) {
        return new TaskExecutionResult(task, assignment, TaskExecutionOutcome.SUCCEEDED,
                evidence, Optional.empty(), tick, worldMutationCount, materialMutationCount, detail);
    }

    public static TaskExecutionResult failure(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            List<ExecutionEvidence> evidence,
            TaskFailure failure,
            int worldMutationCount,
            int materialMutationCount,
            String detail) {
        TaskExecutionOutcome outcome = failure.retryable()
                ? TaskExecutionOutcome.RETRYABLE_FAILURE
                : TaskExecutionOutcome.TERMINAL_FAILURE;
        return new TaskExecutionResult(task, assignment, outcome, evidence,
                Optional.of(failure), tick, worldMutationCount, materialMutationCount, detail);
    }

    public static TaskExecutionResult unsupported(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TaskFailure failure,
            String detail) {
        return new TaskExecutionResult(task, assignment, TaskExecutionOutcome.UNSUPPORTED,
                List.of(), Optional.of(failure), tick, 0, 0, detail);
    }

    public static TaskExecutionResult cancelled(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            List<ExecutionEvidence> evidence,
            TaskFailure cancellation,
            String detail) {
        return new TaskExecutionResult(task, assignment, TaskExecutionOutcome.CANCELLED,
                evidence, Optional.of(cancellation), tick, 0, 0, detail);
    }

    public static TaskExecutionResult recoveryRequired(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            List<ExecutionEvidence> evidence,
            TaskFailure failure,
            String detail) {
        return new TaskExecutionResult(task, assignment, TaskExecutionOutcome.RECOVERY_REQUIRED,
                evidence, Optional.of(failure), tick, 0, 0, detail);
    }

    private void validateOutcome(ConstructionTask task, TaskAssignment assignment) {
        boolean failureRequired = outcome == TaskExecutionOutcome.RETRYABLE_FAILURE
                || outcome == TaskExecutionOutcome.TERMINAL_FAILURE
                || outcome == TaskExecutionOutcome.CANCELLED
                || outcome == TaskExecutionOutcome.RECOVERY_REQUIRED
                || outcome == TaskExecutionOutcome.UNSUPPORTED;
        if (failureRequired != failure.isPresent()) {
            throw new IllegalArgumentException("Outcome/failure presence mismatch for " + outcome);
        }
        if (outcome == TaskExecutionOutcome.RETRYABLE_FAILURE) {
            if (!failure.orElseThrow().retryable()
                    || assignment.attempt() >= task.retryPolicy().maximumAttempts()
                    || !task.retryPolicy().retryableFailures().contains(failure.orElseThrow().code())) {
                throw new IllegalArgumentException("Retryable failure is outside the task retry policy");
            }
        }
        if (outcome == TaskExecutionOutcome.TERMINAL_FAILURE && failure.orElseThrow().retryable()) {
            throw new IllegalArgumentException("Terminal failure cannot be marked retryable");
        }
        if (outcome == TaskExecutionOutcome.CANCELLED) {
            if (!task.cancellable() || failure.orElseThrow().category() != TaskFailureCategory.CANCELLED) {
                throw new IllegalArgumentException("Cancellation requires a cancellable task and CANCELLED failure");
            }
        }
        if (outcome == TaskExecutionOutcome.UNSUPPORTED
                && failure.orElseThrow().category() != TaskFailureCategory.UNSUPPORTED_CAPABILITY) {
            throw new IllegalArgumentException("Unsupported outcome requires UNSUPPORTED_CAPABILITY");
        }
        if (outcome == TaskExecutionOutcome.SUCCEEDED) {
            Map<ResourceId, ExecutionEvidence> latest = new LinkedHashMap<>();
            for (ExecutionEvidence item : evidence) {
                ExecutionEvidence prior = latest.get(item.requirementId());
                if (prior == null || item.observedTick() > prior.observedTick()) {
                    latest.put(item.requirementId(), item);
                } else if (item.observedTick() == prior.observedTick()
                        && (!item.observedValues().equals(prior.observedValues())
                        || item.passed() != prior.passed())) {
                    throw new IllegalArgumentException("Conflicting evidence at the latest observation tick for "
                            + item.requirementId());
                }
            }
            for (TaskPostcondition postcondition : task.postconditions()) {
                ExecutionEvidence item = latest.get(postcondition.conditionId());
                if (item == null
                        || !item.passed()
                        || item.kind() != postcondition.requiredEvidenceKind()
                        || !item.subjectId().equals(postcondition.subjectId())
                        || !item.expectedValues().equals(postcondition.expectedValues())) {
                    throw new IllegalArgumentException("Success is missing objective postcondition evidence "
                            + postcondition.conditionId());
                }
            }
        }
    }

    public ResourceId assignmentId() { return assignmentId; }
    public ResourceId sessionId() { return sessionId; }
    public ResourceId graphId() { return graphId; }
    public ResourceId taskId() { return taskId; }
    public ResourceId executorId() { return executorId; }
    public ExecutionMode mode() { return mode; }
    public TaskExecutionOutcome outcome() { return outcome; }
    public List<ExecutionEvidence> evidence() { return evidence; }
    public Optional<TaskFailure> failure() { return failure; }
    public long resultTick() { return resultTick; }
    public int worldMutationCount() { return worldMutationCount; }
    public int materialMutationCount() { return materialMutationCount; }
    public String detail() { return detail; }
}
