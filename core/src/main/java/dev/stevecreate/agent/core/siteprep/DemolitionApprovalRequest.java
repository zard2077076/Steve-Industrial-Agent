package dev.stevecreate.agent.core.siteprep;

import java.time.Instant;
import java.util.Set;

public record DemolitionApprovalRequest(
        String playerIdentity,
        Set<String> approvedObstacleIds,
        int maximumMutations,
        Instant requestedAt,
        Instant expiresAt,
        DemolitionApprovalContext context) {
    public DemolitionApprovalRequest(
            String playerIdentity,
            Set<String> approvedObstacleIds,
            int maximumMutations,
            Instant requestedAt,
            Instant expiresAt) {
        this(playerIdentity, approvedObstacleIds, maximumMutations, requestedAt, expiresAt,
                DemolitionApprovalContext.legacyAdvancedCommand());
    }

    public DemolitionApprovalRequest {
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        if (approvedObstacleIds == null || approvedObstacleIds.size() > 4_096) {
            throw new IllegalArgumentException("approvedObstacleIds must contain 0..4096 IDs");
        }
        approvedObstacleIds = approvedObstacleIds.stream()
                .map(value -> SitePreparationHashes.text(value, "approvedObstacleId"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maximumMutations < approvedObstacleIds.size() || maximumMutations > 4_096) {
            throw new IllegalArgumentException("maximumMutations must cover 0..4096 approvals");
        }
        if (requestedAt == null || expiresAt == null || !requestedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("approval expiry is invalid");
        }
        if (context == null) throw new IllegalArgumentException("approval context is required");
    }
}
