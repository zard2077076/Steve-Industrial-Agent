package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-04 progress or final physical evidence captured from the real world. */
public sealed interface BeltPressExecutionUpdate
        permits BeltPressExecutionUpdate.InProgress, BeltPressExecutionUpdate.Completed {

    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedBuildSteps,
            int totalBuildSteps,
            long gameTick) implements BeltPressExecutionUpdate {

        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedBuildSteps < 0
                    || totalBuildSteps < 1
                    || completedBuildSteps > totalBuildSteps) {
                throw new IllegalArgumentException("Invalid C-04 build progress");
            }
            if (gameTick < 0) {
                throw new IllegalArgumentException("gameTick must be non-negative");
            }
        }
    }

    record Completed(BeltPressEvidence evidence) implements BeltPressExecutionUpdate {
        public Completed {
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
