package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

final class ConstructionContractValues {
    static final int MAX_IDS = 256;
    static final int MAX_PARAMETERS = 64;
    static final int MAX_PARAMETER_VALUE_LENGTH = 512;
    static final int MAX_DETAIL_LENGTH = 2_048;

    private ConstructionContractValues() {
    }

    static String boundedText(String value, String name, int maximumLength, boolean allowEmpty) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isBlank()) || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain "
                    + (allowEmpty ? "0" : "1") + " to " + maximumLength + " characters");
        }
        return value;
    }

    static String fingerprint(String value, String name) {
        return boundedText(value, name, 256, false);
    }

    static Set<ResourceId> sortedIds(Set<ResourceId> values, String name, boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty()) || values.size() > MAX_IDS) {
            throw new IllegalArgumentException(name + " count must be between "
                    + (requireNonEmpty ? 1 : 0) + " and " + MAX_IDS);
        }
        List<ResourceId> ordered = new ArrayList<>();
        for (ResourceId value : values) {
            ordered.add(Objects.requireNonNull(value, name + " element"));
        }
        ordered.sort(Comparator.comparing(ResourceId::toString));
        return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }

    static <T> List<T> uniqueSorted(
            List<T> values,
            String name,
            Function<T, ResourceId> idExtractor,
            boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty()) || values.size() > MAX_IDS) {
            throw new IllegalArgumentException(name + " count must be between "
                    + (requireNonEmpty ? 1 : 0) + " and " + MAX_IDS);
        }
        Map<ResourceId, T> indexed = new LinkedHashMap<>();
        for (T item : values) {
            T value = Objects.requireNonNull(item, name + " element");
            ResourceId id = Objects.requireNonNull(idExtractor.apply(value), name + " id");
            if (indexed.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("duplicate " + name + " id " + id);
            }
        }
        List<T> copy = new ArrayList<>(indexed.values());
        copy.sort(Comparator.comparing(value -> idExtractor.apply(value).toString()));
        return Collections.unmodifiableList(copy);
    }

    static Map<ResourceId, String> parameters(Map<ResourceId, String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException(name + " count exceeds " + MAX_PARAMETERS);
        }
        List<Map.Entry<ResourceId, String>> ordered = new ArrayList<>(values.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)));
        Map<ResourceId, String> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, String> entry : ordered) {
            ResourceId key = Objects.requireNonNull(entry.getKey(), name + " key");
            String value = boundedText(entry.getValue(), name + " value for " + key,
                    MAX_PARAMETER_VALUE_LENGTH, false);
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
