package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-09 progress or final physical evidence. */
public sealed interface BasinPressExecutionUpdate
        permits BasinPressExecutionUpdate.InProgress,
                BasinPressExecutionUpdate.Completed {
    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements BasinPressExecutionUpdate {
        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0
                    || totalPlacements < 1
                    || completedPlacements > totalPlacements
                    || gameTick < 0) {
                throw new IllegalArgumentException("Invalid C-09 progress");
            }
        }
    }

    record Completed(BasinPressEvidence evidence)
            implements BasinPressExecutionUpdate {
        public Completed { Objects.requireNonNull(evidence, "evidence"); }
    }
}
