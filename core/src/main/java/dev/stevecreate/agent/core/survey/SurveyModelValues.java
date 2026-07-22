package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

final class SurveyModelValues {
    private SurveyModelValues() {}

    static String text(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is not SHA-256");
        return value;
    }

    static List<String> texts(List<String> values, String name, int maximum) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > maximum || copy.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 4_096)
                || copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return copy;
    }
}
