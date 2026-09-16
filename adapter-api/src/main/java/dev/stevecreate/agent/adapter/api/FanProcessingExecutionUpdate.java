package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-06 progress or final physical evidence. */
public sealed interface FanProcessingExecutionUpdate
        permits FanProcessingExecutionUpdate.InProgress,
                FanProcessingExecutionUpdate.Completed {

    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements FanProcessingExecutionUpdate {
        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0
                    || totalPlacements < 1
                    || completedPlacements > totalPlacements
                    || gameTick < 0) {
                throw new IllegalArgumentException("Invalid C-06 progress");
            }
        }
    }

    record Completed(FanProcessingEvidence evidence)
            implements FanProcessingExecutionUpdate {
        public Completed { Objects.requireNonNull(evidence, "evidence"); }
    }
}
