package dev.stevecreate.agent.core.resource;

import java.util.Objects;

/**
 * Loader-neutral resource categories that may flow through a generic machine graph.
 *
 * <p>The model describes compatibility only. It does not claim that every category
 * already has a runtime transport or adapter implementation.</p>
 */
public enum GenericResourceType {
    ITEM("item"),
    FLUID("fluid"),
    ROTATIONAL_POWER("rotational_power"),
    ELECTRICAL_ENERGY("electrical_energy"),
    CHEMICAL("chemical"),
    HEAT("heat"),
    AIRFLOW("airflow");

    private final String serializedName;

    GenericResourceType(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static GenericResourceType fromSerializedName(String serializedName) {
        Objects.requireNonNull(serializedName, "serializedName");
        return switch (serializedName) {
            case "item" -> ITEM;
            case "fluid" -> FLUID;
            case "rotational_power" -> ROTATIONAL_POWER;
            case "electrical_energy" -> ELECTRICAL_ENERGY;
            case "chemical" -> CHEMICAL;
            case "heat" -> HEAT;
            case "airflow" -> AIRFLOW;
            default -> throw new IllegalArgumentException(
                    "Unknown generic resource type: " + serializedName);
        };
    }
}
