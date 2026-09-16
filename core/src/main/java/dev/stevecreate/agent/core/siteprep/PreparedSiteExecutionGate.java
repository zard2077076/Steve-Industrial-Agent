package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Fail-closed identity, freshness, orientation and footprint bridge into construction. */
public final class PreparedSiteExecutionGate {
    public AuthorizationResult authorize(
            PreparedConstructionSite prepared,
            ConfirmedSiteSelection selection,
            ExecutionReadyPlan ready,
            PlanningEvidence planning,
            Instant now) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(planning, "planning");
        Objects.requireNonNull(now, "now");

        AuthorizationResult identity = validateIdentity(prepared, selection, ready, planning, now);
        if (identity instanceof Refused) return identity;

        VerifiedPhysicalPlan physical = ready.physicalPlan();
        if (physical.placements().get(0).anchor().equals(prepared.anchor().position()) == false) {
            return refused(Failure.PLAN_ANCHOR_MISMATCH,
                    "VerifiedPhysicalPlan does not start at the player-selected prepared anchor");
        }
        QuarterTurn expected = quarterTurn(prepared.facing());
        if (physical.placements().stream().map(PhysicalMachinePlacement::orientation)
                .anyMatch(value -> value != expected)) {
            return refused(Failure.PLAN_FACING_MISMATCH,
                    "VerifiedPhysicalPlan orientation differs from the confirmed site facing");
        }

        Set<BlockPos3i> cells = coveredCells(physical);
        if (cells.isEmpty() || cells.size() > 4_096) {
            return refused(Failure.PLAN_FOOTPRINT_INVALID,
                    "VerifiedPhysicalPlan footprint is empty or exceeds the bounded bridge");
        }
        if (cells.stream().anyMatch(value -> !selection.authorizedBounds().contains(value))) {
            return refused(Failure.PLAN_OUTSIDE_AUTHORIZED_REGION,
                    "VerifiedPhysicalPlan extends outside the player-confirmed site region");
        }
        Set<BlockPos3i> protectedCells = prepared.remainingProtectedFindings().stream()
                .map(ObstacleFinding::position).collect(java.util.stream.Collectors.toSet());
        if (cells.stream().anyMatch(protectedCells::contains)) {
            return refused(Failure.PROTECTED_BLOCK_CONFLICT,
                    "VerifiedPhysicalPlan intersects a protected post-clearance finding");
        }

