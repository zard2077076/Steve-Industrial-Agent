package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Adapter/offline-reader supplied candidate. A directory name alone never makes it writable. */
public record WritableTestWorldCandidate(
        String sourceWorldIdentity,
        String levelName,
        String saveDirectoryName,
        Path gameDirectory,
        Path worldRoot,
        String marker,
        String runtimeFingerprint,
        String worldFingerprint,
        String testInstanceIdentity,
        WorldEnvironmentType environmentType,
        boolean disposable,
        WritableTestWorldOccupancy occupancy,
        List<String> evidence) {
    public WritableTestWorldCandidate {
        sourceWorldIdentity = text(sourceWorldIdentity, "sourceWorldIdentity");
        levelName = text(levelName, "levelName");
        saveDirectoryName = text(saveDirectoryName, "saveDirectoryName");
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(worldRoot, "worldRoot");
        marker = text(marker, "marker");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        worldFingerprint = text(worldFingerprint, "worldFingerprint");
        testInstanceIdentity = text(testInstanceIdentity, "testInstanceIdentity");
        Objects.requireNonNull(environmentType, "environmentType");
        Objects.requireNonNull(occupancy, "occupancy");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64 || evidence.stream().anyMatch(item ->
                item == null || item.isBlank() || item.length() > 4_096)) {
            throw new IllegalArgumentException("evidence is empty or invalid");
        }
    }

    private static String text(String value, String name) {
        return WorldEnvironmentEvidence.text(value, name);
    }
}
