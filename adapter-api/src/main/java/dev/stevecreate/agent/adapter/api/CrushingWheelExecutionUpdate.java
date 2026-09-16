package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-05 progress or final physical evidence captured from the real world. */
public sealed interface CrushingWheelExecutionUpdate
        permits CrushingWheelExecutionUpdate.InProgress,
                CrushingWheelExecutionUpdate.Completed {

    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements CrushingWheelExecutionUpdate {

        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0
                    || totalPlacements < 1
                    || completedPlacements > totalPlacements) {
                throw new IllegalArgumentException("Invalid C-05 placement progress");
            }
            if (gameTick < 0) {
                throw new IllegalArgumentException("gameTick must be non-negative");
            }
        }
    }

    record Completed(CrushingWheelEvidence evidence)
            implements CrushingWheelExecutionUpdate {
        public Completed {
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
