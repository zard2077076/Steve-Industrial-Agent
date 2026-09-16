package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-07 progress or final physical evidence. */
public sealed interface MechanicalSawExecutionUpdate
        permits MechanicalSawExecutionUpdate.InProgress, MechanicalSawExecutionUpdate.Completed {

    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements MechanicalSawExecutionUpdate {
        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0
                    || totalPlacements < 1
                    || completedPlacements > totalPlacements
                    || gameTick < 0) {
                throw new IllegalArgumentException("Invalid C-07 progress");
            }
        }
    }

    record Completed(MechanicalSawEvidence evidence) implements MechanicalSawExecutionUpdate {
        public Completed { Objects.requireNonNull(evidence, "evidence"); }
    }
}
