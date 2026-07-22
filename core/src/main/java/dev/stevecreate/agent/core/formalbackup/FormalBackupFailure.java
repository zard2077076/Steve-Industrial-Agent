package dev.stevecreate.agent.core.formalbackup;

import java.util.List;
import java.util.Objects;

/** Complete typed Stage-B failure context; unavailable values remain explicit strings. */
public record FormalBackupFailure(
        FormalBackupFailureCode code,
        FormalBackupStage stage,
        String worldIdentity,
        String backupIdentity,
        String candidateIdentity,
        String canonicalSourcePath,
        String canonicalDestinationPath,
        String sourceFingerprint,
        String runtimeFingerprint,
        long requiredSpaceBytes,
        long actualSpaceBytes,
        String manifestIdentity,
        String approvalState,
        List<String> evidence,
        String reason,
        String safeNextStep) {
    public FormalBackupFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        worldIdentity = text(worldIdentity, "worldIdentity");
        backupIdentity = text(backupIdentity, "backupIdentity");
        candidateIdentity = text(candidateIdentity, "candidateIdentity");
        canonicalSourcePath = text(canonicalSourcePath, "canonicalSourcePath");
        canonicalDestinationPath = text(canonicalDestinationPath, "canonicalDestinationPath");
        sourceFingerprint = text(sourceFingerprint, "sourceFingerprint");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        if (requiredSpaceBytes < 0 || actualSpaceBytes < 0) {
            throw new IllegalArgumentException("space context is negative");
        }
        manifestIdentity = text(manifestIdentity, "manifestIdentity");
        approvalState = text(approvalState, "approvalState");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 128
                || evidence.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 4_096)) {
            throw new IllegalArgumentException("failure evidence is invalid");
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
