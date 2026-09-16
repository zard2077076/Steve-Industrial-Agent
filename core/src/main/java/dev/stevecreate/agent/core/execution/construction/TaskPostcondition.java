package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Objective evidence requirement that must exist before a task can report success. */
public record TaskPostcondition(
        ResourceId conditionId,
        TaskConditionKind kind,
        ResourceId subjectId,
        ExecutionEvidenceKind requiredEvidenceKind,
        Map<ResourceId, String> expectedValues) {
    public TaskPostcondition {
        Objects.requireNonNull(conditionId, "conditionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(requiredEvidenceKind, "requiredEvidenceKind");
        expectedValues = ConstructionContractValues.parameters(expectedValues, "expectedValues");
        if (expectedValues.isEmpty()) {
            throw new IllegalArgumentException("A task postcondition requires explicit expected values");
        }
    }
}
