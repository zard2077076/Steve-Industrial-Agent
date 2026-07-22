package dev.stevecreate.agent.core.formalbackup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Trusted internal FB-01 input; public commands must resolve registered identities before constructing it. */
public record FormalBackupDestinationRequest(
        String expectedWorldIdentity,
        String observedWorldIdentity,
        Path formalPlatformRoot,
        Path formalInstanceRoot,
        Path sourceWorldRoot,
        Path repositoryRoot,
        Path backupRoot,
        String sourceFingerprint,
        String runtimeFingerprint,
        long sourceFileCount,
        long estimatedSourceBytes,
        long safetyMarginBytes,
        Instant requestedAt,
        String policyVersion,
        String toolVersion,
        String gitHead) {
    public FormalBackupDestinationRequest {
        expectedWorldIdentity = world(expectedWorldIdentity, "expectedWorldIdentity");
        observedWorldIdentity = world(observedWorldIdentity, "observedWorldIdentity");
        Objects.requireNonNull(formalPlatformRoot, "formalPlatformRoot");
        Objects.requireNonNull(formalInstanceRoot, "formalInstanceRoot");
        Objects.requireNonNull(sourceWorldRoot, "sourceWorldRoot");
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(backupRoot, "backupRoot");
        sourceFingerprint = hash(sourceFingerprint, "sourceFingerprint");
        runtimeFingerprint = hash(runtimeFingerprint, "runtimeFingerprint");
        if (sourceFileCount < 1 || sourceFileCount > 1_000_000
                || estimatedSourceBytes < 1 || safetyMarginBytes < 1) {
            throw new IllegalArgumentException("source estimate or safety margin is invalid");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (requestedAt.isBefore(Instant.EPOCH)) throw new IllegalArgumentException("requestedAt is before epoch");
        policyVersion = text(policyVersion, "policyVersion");
        toolVersion = text(toolVersion, "toolVersion");
        Objects.requireNonNull(gitHead, "gitHead");
        if (!gitHead.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("gitHead is invalid");
    }

    private static String world(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("world:[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    private static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
