package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Complete observed-vs-expected evidence attributed to one exact assignment. */
public record ExecutionEvidence(
        ResourceId evidenceId,
        ResourceId requirementId,
        ResourceId sessionId,
        ResourceId graphId,
        ResourceId taskId,
        ResourceId assignmentId,
        ResourceId executorId,
        ExecutionMode mode,
        ExecutionEvidenceKind kind,
        ResourceId subjectId,
        Map<ResourceId, String> observedValues,
        Map<ResourceId, String> expectedValues,
        long observedTick,
        boolean passed,
        String provenance) {
    public ExecutionEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        observedValues = ConstructionContractValues.parameters(observedValues, "observedValues");
        expectedValues = ConstructionContractValues.parameters(expectedValues, "expectedValues");
        if (observedTick < 0) {
            throw new IllegalArgumentException("observedTick must be non-negative");
        }
        if (passed && !observedValues.entrySet().containsAll(expectedValues.entrySet())) {
            throw new IllegalArgumentException("Passing evidence must contain every expected observed value");
        }
        provenance = ConstructionContractValues.boundedText(
                provenance, "provenance", 1_024, false);
    }

    public boolean belongsTo(TaskAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        return sessionId.equals(assignment.sessionId())
                && graphId.equals(assignment.graphId())
                && taskId.equals(assignment.taskId())
                && assignmentId.equals(assignment.assignmentId())
                && executorId.equals(assignment.executorId())
                && mode == assignment.mode();
    }
}
