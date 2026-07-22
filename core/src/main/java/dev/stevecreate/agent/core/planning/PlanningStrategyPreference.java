package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** Explicit loader-neutral goal preference; P-08 assigns deterministic weights later. */
public enum PlanningStrategyPreference {
    MINIMIZE_STEPS("minimize_steps"),
    MINIMIZE_MACHINE_TYPES("minimize_machine_types"),
    MINIMIZE_RAW_MATERIAL_TYPES("minimize_raw_material_types"),
    PREFER_OWNED_RESOURCES("prefer_owned_resources"),
    MINIMIZE_PROCESSING_TIME("minimize_processing_time");

    private final String serializedName;

    PlanningStrategyPreference(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static PlanningStrategyPreference fromSerializedName(String value) {
        Objects.requireNonNull(value, "value");
        for (PlanningStrategyPreference preference : values()) {
            if (preference.serializedName.equals(value)) {
                return preference;
            }
        }
        throw new IllegalArgumentException("Unknown planning strategy preference: " + value);
    }
}
