package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure SP-05 issuance and exact one-time token validation. */
public final class DemolitionApprovalService {
    public DemolitionApprovalToken issue(
            SiteSurveySnapshot survey,
            DemolitionPreview preview,
            DemolitionApprovalRequest request) {
        Objects.requireNonNull(survey, "survey");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(request, "request");
        if (!preview.worldIdentity().equals(survey.worldIdentity())
                || !preview.planHash().equals(survey.planHash())
                || !preview.siteSnapshotHash().equals(survey.siteSnapshotHash())) {
            throw new IllegalArgumentException("DEMOLITION_APPROVAL_STALE");
        }
        if (request.expiresAt().isAfter(preview.expiresAt())) {
            throw new IllegalArgumentException("DEMOLITION_APPROVAL_EXPIRED");
        }
        Set<String> requested = request.approvedObstacleIds();
        List<ObstacleFinding> selected = survey.findings().stream()
                .filter(value -> requested.contains(value.obstacleId()))
                .sorted(Comparator.comparing(ObstacleFinding::obstacleId)).toList();
        if (selected.size() != requested.size()) {
            throw new IllegalArgumentException("DEMOLITION_APPROVAL_SCOPE_MISMATCH");
        }
        if (selected.stream().anyMatch(value -> !value.classification().approvable())) {
            throw new IllegalArgumentException("PROTECTED_OBSTACLE_PRESENT");
        }
        if (selected.size() > request.maximumMutations()) {
            throw new IllegalArgumentException("DEMOLITION_BUDGET_EXCEEDED");
        }
        List<ApprovedObstacle> approved = selected.stream().map(value -> new ApprovedObstacle(
                value.obstacleId(), value.position(), value.blockStateFingerprint(),
                value.classification())).toList();
        String identityHash = SitePreparationHashes.sha256(survey.worldIdentity() + "\n"
                + survey.dimension() + "\n" + survey.planHash() + "\n"
                + survey.siteSnapshotHash() + "\n" + preview.approvalHash() + "\n"
                + request.playerIdentity() + "\n" + request.context() + "\n"
                + approved + "\n" + request.expiresAt());
        return new DemolitionApprovalToken("demolition-approval:" + identityHash,
                survey.worldIdentity(), survey.dimension(), survey.planHash(),
                survey.siteSnapshotHash(), preview.approvalHash(), request.playerIdentity(),
                request.context(), approved, request.requestedAt(), request.expiresAt(), request.maximumMutations(),
                DemolitionApprovalState.ACTIVE);
    }

    public DemolitionApprovalCheck check(
            DemolitionApprovalToken token,
            String worldIdentity,
            ResourceId dimension,
            String planHash,
            String siteSnapshotHash,
            String playerIdentity,
            List<ObstacleObservation> currentObservations,
            int requestedMutations,
            Instant now) {
        return check(token, worldIdentity, dimension, planHash, siteSnapshotHash,
                playerIdentity, token.context(), currentObservations, requestedMutations, now);
    }

    public DemolitionApprovalCheck check(
            DemolitionApprovalToken token,
            String worldIdentity,
            ResourceId dimension,
            String planHash,
            String siteSnapshotHash,
            String playerIdentity,
            DemolitionApprovalContext expectedContext,
            List<ObstacleObservation> currentObservations,
            int requestedMutations,
            Instant now) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expectedContext, "expectedContext");
        List<DemolitionApprovalFailure> failures = new ArrayList<>();
        if (!token.worldIdentity().equals(worldIdentity)) failures.add(DemolitionApprovalFailure.WORLD_MISMATCH);
        if (!token.dimension().equals(dimension)) failures.add(DemolitionApprovalFailure.DIMENSION_MISMATCH);
        if (!token.planHash().equals(normalizeHash(planHash))) failures.add(DemolitionApprovalFailure.PLAN_HASH_MISMATCH);
        if (!token.siteSnapshotHash().equals(normalizeHash(siteSnapshotHash))) {
            failures.add(DemolitionApprovalFailure.SITE_SNAPSHOT_STALE);
        }
        if (!token.playerIdentity().equals(playerIdentity)) failures.add(DemolitionApprovalFailure.PLAYER_MISMATCH);
        if (!token.context().equals(expectedContext)) {
            failures.add(DemolitionApprovalFailure.CONTEXT_MISMATCH);
        }
        if (!now.isBefore(token.expiresAt())) failures.add(DemolitionApprovalFailure.EXPIRED);
        if (token.state() == DemolitionApprovalState.REVOKED) failures.add(DemolitionApprovalFailure.REVOKED);
        if (token.state() == DemolitionApprovalState.CONSUMED) failures.add(DemolitionApprovalFailure.ALREADY_CONSUMED);
        if (requestedMutations < 0 || requestedMutations > token.maximumMutations()) {
            failures.add(DemolitionApprovalFailure.MUTATION_BUDGET_EXCEEDED);
        }
        Set<String> current = new HashSet<>();
        for (ObstacleObservation observation : currentObservations) {
            current.add(observation.position() + "|" + observation.blockStateFingerprint());
        }
        boolean exact = token.approvedObstacles().stream().allMatch(value ->
                current.contains(value.position() + "|" + value.blockStateFingerprint()));
        if (!exact || current.size() != token.approvedObstacles().size()) {
            failures.add(DemolitionApprovalFailure.SCOPE_MISMATCH);
        }
        return new DemolitionApprovalCheck(failures.isEmpty(), failures);
    }

    public DemolitionApprovalToken revoke(DemolitionApprovalToken token) {
        Objects.requireNonNull(token, "token");
        return withState(token, DemolitionApprovalState.REVOKED);
    }

    public DemolitionApprovalToken consume(DemolitionApprovalToken token) {
        Objects.requireNonNull(token, "token");
        if (token.state() != DemolitionApprovalState.ACTIVE) {
            throw new IllegalArgumentException("approval token is not active");
        }
        return withState(token, DemolitionApprovalState.CONSUMED);
    }

    private static DemolitionApprovalToken withState(
            DemolitionApprovalToken token,
            DemolitionApprovalState state) {
        return new DemolitionApprovalToken(token.tokenIdentity(), token.worldIdentity(),
                token.dimension(), token.planHash(), token.siteSnapshotHash(), token.approvalHash(),
                token.playerIdentity(), token.context(), token.approvedObstacles(), token.issuedAt(),
                token.expiresAt(), token.maximumMutations(), state);
    }

    private static String normalizeHash(String value) {
        return SitePreparationHashes.hash(value, "hash");
    }
}
