package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Exact isolated writable identity produced by discovery and rechecked by the guard. */
public record WritableTestWorldIdentity(
        String value,
        String sourceWorldIdentity,
        String levelName,
        String saveDirectoryName,
        Path canonicalGameDirectory,
        Path canonicalWorldPath,
        String runtimeFingerprint,
        String worldFingerprint,
        String testInstanceIdentity,
        WritableTestWorldOccupancy occupancy,
        List<String> evidence) {
    public static final String REQUIRED_MARKER = "ISOLATED_WRITABLE_TEST_WORLD";

    public WritableTestWorldIdentity {
        Objects.requireNonNull(value, "value");
        if (!value.matches("test-world:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be a test-world SHA-256 identity");
        }
        sourceWorldIdentity = WorldEnvironmentEvidence.text(
                sourceWorldIdentity, "sourceWorldIdentity");
        levelName = WorldEnvironmentEvidence.text(levelName, "levelName");
        saveDirectoryName = WorldEnvironmentEvidence.text(saveDirectoryName, "saveDirectoryName");
        Objects.requireNonNull(canonicalGameDirectory, "canonicalGameDirectory");
        Objects.requireNonNull(canonicalWorldPath, "canonicalWorldPath");
        runtimeFingerprint = WorldEnvironmentEvidence.text(
                runtimeFingerprint, "runtimeFingerprint");
        worldFingerprint = WorldEnvironmentEvidence.text(
                worldFingerprint, "worldFingerprint");
        testInstanceIdentity = WorldEnvironmentEvidence.text(
                testInstanceIdentity, "testInstanceIdentity");
        Objects.requireNonNull(occupancy, "occupancy");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) throw new IllegalArgumentException("evidence is empty");
    }
}
