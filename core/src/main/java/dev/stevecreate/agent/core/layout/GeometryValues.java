package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;
import java.util.Set;

final class GeometryValues {
    private GeometryValues() {}

    static Set<BlockPos3i> cells(Set<BlockPos3i> values, String name, boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > 4_096) {
            throw new IllegalArgumentException(name + " cell count is outside its bound");
        }
        values.forEach(value -> Objects.requireNonNull(value, name + " cell"));
        return Set.copyOf(values);
    }
}
