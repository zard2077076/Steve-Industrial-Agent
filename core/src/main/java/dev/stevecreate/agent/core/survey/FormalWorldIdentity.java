package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

/** Stable logical identity is separate from the mutable level.dat snapshot fingerprint. */
public record FormalWorldIdentity(
        String value,
        String levelName,
        String saveDirectoryName,
        int dataVersion,
        String versionName,
        List<String> dimensions) {
    public FormalWorldIdentity {
        Objects.requireNonNull(value, "value");
        if (!value.matches("world:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be a world SHA-256 identity");
        }
        levelName = text(levelName, "levelName");
        saveDirectoryName = text(saveDirectoryName, "saveDirectoryName");
        if (dataVersion <= 0) throw new IllegalArgumentException("dataVersion must be positive");
        versionName = text(versionName, "versionName");
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        if (dimensions.isEmpty() || dimensions.size() > 64) {
            throw new IllegalArgumentException("dimensions are empty or too large");
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 4_096) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
