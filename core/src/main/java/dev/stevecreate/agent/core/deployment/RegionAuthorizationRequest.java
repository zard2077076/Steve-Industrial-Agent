package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact requested scope checked against one immutable region authorization. */
public record RegionAuthorizationRequest(
        String worldIdentity,
        WorldEnvironmentType environmentClassification,
        ResourceId dimensionId,
        DeploymentBoundingBox requestedBounds,
        Optional<String> ownerIdentity,
        Set<RegionAuthorizedOperation> requestedOperations,
        int requestedBlockMutations,
        String previewHash,
        String worldSnapshotFingerprint,
        String runtimeFingerprint) {
    public RegionAuthorizationRequest {
        worldIdentity = RegionAuthorization.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(requestedBounds, "requestedBounds");
        ownerIdentity = RegionAuthorization.owner(ownerIdentity);
        requestedOperations = RegionAuthorization.operations(requestedOperations);
        if (requestedBlockMutations < 0
                || requestedBlockMutations > DeploymentPolicy.MAX_AFFECTED_BLOCKS) {
            throw new IllegalArgumentException("requestedBlockMutations is outside its bound");
        }
        previewHash = RegionAuthorization.hash(previewHash, "previewHash");
        worldSnapshotFingerprint = RegionAuthorization.text(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        runtimeFingerprint = RegionAuthorization.text(runtimeFingerprint, "runtimeFingerprint");
    }
}
