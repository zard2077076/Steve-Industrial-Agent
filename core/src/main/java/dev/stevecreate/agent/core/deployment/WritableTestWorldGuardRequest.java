package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.Objects;

public record WritableTestWorldGuardRequest(
        WritableTestWorldOperation operation,
        String currentSourceWorldIdentity,
        Path currentGameDirectory,
        Path currentWorldPath,
        String currentWorldFingerprint,
        String currentMarker,
        WritableTestWorldOccupancy currentOccupancy) {
    public WritableTestWorldGuardRequest {
        Objects.requireNonNull(operation, "operation");
        currentSourceWorldIdentity = WorldEnvironmentEvidence.text(
                currentSourceWorldIdentity, "currentSourceWorldIdentity");
        Objects.requireNonNull(currentGameDirectory, "currentGameDirectory");
        Objects.requireNonNull(currentWorldPath, "currentWorldPath");
        currentWorldFingerprint = WorldEnvironmentEvidence.text(
                currentWorldFingerprint, "currentWorldFingerprint");
        currentMarker = WorldEnvironmentEvidence.text(currentMarker, "currentMarker");
        Objects.requireNonNull(currentOccupancy, "currentOccupancy");
    }
}
