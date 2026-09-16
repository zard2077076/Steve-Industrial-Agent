package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-08 progress or final physical evidence. */
public sealed interface BasinMixerExecutionUpdate
        permits BasinMixerExecutionUpdate.InProgress,
                BasinMixerExecutionUpdate.Completed {
    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements BasinMixerExecutionUpdate {
        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0
                    || totalPlacements < 1
                    || completedPlacements > totalPlacements
                    || gameTick < 0) {
                throw new IllegalArgumentException(
                        "Invalid C-08 progress");
            }
        }
    }

    record Completed(BasinMixerEvidence evidence)
            implements BasinMixerExecutionUpdate {
        public Completed {
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
