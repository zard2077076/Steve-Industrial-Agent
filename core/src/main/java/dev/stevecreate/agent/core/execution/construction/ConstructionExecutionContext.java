package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable input for one bounded executor call; trusted world services stay inside implementations. */
public record ConstructionExecutionContext(
        ConstructionExecutionCommand command,
        long currentTick,
        List<ExecutionEvidence> priorEvidence,
        Optional<TaskFailure> priorFailure,
        Optional<ResourceId> reason) {
    public ConstructionExecutionContext {
        Objects.requireNonNull(command, "command");
        if (currentTick < 0) {
            throw new IllegalArgumentException("currentTick must be non-negative");
        }
        priorEvidence = ConstructionContractValues.uniqueSorted(
                priorEvidence, "priorEvidence", ExecutionEvidence::evidenceId, false);
        priorFailure = Objects.requireNonNull(priorFailure, "priorFailure");
        reason = Objects.requireNonNull(reason, "reason");
        if ((command == ConstructionExecutionCommand.CANCEL) != reason.isPresent()) {
            throw new IllegalArgumentException("Only CANCEL requires an exact reason");
        }
        if (command == ConstructionExecutionCommand.RECOVER && priorEvidence.isEmpty()) {
            throw new IllegalArgumentException("Recovery requires exact reconciliation evidence");
        }
    }
}
