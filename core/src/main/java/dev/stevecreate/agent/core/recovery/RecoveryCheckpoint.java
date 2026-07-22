package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded persisted state discovered after reload but not executable until reconciliation. */
public record RecoveryCheckpoint(
        RecoveryPlanSnapshot plan,
        RecoverySessionSnapshot session,
        WorldChangeJournal journal,
        List<BlockPos3i> modifiedPositions,
        long savedTick) {

    public RecoveryCheckpoint {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(journal, "journal");
        if (!plan.planId().equals(session.planId())) {
            throw new IllegalArgumentException("Recovery plan and session identities differ");
        }
        if (!session.sessionId().equals(journal.sessionId())) {
            throw new IllegalArgumentException("Recovery session and journal identities differ");
        }
        if (!session.worldChanges().equals(journal.references())) {
            throw new IllegalArgumentException(
                    "Recovery session references must exactly match the journal");
        }
        Objects.requireNonNull(modifiedPositions, "modifiedPositions");
        modifiedPositions = List.copyOf(modifiedPositions);
        if (modifiedPositions.stream().anyMatch(Objects::isNull)
                || new HashSet<>(modifiedPositions).size() != modifiedPositions.size()) {
            throw new IllegalArgumentException(
                    "modifiedPositions must contain unique non-null positions");
        }
        if (!modifiedPositions.equals(journal.modifiedPositions())) {
            throw new IllegalArgumentException(
                    "Persisted modified positions must exactly match the journal");
        }
        if (savedTick < session.lastProgressTick()) {
            throw new IllegalArgumentException(
                    "savedTick must not precede the session's latest progress");
        }
    }

    public static RecoveryCheckpoint capture(
            GenericExecutionSession session,
            WorldChangeJournal journal,
            long savedTick) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(journal, "journal");
        return new RecoveryCheckpoint(
                RecoveryPlanSnapshot.capture(session.plan()),
                RecoverySessionSnapshot.capture(session),
                journal,
                journal.modifiedPositions(),
                savedTick);
    }
}
