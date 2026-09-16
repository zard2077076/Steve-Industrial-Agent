package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Set;

/** Directed task edge with exact predecessor evidence requirements. */
public record TaskDependency(
        ResourceId predecessorTaskId,
        ResourceId successorTaskId,
        TaskDependencyKind kind,
        Set<ResourceId> requiredPostconditionIds) {
    public TaskDependency {
        Objects.requireNonNull(predecessorTaskId, "predecessorTaskId");
        Objects.requireNonNull(successorTaskId, "successorTaskId");
        Objects.requireNonNull(kind, "kind");
        if (predecessorTaskId.equals(successorTaskId)) {
            throw new IllegalArgumentException("A task cannot depend on itself");
        }
        requiredPostconditionIds = ConstructionContractValues.sortedIds(
                requiredPostconditionIds, "requiredPostconditionIds", true);
    }
}
