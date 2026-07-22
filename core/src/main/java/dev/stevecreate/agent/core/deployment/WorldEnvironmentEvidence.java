package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Adapter-supplied identities and provenance. Directory names alone are never classification evidence. */
public record WorldEnvironmentEvidence(
        String environmentId,
        String worldIdentity,
        Path gameDirectory,
        Path worldRoot,
        String minecraftVersion,
        String loaderAndModFingerprint,
        String runtimeFingerprint,
        String saveFingerprint,
        String serverIdentity,
        WorldEnvironmentType intendedType,
        boolean disposable,
        boolean writableRequested,
        String provenance,
        List<String> classificationEvidence) {
    public static final int MAX_EVIDENCE = 64;

    public WorldEnvironmentEvidence {
        environmentId = text(environmentId, "environmentId");
        worldIdentity = text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(worldRoot, "worldRoot");
        minecraftVersion = text(minecraftVersion, "minecraftVersion");
        loaderAndModFingerprint = text(loaderAndModFingerprint, "loaderAndModFingerprint");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        saveFingerprint = text(saveFingerprint, "saveFingerprint");
        serverIdentity = text(serverIdentity, "serverIdentity");
        Objects.requireNonNull(intendedType, "intendedType");
        provenance = text(provenance, "provenance");
        classificationEvidence = texts(classificationEvidence, "classificationEvidence");
        if (classificationEvidence.isEmpty()) {
            throw new IllegalArgumentException("classificationEvidence cannot be empty");
        }
    }

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }

    static List<String> texts(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_EVIDENCE) throw new IllegalArgumentException(name + " is too large");
        List<String> copy = values.stream().map(value -> text(value, name + " entry")).toList();
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(name + " contains duplicates");
        }
        return copy;
    }
}
