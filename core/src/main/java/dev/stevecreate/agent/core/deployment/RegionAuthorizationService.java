package dev.stevecreate.agent.core.deployment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure exact-scope comparison with no world, filesystem, session or mutation access. */
public final class RegionAuthorizationService {
    public RegionAuthorizationCheck check(
            RegionAuthorization authorization,
            RegionAuthorizationRequest request,
            Instant evaluatedAt) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        List<RegionAuthorizationFailure> failures = new ArrayList<>();

        if (authorization.environmentClassification() != request.environmentClassification()) {
            failures.add(RegionAuthorizationFailure.ENVIRONMENT_MISMATCH);
        }
        if (!authorization.worldIdentity().equals(request.worldIdentity())) {
            failures.add(RegionAuthorizationFailure.WORLD_IDENTITY_MISMATCH);
        }
        if (!authorization.dimensionId().equals(request.dimensionId())) {
            failures.add(RegionAuthorizationFailure.DIMENSION_MISMATCH);
        }
        if (!authorization.ownerIdentity().equals(request.ownerIdentity())) {
            failures.add(RegionAuthorizationFailure.OWNER_IDENTITY_MISMATCH);
        }
        if (!contains(authorization.regionBounds(), request.requestedBounds())) {
            failures.add(RegionAuthorizationFailure.REGION_OUT_OF_SCOPE);
        }
        if (!authorization.allowedOperations().containsAll(request.requestedOperations())) {
            failures.add(RegionAuthorizationFailure.OPERATION_NOT_ALLOWED);
        }
        if (request.requestedBlockMutations() > authorization.maximumBlockMutations()) {
            failures.add(RegionAuthorizationFailure.MUTATION_BUDGET_EXCEEDED);
        }
        if (!authorization.previewHash().equals(request.previewHash())) {
            failures.add(RegionAuthorizationFailure.PREVIEW_HASH_MISMATCH);
        }
        if (!authorization.worldSnapshotFingerprint().equals(
                request.worldSnapshotFingerprint())) {
            failures.add(RegionAuthorizationFailure.WORLD_SNAPSHOT_MISMATCH);
        }
        if (!authorization.runtimeFingerprint().equals(request.runtimeFingerprint())) {
            failures.add(RegionAuthorizationFailure.RUNTIME_FINGERPRINT_MISMATCH);
        }
        if (!evaluatedAt.isBefore(authorization.expiresAt())) {
            failures.add(RegionAuthorizationFailure.EXPIRED);
        }
        if (authorization.approvalState() != RegionApprovalState.APPROVED) {
            failures.add(RegionAuthorizationFailure.NOT_APPROVED);
        }
        if (authorization.revocationState() == RegionRevocationState.REVOKED) {
            failures.add(RegionAuthorizationFailure.REVOKED);
        }
        if (authorization.oneTimeUse() && authorization.useState() == RegionUseState.CONSUMED) {
            failures.add(RegionAuthorizationFailure.ONE_TIME_USE_ALREADY_CONSUMED);
        }
        if (authorization.environmentClassification() == WorldEnvironmentType.FORMAL_PLAYER_WORLD) {
            failures.add(RegionAuthorizationFailure.FORMAL_WORLD_EXECUTION_FORBIDDEN);
        }
        return new RegionAuthorizationCheck(failures, failures.isEmpty());
    }

    private static boolean contains(
            DeploymentBoundingBox outer,
            DeploymentBoundingBox inner) {
        return outer.contains(inner.minimum()) && outer.contains(inner.maximum());
    }
}
