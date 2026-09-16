package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Bounded C-10 progress or final physical evidence. */
public sealed interface DeployerExecutionUpdate
        permits DeployerExecutionUpdate.InProgress,
                DeployerExecutionUpdate.Completed {
    record InProgress(
            CreatePlanExecutionPhase phase,
            int completedPlacements,
            int totalPlacements,
            long gameTick) implements DeployerExecutionUpdate {
        public InProgress {
            Objects.requireNonNull(phase, "phase");
            if (completedPlacements < 0
                    || totalPlacements < 1
                    || completedPlacements > totalPlacements
                    || gameTick < 0) {
                throw new IllegalArgumentException(
                        "Invalid C-10 progress");
            }
        }
    }

    record Completed(DeployerEvidence evidence)
            implements DeployerExecutionUpdate {
        public Completed {
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
