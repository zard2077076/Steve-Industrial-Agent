package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic selection of the unassigned verified DAG frontier. */
public final class TaskScheduler {
    private TaskScheduler() {}

    public static List<ConstructionTask> readyTasks(
            ConstructionTaskGraph graph,
            Map<ResourceId, TaskExecutionResult> completed,
            Set<ResourceId> activeTaskIds,
            Set<ResourceId> terminalTaskIds,
            PriorityPolicy priorityPolicy) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(activeTaskIds, "activeTaskIds");
        Objects.requireNonNull(terminalTaskIds, "terminalTaskIds");
        Objects.requireNonNull(priorityPolicy, "priorityPolicy");
        return graph.readyTasks(completed).stream()
                .filter(task -> !activeTaskIds.contains(task.taskId()))
                .filter(task -> !terminalTaskIds.contains(task.taskId()))
                .sorted(priorityPolicy.comparator())
                .toList();
    }
}
