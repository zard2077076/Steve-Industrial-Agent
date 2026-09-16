package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Required observed state before one bounded task action may run. */
public record TaskPrecondition(
        ResourceId conditionId,
        TaskConditionKind kind,
        ResourceId subjectId,
        ExecutionEvidenceKind requiredEvidenceKind,
        Map<ResourceId, String> expectedValues) {
    public TaskPrecondition {
        Objects.requireNonNull(conditionId, "conditionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(requiredEvidenceKind, "requiredEvidenceKind");
        expectedValues = ConstructionContractValues.parameters(expectedValues, "expectedValues");
    }
}
