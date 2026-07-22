package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

/** Complete bounded refusal context shared by offline discovery and in-game pilot commands. */
public record WritableTestWorldFailure(
        WritableTestWorldFailureCode code,
        WritableTestWorldStage stage,
        String testInstanceIdentity,
        String worldIdentity,
        String canonicalWorldPath,
        String worldFingerprint,
        String region,
        String previewHash,
        String backupIdentity,
        String sessionIdentity,
        List<String> evidence,
        String reason,
        String safeNextStep) {
    public static final String UNAVAILABLE = "unavailable";

    public WritableTestWorldFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        testInstanceIdentity = text(testInstanceIdentity, "testInstanceIdentity");
        worldIdentity = text(worldIdentity, "worldIdentity");
        canonicalWorldPath = text(canonicalWorldPath, "canonicalWorldPath");
        worldFingerprint = text(worldFingerprint, "worldFingerprint");
        region = text(region, "region");
        previewHash = text(previewHash, "previewHash");
        backupIdentity = text(backupIdentity, "backupIdentity");
        sessionIdentity = text(sessionIdentity, "sessionIdentity");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64 || evidence.stream().anyMatch(item ->
                item == null || item.isBlank() || item.length() > 4_096)) {
            throw new IllegalArgumentException("evidence is empty or invalid");
        }
        reason = text(reason, "reason");
        safeNextStep = text(safeNextStep, "safeNextStep");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
