package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

/** Immutable classification result; dangerous environments have write and execution authority stripped. */
public record WorldEnvironmentDescriptor(
        String environmentId,
        WorldEnvironmentType environmentType,
        String worldIdentity,
        String worldRootIdentity,
        String gameDirectoryIdentity,
        String minecraftVersion,
        String loaderAndModFingerprint,
        String runtimeFingerprint,
        String saveFingerprint,
        String serverIdentity,
        boolean disposable,
        boolean writable,
        boolean backupRequired,
        boolean humanApprovalRequired,
        boolean executionAllowed,
        String provenance,
        List<String> classificationEvidence,
        List<String> warnings) {
    public WorldEnvironmentDescriptor {
        environmentId = WorldEnvironmentEvidence.text(environmentId, "environmentId");
        Objects.requireNonNull(environmentType, "environmentType");
        worldIdentity = WorldEnvironmentEvidence.text(worldIdentity, "worldIdentity");
        worldRootIdentity = WorldEnvironmentEvidence.text(worldRootIdentity, "worldRootIdentity");
        gameDirectoryIdentity = WorldEnvironmentEvidence.text(gameDirectoryIdentity, "gameDirectoryIdentity");
        minecraftVersion = WorldEnvironmentEvidence.text(minecraftVersion, "minecraftVersion");
        loaderAndModFingerprint = WorldEnvironmentEvidence.text(loaderAndModFingerprint, "loaderAndModFingerprint");
        runtimeFingerprint = WorldEnvironmentEvidence.text(runtimeFingerprint, "runtimeFingerprint");
        saveFingerprint = WorldEnvironmentEvidence.text(saveFingerprint, "saveFingerprint");
        serverIdentity = WorldEnvironmentEvidence.text(serverIdentity, "serverIdentity");
        provenance = WorldEnvironmentEvidence.text(provenance, "provenance");
        classificationEvidence = WorldEnvironmentEvidence.texts(classificationEvidence, "classificationEvidence");
        warnings = WorldEnvironmentEvidence.texts(warnings, "warnings");
        if (classificationEvidence.isEmpty()) throw new IllegalArgumentException("classificationEvidence is empty");
        if ((environmentType == WorldEnvironmentType.FORMAL_PLAYER_WORLD
                || environmentType == WorldEnvironmentType.UNKNOWN_WORLD
                || environmentType == WorldEnvironmentType.FORBIDDEN_WORLD)
                && (writable || executionAllowed)) {
            throw new IllegalArgumentException("Dangerous environment cannot be writable or executable");
        }
        if (executionAllowed && !writable) {
            throw new IllegalArgumentException("Execution requires write authority");
        }
    }
}
