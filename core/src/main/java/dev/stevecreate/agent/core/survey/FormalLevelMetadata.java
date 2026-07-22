package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

/** Loader-neutral subset of level.dat needed to identify a world without opening it in Minecraft. */
public record FormalLevelMetadata(
        String levelName,
        int dataVersion,
        long lastPlayedEpochMillis,
        String versionName,
        List<String> dimensions,
        String levelDatSha256) {
    public FormalLevelMetadata {
        levelName = text(levelName, "levelName");
        if (dataVersion <= 0) throw new IllegalArgumentException("dataVersion must be positive");
        if (lastPlayedEpochMillis < 0) throw new IllegalArgumentException("lastPlayedEpochMillis must be non-negative");
        versionName = text(versionName, "versionName");
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        if (dimensions.isEmpty() || dimensions.size() > 64
                || dimensions.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 512)
                || dimensions.stream().distinct().count() != dimensions.size()) {
            throw new IllegalArgumentException("dimensions are empty, duplicate or invalid");
        }
        dimensions = dimensions.stream().sorted().toList();
        Objects.requireNonNull(levelDatSha256, "levelDatSha256");
        if (!levelDatSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("levelDatSha256 must be lowercase SHA-256 hex");
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
