package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Arrays;
import java.util.Optional;

/** Stable logical role of an implementation-owned port before physical layout. */
public enum ImplementationPortRole {
    ITEM_INPUT("item_input", Optional.of(GenericResourceType.ITEM), PortMode.INPUT),
    ITEM_OUTPUT("item_output", Optional.of(GenericResourceType.ITEM), PortMode.OUTPUT),
    FLUID_INPUT("fluid_input", Optional.of(GenericResourceType.FLUID), PortMode.INPUT),
    FLUID_OUTPUT("fluid_output", Optional.of(GenericResourceType.FLUID), PortMode.OUTPUT),
    ROTATIONAL_POWER_INPUT(
            "rotational_power_input",
            Optional.of(GenericResourceType.ROTATIONAL_POWER),
            PortMode.INPUT),
    ROTATIONAL_POWER_OUTPUT(
            "rotational_power_output",
            Optional.of(GenericResourceType.ROTATIONAL_POWER),
            PortMode.OUTPUT),
    ELECTRICAL_ENERGY_INPUT(
            "electrical_energy_input",
            Optional.of(GenericResourceType.ELECTRICAL_ENERGY),
            PortMode.INPUT),
    ELECTRICAL_ENERGY_OUTPUT(
            "electrical_energy_output",
            Optional.of(GenericResourceType.ELECTRICAL_ENERGY),
            PortMode.OUTPUT),
    HEAT_INPUT("heat_input", Optional.of(GenericResourceType.HEAT), PortMode.INPUT),
    HEAT_OUTPUT("heat_output", Optional.of(GenericResourceType.HEAT), PortMode.OUTPUT),
    AIRFLOW_INPUT("airflow_input", Optional.of(GenericResourceType.AIRFLOW), PortMode.INPUT),
    AIRFLOW_OUTPUT("airflow_output", Optional.of(GenericResourceType.AIRFLOW), PortMode.OUTPUT),
    REDSTONE_CONTROL("redstone_control", Optional.empty(), PortMode.INPUT),
    SIGNAL_INPUT("signal_input", Optional.empty(), PortMode.INPUT),
    SIGNAL_OUTPUT("signal_output", Optional.empty(), PortMode.OUTPUT),
    UNSUPPORTED_SPECIAL_PORT("unsupported_special_port", Optional.empty(), PortMode.BIDIRECTIONAL);

    private final String serializedName;
    private final Optional<GenericResourceType> expectedResourceType;
    private final PortMode expectedMode;

    ImplementationPortRole(
            String serializedName,
            Optional<GenericResourceType> expectedResourceType,
            PortMode expectedMode) {
        this.serializedName = serializedName;
        this.expectedResourceType = expectedResourceType;
        this.expectedMode = expectedMode;
    }

    public String serializedName() {
        return serializedName;
    }

    public Optional<GenericResourceType> expectedResourceType() {
        return expectedResourceType;
    }

    public PortMode expectedMode() {
        return expectedMode;
    }

    public static Optional<ImplementationPortRole> fromSerializedName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(role -> role.serializedName.equals(value))
                .findFirst();
    }
}
