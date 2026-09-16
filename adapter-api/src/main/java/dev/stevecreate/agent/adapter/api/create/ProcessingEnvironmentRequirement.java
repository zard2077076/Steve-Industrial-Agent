package dev.stevecreate.agent.adapter.api.create;

import java.util.Objects;

/** Complete loader-neutral environment contract for one runtime recipe. */
public record ProcessingEnvironmentRequirement(
        DirectionalFlowRequirement directionalFlow,
        HeatRequirement heat,
        AirflowRequirement airflow,
        MediumRequirement medium,
        ToolRequirement tool,
        CatalystRequirement catalyst,
        BasinRequirement basin,
        HeldItemRequirement heldItem,
        MinimumSpeedRequirement minimumSpeed,
        RuntimeObservationRequirement runtimeObservation,
        boolean itemProcessingOnly,
        boolean arbitraryWorldInteractionForbidden) {
    public ProcessingEnvironmentRequirement {
        Objects.requireNonNull(directionalFlow, "directionalFlow");
        Objects.requireNonNull(heat, "heat");
        Objects.requireNonNull(airflow, "airflow");
        Objects.requireNonNull(medium, "medium");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(catalyst, "catalyst");
        Objects.requireNonNull(basin, "basin");
        Objects.requireNonNull(heldItem, "heldItem");
        Objects.requireNonNull(minimumSpeed, "minimumSpeed");
        Objects.requireNonNull(runtimeObservation, "runtimeObservation");
        if (airflow.required() != (medium != MediumRequirement.NONE)) {
            throw new IllegalArgumentException("Airflow and medium requirements disagree");
        }
        if (!itemProcessingOnly || !arbitraryWorldInteractionForbidden) {
            throw new IllegalArgumentException(
                    "C-05 through C-10 Phase I is item-processing-only and forbids arbitrary world use");
        }
    }
}
