package dev.stevecreate.agent.adapter.api.create;

import java.util.Objects;

/** Bounded live airflow requirement; reach is verified rather than inferred from sleep time. */
public record AirflowRequirement(
        boolean required,
        MediumRequirement medium,
        int minimumReachBlocks,
        boolean obstructionFreePath,
        boolean liveFlowObservationRequired,
        boolean dwellCompletionRequired) {
    public AirflowRequirement {
        Objects.requireNonNull(medium, "medium");
        if (minimumReachBlocks < 0 || minimumReachBlocks > 64) {
            throw new IllegalArgumentException("minimumReachBlocks must be between 0 and 64");
        }
        if (required) {
            if (medium == MediumRequirement.NONE || minimumReachBlocks == 0
                    || !obstructionFreePath || !liveFlowObservationRequired
                    || !dwellCompletionRequired) {
                throw new IllegalArgumentException(
                        "Airflow processing requires a medium, reach, clear path and live completion evidence");
            }
        } else if (medium != MediumRequirement.NONE || minimumReachBlocks != 0
                || obstructionFreePath || liveFlowObservationRequired || dwellCompletionRequired) {
            throw new IllegalArgumentException("A non-airflow process cannot claim airflow facts");
        }
    }

    public static AirflowRequirement none() {
        return new AirflowRequirement(false, MediumRequirement.NONE, 0, false, false, false);
    }
}
