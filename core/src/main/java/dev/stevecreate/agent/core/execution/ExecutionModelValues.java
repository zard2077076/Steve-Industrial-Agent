package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ExecutionModelValues {
    static final int MAX_PARAMETERS = 32;
    static final int MAX_PARAMETER_VALUE_LENGTH = 256;
    static final int MAX_REQUIREMENTS = 64;

    private ExecutionModelValues() {
    }

    static Map<ResourceId, String> copyParameters(
            Map<ResourceId, String> values,
            String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException(name + " count exceeds " + MAX_PARAMETERS);
        }
        Map<ResourceId, String> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, String> entry : values.entrySet()) {
            ResourceId key = Objects.requireNonNull(entry.getKey(), name + " key");
            String value = Objects.requireNonNull(entry.getValue(), name + " value for " + key);
            if (value.isBlank() || value.length() > MAX_PARAMETER_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        name + " value for " + key + " must contain 1 to "
                                + MAX_PARAMETER_VALUE_LENGTH + " characters");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    static Set<ResourceId> copyRequirements(
            Set<ResourceId> values,
            String name,
            boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty()) || values.size() > MAX_REQUIREMENTS) {
            String minimum = requireNonEmpty ? "1" : "0";
            throw new IllegalArgumentException(
                    name + " count must be between " + minimum + " and " + MAX_REQUIREMENTS);
        }
        LinkedHashSet<ResourceId> copy = new LinkedHashSet<>();
        for (ResourceId value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }
}
