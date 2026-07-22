package dev.stevecreate.agent.core.graph;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

final class GraphModelValues {
    private GraphModelValues() {
    }

    static Map<String, String> copyTextMap(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = requireText(entry.getKey(), name + " key");
            String value = requireText(entry.getValue(), name + " value for " + key);
            if (copy.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(name + " contains duplicate key: " + key);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    static Set<ResourceId> copyIds(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<ResourceId> copy = new LinkedHashSet<>();
        for (ResourceId value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    static OptionalLong requirePositiveIfPresent(OptionalLong value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isPresent() && value.getAsLong() <= 0) {
            throw new IllegalArgumentException(name + " must be positive when present");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
