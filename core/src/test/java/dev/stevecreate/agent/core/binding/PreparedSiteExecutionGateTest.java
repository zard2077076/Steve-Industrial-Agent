package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessSuccess;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.AnchorSource;
import dev.stevecreate.agent.core.siteprep.ConfirmedSiteSelection;
import dev.stevecreate.agent.core.siteprep.PlacementAnchor;
import dev.stevecreate.agent.core.siteprep.PreparedConstructionSite;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionAuthorization;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate.Authorized;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate.Failure;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate.PlanningEvidence;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate.Refused;
import dev.stevecreate.agent.core.siteprep.RegionCornerSelection;
import dev.stevecreate.agent.core.siteprep.SalvageLedger;
import dev.stevecreate.agent.core.siteprep.SiteFacing;
import dev.stevecreate.agent.core.siteprep.SiteSelectionService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PreparedSiteExecutionGateTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final ResourceId DIMENSION = ResourceId.parse("minecraft:overworld");
    private static final String PLAYER = "player:prepared-site-fixture";
    private static final Instant PREPARED_AT = Instant.parse("2026-07-27T09:00:00Z");

    @Test
    void exactPreparedSiteSnapshotAndVerifiedPlanProduceAnUnforgeableBridge() {
        Fixture fixture = fixture(false);
        var result = new PreparedSiteExecutionGate().authorize(
                fixture.prepared(), fixture.selection(), fixture.ready(), fixture.planning(),
                PREPARED_AT.plusSeconds(2));

        assertThat(result).isInstanceOf(Authorized.class);
        PreparedSiteExecutionAuthorization authorization =
                ((Authorized) result).authorization();
        assertThat(authorization.executionReadyPlan()).isSameAs(fixture.ready());
        assertThat(authorization.preparedSiteIdentity())
                .isEqualTo(fixture.prepared().preparedSiteIdentity());
        assertThat(authorization.selectionHash()).isEqualTo(fixture.selection().selectionHash());
        assertThat(authorization.coveredCells()).contains(
                fixture.ready().physicalPlan().placements().get(0).anchor());
        assertThat(authorization.evidenceHash()).matches("[0-9a-f]{64}");
        assertThat(PreparedSiteExecutionAuthorization.class.getConstructors()).isEmpty();
    }

    @Test
    void staleOrInventedPlanningSnapshotCannotReachConstruction() {
        Fixture fixture = fixture(false);
        PlanningEvidence wrong = new PlanningEvidence(
                WORLD, DIMENSION, fixture.prepared().preparedSiteIdentity(),
                "f".repeat(64), fixture.ready().physicalPlan().id(),
                fixture.ready().physicalPlan().candidate().snapshotFingerprint(),
                PREPARED_AT.plusSeconds(1), true, "fixture:wrong-clean-snapshot");

        var result = new PreparedSiteExecutionGate().authorize(
                fixture.prepared(), fixture.selection(), fixture.ready(), wrong,
                PREPARED_AT.plusSeconds(2));
        assertThat(result).isInstanceOf(Refused.class);
        assertThat(((Refused) result).failure()).isEqualTo(Failure.PLANNING_SNAPSHOT_MISMATCH);
    }

    @Test
    void planOutsideConfirmedRegionAndExpiredPreparedSiteFailClosed() {
        Fixture narrow = fixture(true);
        var outside = new PreparedSiteExecutionGate().authorize(
                narrow.prepared(), narrow.selection(), narrow.ready(), narrow.planning(),
                PREPARED_AT.plusSeconds(2));
        assertThat(outside).isInstanceOf(Refused.class);
        assertThat(((Refused) outside).failure())
                .isEqualTo(Failure.PLAN_OUTSIDE_AUTHORIZED_REGION);

        Fixture broad = fixture(false);
        var expired = new PreparedSiteExecutionGate().authorize(
                broad.prepared(), broad.selection(), broad.ready(), broad.planning(),
                PREPARED_AT.plusSeconds(601));
        assertThat(expired).isInstanceOf(Refused.class);
        assertThat(((Refused) expired).failure()).isEqualTo(Failure.PREPARED_SITE_EXPIRED);
    }

    private static Fixture fixture(boolean narrow) {
        VerifiedPhysicalPlan physical = DeploymentPreviewServiceTest.physicalPlan();
        ExecutionReadyPlan ready = ((ExecutionReadinessSuccess)
                new dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessVerifier()
                        .verify(physical,
                                new PhysicalizationServiceTest.ReadyFacts(physical).context()))
                .plan();
        BlockPos3i anchorPosition = physical.placements().get(0).anchor();
        Set<BlockPos3i> cells = coveredCells(physical);
        DeploymentBoundingBox bounds = narrow
                ? new DeploymentBoundingBox(anchorPosition, anchorPosition)
                : DeploymentBoundingBox.enclosing(cells);
        SiteSelectionService selections = new SiteSelectionService();
        PlacementAnchor anchor = selections.anchor(
                WORLD, DIMENSION, anchorPosition, PLAYER, PREPARED_AT.minusSeconds(10),
                AnchorSource.PLAYER_LOOK);
        RegionCornerSelection corners = selections.corners(
                WORLD, DIMENSION, bounds.minimum(), WORLD, DIMENSION, bounds.maximum(),
                WORLD, DIMENSION, PLAYER, PREPARED_AT.minusSeconds(9));
        ConfirmedSiteSelection selection = selections.confirm(
                anchor, SiteFacing.NORTH, corners, "site-session:bridge-fixture",
                PREPARED_AT.minusSeconds(8), PREPARED_AT.plusSeconds(900));
        PreparedConstructionSite prepared = new PreparedConstructionSite(
                "prepared-site:bridge-fixture", WORLD, DIMENSION, anchor, SiteFacing.NORTH,
                "b".repeat(64), "c".repeat(64), "terrain-graph:bridge-fixture",
                new SalvageLedger("salvage-ledger:bridge", "salvage-destination:bridge",
                        List.of()),
                List.of(), List.of(), PREPARED_AT, PREPARED_AT.plusSeconds(600));
        PlanningEvidence planning = new PlanningEvidence(
                WORLD, DIMENSION, prepared.preparedSiteIdentity(),
                prepared.cleanSiteSnapshotHash(), physical.id(),
                physical.candidate().snapshotFingerprint(), PREPARED_AT.plusSeconds(1), true,
                "fixture:authoritative-post-clearance-placement-snapshot");
        return new Fixture(selection, prepared, ready, planning);
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
        return cells;
    }

    private record Fixture(
            ConfirmedSiteSelection selection,
            PreparedConstructionSite prepared,
            ExecutionReadyPlan ready,
            PlanningEvidence planning) {}
}
