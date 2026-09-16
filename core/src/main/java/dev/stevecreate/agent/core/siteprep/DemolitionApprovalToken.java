package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record DemolitionApprovalToken(
        String tokenIdentity,
        String worldIdentity,
        ResourceId dimension,
        String planHash,
        String siteSnapshotHash,
        String approvalHash,
        String playerIdentity,
        DemolitionApprovalContext context,
        List<ApprovedObstacle> approvedObstacles,
        Instant issuedAt,
        Instant expiresAt,
        int maximumMutations,
        DemolitionApprovalState state) {
    public DemolitionApprovalToken(
            String tokenIdentity,
            String worldIdentity,
            ResourceId dimension,
            String planHash,
            String siteSnapshotHash,
            String approvalHash,
            String playerIdentity,
            List<ApprovedObstacle> approvedObstacles,
            Instant issuedAt,
            Instant expiresAt,
            int maximumMutations,
            DemolitionApprovalState state) {
        this(tokenIdentity, worldIdentity, dimension, planHash, siteSnapshotHash,
                approvalHash, playerIdentity,
                DemolitionApprovalContext.legacyAdvancedCommand(), approvedObstacles,
                issuedAt, expiresAt, maximumMutations, state);
    }

    public DemolitionApprovalToken {
        tokenIdentity = SitePreparationHashes.text(tokenIdentity, "tokenIdentity");
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        planHash = SitePreparationHashes.hash(planHash, "planHash");
        siteSnapshotHash = SitePreparationHashes.hash(siteSnapshotHash, "siteSnapshotHash");
        approvalHash = SitePreparationHashes.hash(approvalHash, "approvalHash");
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        Objects.requireNonNull(context, "context");
        approvedObstacles = List.copyOf(Objects.requireNonNull(approvedObstacles, "approvedObstacles"))
                .stream().sorted(Comparator.comparing(ApprovedObstacle::obstacleId)).toList();
        if (approvedObstacles.size() > 4_096
                || approvedObstacles.stream().map(ApprovedObstacle::obstacleId).distinct().count()
                        != approvedObstacles.size()) {
            throw new IllegalArgumentException("approved obstacles are duplicate or unbounded");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!issuedAt.isBefore(expiresAt)) throw new IllegalArgumentException("token expiry is invalid");
        if (maximumMutations < approvedObstacles.size() || maximumMutations > 4_096) {
            throw new IllegalArgumentException("maximumMutations does not cover approval");
        }
        Objects.requireNonNull(state, "state");
    }
}
