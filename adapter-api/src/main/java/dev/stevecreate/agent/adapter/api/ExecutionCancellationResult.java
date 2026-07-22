package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import java.util.List;
import java.util.Objects;

/** Typed cancellation result with the immutable journal and conservative rollback report. */
public record ExecutionCancellationResult(
        ResourceId sessionId,
        ResourceId cancellationReason,
        WorldChangeJournal journal,
        RollbackReport rollbackReport) {
    public ExecutionCancellationResult {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(cancellationReason, "cancellationReason");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(rollbackReport, "rollbackReport");
        if (!journal.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("Journal session does not match cancellation session");
        }
        if (!rollbackReport.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("Rollback report session does not match cancellation session");
        }
    }

    public List<BlockPos3i> modifiedPositions() {
        return journal.modifiedPositions();
    }
}
