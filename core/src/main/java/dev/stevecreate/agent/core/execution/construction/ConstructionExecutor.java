package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;

/**
 * Loader-neutral backend contract.
 *
 * <p>Each call performs at most one bounded world action and one bounded material action. World,
 * loader and mod objects are implementation-owned and never cross this API.</p>
 */
public interface ConstructionExecutor {
    ResourceId executorId();

    ExecutionMode mode();

    default boolean supports(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment) {
        if (!graph.graphId().equals(assignment.graphId())
                || !task.taskId().equals(assignment.taskId())
                || !executorId().equals(assignment.executorId())
                || mode() != assignment.mode()) {
            return false;
        }
        return graph.descriptor(task.capabilityId()).supports(mode(), task.kind())
                && task.allowedModes().contains(mode());
    }

    TaskExecutionResult execute(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context);
}
