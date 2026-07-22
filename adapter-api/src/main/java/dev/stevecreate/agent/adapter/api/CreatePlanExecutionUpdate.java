package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** A tick result is either bounded progress or completed evidence read from the real world. */
public sealed interface CreatePlanExecutionUpdate
        permits CreatePlanExecutionUpdate.InProgress, CreatePlanExecutionUpdate.Completed {

    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements CreatePlanExecutionUpdate {

        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0 || totalPlacements < 1 || completedPlacements > totalPlacements) {
                throw new IllegalArgumentException("Invalid placement progress");
            }
            if (gameTick < 0) {
                throw new IllegalArgumentException("gameTick must be non-negative");
            }
        }
    }

    record Completed(WaterWheelMillstoneEvidence evidence) implements CreatePlanExecutionUpdate {
        public Completed {
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
