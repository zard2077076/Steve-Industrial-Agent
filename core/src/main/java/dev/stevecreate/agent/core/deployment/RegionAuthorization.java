package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable authorization evidence; it is not a write permit or executable capability. */
public record RegionAuthorization(
        String authorizationId,
        String worldIdentity,
        WorldEnvironmentType environmentClassification,
        ResourceId dimensionId,
        DeploymentBoundingBox regionBounds,
        Optional<String> ownerIdentity,
        String authorizerIdentity,
        Set<RegionAuthorizedOperation> allowedOperations,
        int maximumBlockMutations,
        Instant expiresAt,
        String previewHash,
        String worldSnapshotFingerprint,
        String runtimeFingerprint,
        boolean oneTimeUse,
        RegionApprovalState approvalState,
        RegionRevocationState revocationState,
        RegionUseState useState,
        String provenance) {
    private static final Set<RegionAuthorizedOperation> READ_ONLY_OPERATIONS = Set.of(
            RegionAuthorizedOperation.READ_ONLY_SCAN, RegionAuthorizedOperation.DRY_RUN);

    public RegionAuthorization {
        authorizationId = text(authorizationId, "authorizationId");
        worldIdentity = text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(regionBounds, "regionBounds");
        ownerIdentity = owner(ownerIdentity);
        authorizerIdentity = text(authorizerIdentity, "authorizerIdentity");
        allowedOperations = operations(allowedOperations);
        if (maximumBlockMutations < 0
                || maximumBlockMutations > DeploymentPolicy.MAX_AFFECTED_BLOCKS) {
            throw new IllegalArgumentException("maximumBlockMutations is outside its bound");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        previewHash = hash(previewHash, "previewHash");
        worldSnapshotFingerprint = text(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(revocationState, "revocationState");
        Objects.requireNonNull(useState, "useState");
        provenance = text(provenance, "provenance");

        if (nonExecutableEnvironment(environmentClassification)) {
            if (!READ_ONLY_OPERATIONS.containsAll(allowedOperations)
                    || maximumBlockMutations != 0
                    || approvalState == RegionApprovalState.APPROVED) {
                throw new IllegalArgumentException(
                        "formal, unknown and forbidden regions cannot gain live authority");
            }
        }
        if (!oneTimeUse && useState == RegionUseState.CONSUMED) {
            throw new IllegalArgumentException("reusable authorization cannot be consumed");
        }
    }

    private static boolean nonExecutableEnvironment(WorldEnvironmentType type) {
        return type == WorldEnvironmentType.FORMAL_PLAYER_WORLD
                || type == WorldEnvironmentType.UNKNOWN_WORLD
                || type == WorldEnvironmentType.FORBIDDEN_WORLD;
    }

    static String text(String value, String name) {
        return WorldEnvironmentEvidence.text(value, name);
    }

    static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
        }
        return value;
    }

    static Optional<String> owner(Optional<String> value) {
        Objects.requireNonNull(value, "ownerIdentity");
        return value.map(owner -> text(owner, "ownerIdentity"));
    }

    static Set<RegionAuthorizedOperation> operations(Set<RegionAuthorizedOperation> values) {
        Objects.requireNonNull(values, "allowedOperations");
        if (values.isEmpty() || values.size() > RegionAuthorizedOperation.values().length
                || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("allowedOperations is empty or invalid");
        }
        return Set.copyOf(values);
    }
}
