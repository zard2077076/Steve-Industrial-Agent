package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FormalResourceInventoryRequestFactory {
    public FormalResourceInventoryAuthorizationRequest create(
            FormalDeploymentCandidatePackage candidate,
            List<BlockPos3i> containerPositions,
            Set<GenericResourceType> categories,
            String purpose,
            String minimizationScope,
            Instant expiresAt) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(containerPositions, "containerPositions");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!containerPositions.stream().allMatch(candidate.exactBoundingBox()::contains)
                || expiresAt.isAfter(candidate.expiresAt())) {
            throw new IllegalArgumentException("inventory request escaped candidate bounds or expiration");
        }
        List<BlockPos3i> positions = containerPositions.stream()
                .sorted(java.util.Comparator.comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z)).toList();
        MessageDigest digest = FormalWorldBackupManifest.digest();
        FormalWorldBackupManifest.update(digest, candidate.worldIdentity());
        FormalWorldBackupManifest.update(digest, candidate.packageIdentity());
        positions.forEach(value -> FormalWorldBackupManifest.update(digest, value.toString()));
        categories.stream().sorted().forEach(value -> FormalWorldBackupManifest.update(digest, value.name()));
        FormalWorldBackupManifest.update(digest, purpose);
        FormalWorldBackupManifest.update(digest, minimizationScope);
        FormalWorldBackupManifest.update(digest, expiresAt.toString());
        return new FormalResourceInventoryAuthorizationRequest(
                "formal-inventory-request:" + HexFormat.of().formatHex(digest.digest()),
                candidate.worldIdentity(), candidate.packageIdentity(), positions, categories,
                purpose, minimizationScope, expiresAt, true, false, false);
    }
}
