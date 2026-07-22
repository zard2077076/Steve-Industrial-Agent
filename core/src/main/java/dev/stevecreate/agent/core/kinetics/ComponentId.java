package dev.stevecreate.agent.core.kinetics;

import java.util.Objects;

public record ComponentId(String value) {
    public ComponentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Component id must not be blank");
        }
    }
}