        String canonicalCells = cells.stream().sorted(Comparator
                        .comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z))
                .map(BlockPos3i::toString).collect(java.util.stream.Collectors.joining("\n"));
        String evidenceHash = SitePreparationHashes.sha256(
                prepared.preparedSiteIdentity() + "\n" + selection.selectionHash() + "\n"
                        + ready.sessionId() + "\n" + physical.id() + "\n"
                        + planning.physicalSnapshotFingerprint() + "\n" + canonicalCells);
        return new Authorized(new PreparedSiteExecutionAuthorization(
                prepared.preparedSiteIdentity(), selection.selectionHash(),
                prepared.worldIdentity(), prepared.dimension(),
                prepared.cleanSiteSnapshotHash(), planning.physicalSnapshotFingerprint(),
                ready, cells, now,
                prepared.expiresAt().isBefore(selection.expiresAt())
                        ? prepared.expiresAt() : selection.expiresAt(),
                evidenceHash, planning.provenance()));
    }

    private static AuthorizationResult validateIdentity(
            PreparedConstructionSite prepared,
            ConfirmedSiteSelection selection,
            ExecutionReadyPlan ready,
            PlanningEvidence planning,
            Instant now) {
        if (now.isBefore(prepared.preparedAt()) || !now.isBefore(prepared.expiresAt())
                || !now.isBefore(selection.expiresAt())) {
            return refused(Failure.PREPARED_SITE_EXPIRED,
                    "Prepared site or confirmed selection is not fresh");
        }
        if (!prepared.worldIdentity().equals(selection.anchor().worldIdentity())
                || !prepared.dimension().equals(selection.anchor().dimension())
                || !prepared.anchor().equals(selection.anchor())
                || prepared.facing() != selection.facing()) {
            return refused(Failure.SITE_SELECTION_MISMATCH,
                    "Prepared site no longer matches its exact selection evidence");
        }
        if (!planning.authoritativeServerSnapshot()
                || !planning.worldIdentity().equals(prepared.worldIdentity())
                || !planning.dimension().equals(prepared.dimension())
                || !planning.preparedSiteIdentity().equals(prepared.preparedSiteIdentity())
                || !planning.cleanSiteSnapshotHash().equals(prepared.cleanSiteSnapshotHash())
                || !planning.physicalPlanId().equals(ready.physicalPlan().id())
                || !planning.physicalSnapshotFingerprint().equals(
                        ready.physicalPlan().candidate().snapshotFingerprint())
                || planning.capturedAt().isBefore(prepared.preparedAt())
                || planning.capturedAt().isAfter(now)) {
            return refused(Failure.PLANNING_SNAPSHOT_MISMATCH,
                    "Planning snapshot is not an authoritative post-preparation capture");
        }
        return IdentityAccepted.INSTANCE;
    }

    private static Set<BlockPos3i> coveredCells(VerifiedPhysicalPlan plan) {
        Set<BlockPos3i> cells = new LinkedHashSet<>();
        plan.placements().forEach(placement -> {
            cells.add(placement.anchor());
            placement.components().forEach(component -> cells.add(component.position()));
            placement.ports().values().forEach(port -> cells.add(port.position()));
            cells.addAll(placement.clearance());
            cells.addAll(placement.rotationalPowerRoute());
        });
        plan.routes().forEach(route -> cells.addAll(route.positions()));
        return Set.copyOf(cells);
    }

    private static QuarterTurn quarterTurn(SiteFacing facing) {
        return switch (facing) {
            case NORTH -> QuarterTurn.ZERO;
            case EAST -> QuarterTurn.CLOCKWISE_90;
            case SOUTH -> QuarterTurn.CLOCKWISE_180;
            case WEST -> QuarterTurn.CLOCKWISE_270;
        };
    }

    private static Refused refused(Failure failure, String detail) {
        return new Refused(failure, detail);
    }

    public enum Failure {
        PREPARED_SITE_EXPIRED,
        SITE_SELECTION_MISMATCH,
        PLANNING_SNAPSHOT_MISMATCH,
        PLAN_ANCHOR_MISMATCH,
        PLAN_FACING_MISMATCH,
        PLAN_FOOTPRINT_INVALID,
        PLAN_OUTSIDE_AUTHORIZED_REGION,
        PROTECTED_BLOCK_CONFLICT
    }

    public sealed interface AuthorizationResult permits Authorized, Refused, IdentityAccepted {}

    public record Authorized(PreparedSiteExecutionAuthorization authorization)
            implements AuthorizationResult {
        public Authorized {
            Objects.requireNonNull(authorization, "authorization");
        }
    }

    public record Refused(Failure failure, String detail) implements AuthorizationResult {
        public Refused {
            Objects.requireNonNull(failure, "failure");
            detail = SitePreparationHashes.text(detail, "detail");
        }
    }

    private enum IdentityAccepted implements AuthorizationResult { INSTANCE }

    public record PlanningEvidence(
            String worldIdentity,
            ResourceId dimension,
            String preparedSiteIdentity,
            String cleanSiteSnapshotHash,
            ResourceId physicalPlanId,
            String physicalSnapshotFingerprint,
            Instant capturedAt,
            boolean authoritativeServerSnapshot,
            String provenance) {
        public PlanningEvidence {
            worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
            Objects.requireNonNull(dimension, "dimension");
            preparedSiteIdentity = SitePreparationHashes.text(
                    preparedSiteIdentity, "preparedSiteIdentity");
            cleanSiteSnapshotHash = SitePreparationHashes.hash(
                    cleanSiteSnapshotHash, "cleanSiteSnapshotHash");
            Objects.requireNonNull(physicalPlanId, "physicalPlanId");
            physicalSnapshotFingerprint = SitePreparationHashes.text(
                    physicalSnapshotFingerprint, "physicalSnapshotFingerprint");
            Objects.requireNonNull(capturedAt, "capturedAt");
            provenance = SitePreparationHashes.text(provenance, "provenance");
        }
    }
}
