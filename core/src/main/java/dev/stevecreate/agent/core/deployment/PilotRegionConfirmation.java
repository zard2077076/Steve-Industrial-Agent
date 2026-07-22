package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Exact player/session confirmation evidence; later preview-specific authorization remains separate. */
public record PilotRegionConfirmation(
        String confirmationIdentity,
        String writableWorldIdentity,
        ResourceId dimension,
        DeploymentBoundingBox bounds,
        String regionHash,
        String playerIdentity,
        String sessionIdentity,
        Instant expiresAt,
        String policyVersion,
        String worldFingerprint) {
    public PilotRegionConfirmation {
        confirmationIdentity = text(confirmationIdentity, "confirmationIdentity");
        writableWorldIdentity = text(writableWorldIdentity, "writableWorldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(bounds, "bounds");
        regionHash = hash(regionHash, "regionHash");
        playerIdentity = text(playerIdentity, "playerIdentity");
        sessionIdentity = text(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(expiresAt, "expiresAt");
        policyVersion = text(policyVersion, "policyVersion");
        worldFingerprint = text(worldFingerprint, "worldFingerprint");
    }

    private static String text(String value, String name) {
        return WorldEnvironmentEvidence.text(value, name);
    }

    private static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
