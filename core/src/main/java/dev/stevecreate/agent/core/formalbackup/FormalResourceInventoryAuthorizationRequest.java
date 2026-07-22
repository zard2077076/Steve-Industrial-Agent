package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Future authorization request only; it contains no inventory read or result API. */
public record FormalResourceInventoryAuthorizationRequest(
        String requestIdentity,
        String worldIdentity,
        String candidatePackageIdentity,
        List<BlockPos3i> containerPositions,
        Set<GenericResourceType> requestedResourceCategories,
        String purpose,
        String dataMinimizationScope,
        Instant expiresAt,
        boolean noWriteGuarantee,
        boolean requestExecuted,
        boolean inventoryContentsRead) {
    public FormalResourceInventoryAuthorizationRequest {
        Objects.requireNonNull(requestIdentity, "requestIdentity");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(candidatePackageIdentity, "candidatePackageIdentity");
        containerPositions = List.copyOf(Objects.requireNonNull(containerPositions, "containerPositions"));
        requestedResourceCategories = Set.copyOf(Objects.requireNonNull(
                requestedResourceCategories, "requestedResourceCategories"));
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(dataMinimizationScope, "dataMinimizationScope");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!requestIdentity.matches("formal-inventory-request:[0-9a-f]{64}")
                || !worldIdentity.matches("world:[0-9a-f]{64}")
                || !candidatePackageIdentity.matches("formal-candidate:[0-9a-f]{64}")
                || containerPositions.isEmpty() || containerPositions.size() > 64
                || containerPositions.stream().distinct().count() != containerPositions.size()
                || requestedResourceCategories.isEmpty() || purpose.isBlank()
                || dataMinimizationScope.isBlank() || !noWriteGuarantee
                || requestExecuted || inventoryContentsRead) {
            throw new IllegalArgumentException("inventory authorization request crossed its request-only boundary");
        }
    }
}
