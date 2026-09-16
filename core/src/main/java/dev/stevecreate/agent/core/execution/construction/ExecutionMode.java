package dev.stevecreate.agent.core.execution.construction;

import java.util.Locale;
import java.util.Objects;

/** Stable player-selectable construction backend modes. */
public enum ExecutionMode {
    DIRECT,
    BOTS,
    HYBRID;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionMode parse(String value) {
        Objects.requireNonNull(value, "value");
        for (ExecutionMode mode : values()) {
            if (mode.serializedName().equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown execution mode: " + value);
    }
}
