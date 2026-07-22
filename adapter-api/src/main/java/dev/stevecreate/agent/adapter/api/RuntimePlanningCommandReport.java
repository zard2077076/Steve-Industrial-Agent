package dev.stevecreate.agent.adapter.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded loader-neutral command presentation for a runtime planning result. */
public record RuntimePlanningCommandReport(
        boolean success,
        int commandReturn,
        List<String> userLines,
        String structuredLog) {
    public static final int MAX_USER_LINES = 8;
    public static final int MAX_LINE_LENGTH = 16_384;
    public static final int MAX_STRUCTURED_LOG_LENGTH = 65_536;

    public RuntimePlanningCommandReport {
        if (commandReturn != (success ? 1 : 0)) {
            throw new IllegalArgumentException(
                    "Command return must be 1 for success and 0 for failure");
        }
        Objects.requireNonNull(userLines, "userLines");
        if (userLines.isEmpty() || userLines.size() > MAX_USER_LINES) {
            throw new IllegalArgumentException("userLines count violates command-report bounds");
        }
        List<String> lines = new ArrayList<>(userLines.size());
        userLines.forEach(value -> lines.add(requireText(
                value, "userLines element", MAX_LINE_LENGTH)));
        userLines = List.copyOf(lines);
        structuredLog = requireText(
                structuredLog, "structuredLog", MAX_STRUCTURED_LOG_LENGTH);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximumLength + " characters");
        }
        return value;
    }
}
