package dev.stevecreate.agent.adapter.api.create;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

final class CapabilityContracts {
    static final int MAX_TEXT_LENGTH = 2_048;
    static final int MAX_COLLECTION_SIZE = 4_096;

    private CapabilityContracts() {
    }

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }

    static <T> List<T> list(Collection<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return List.copyOf(copy);
    }

    static <T> Set<T> sortedSet(Collection<T> values, Comparator<T> comparator, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        Set<T> sorted = new java.util.TreeSet<>(comparator);
        for (T value : values) {
            T element = Objects.requireNonNull(value, name + " element");
            if (!sorted.add(element)) {
                throw new IllegalArgumentException(name + " contains duplicate value: " + element);
            }
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    static <K, V> Map<K, V> sortedMap(
            Map<K, V> values,
            Comparator<K> comparator,
            String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        Map<K, V> sorted = new TreeMap<>(comparator);
        values.forEach((key, value) -> {
            K actualKey = Objects.requireNonNull(key, name + " key");
            V actualValue = Objects.requireNonNull(value, name + " value");
            if (sorted.put(actualKey, actualValue) != null) {
                throw new IllegalArgumentException(name + " contains duplicate key: " + actualKey);
            }
        });
        return Map.copyOf(sorted);
    }
}
