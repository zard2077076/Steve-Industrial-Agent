package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Immutable selection evidence. It grants no write/session authority. */
public record PilotRegionSelection(
        String selectionIdentity,
        String writableWorldIdentity,
        ResourceId dimension,
        DeploymentBoundingBox bounds,
        String regionHash,
        String playerIdentity,
        String sessionIdentity,
        Instant selectedAt,
        Instant expiresAt,
        String policyVersion,
        PilotRegionState state,
        String previewWorldFingerprint) {
    public PilotRegionSelection {
        selectionIdentity = text(selectionIdentity, "selectionIdentity");
        writableWorldIdentity = text(writableWorldIdentity, "writableWorldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(bounds, "bounds");
        regionHash = hash(regionHash, "regionHash");
        playerIdentity = text(playerIdentity, "playerIdentity");
        sessionIdentity = text(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(selectedAt, "selectedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!selectedAt.isBefore(expiresAt)) throw new IllegalArgumentException("expiry must be later");
        policyVersion = text(policyVersion, "policyVersion");
        Objects.requireNonNull(state, "state");
        previewWorldFingerprint = text(previewWorldFingerprint, "previewWorldFingerprint");
        if (state == PilotRegionState.SELECTED
                && !WritableTestWorldFailure.UNAVAILABLE.equals(previewWorldFingerprint)) {
            throw new IllegalArgumentException("selected region cannot carry preview fingerprint");
        }
        if (state != PilotRegionState.SELECTED
                && WritableTestWorldFailure.UNAVAILABLE.equals(previewWorldFingerprint)) {
            throw new IllegalArgumentException("previewed/confirmed region requires fingerprint");
        }
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
