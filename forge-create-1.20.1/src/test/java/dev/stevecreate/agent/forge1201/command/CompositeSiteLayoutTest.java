package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeSiteLayoutTest {
    private static final CompositePlayerOrderSpecV1 SPEC = CompositePlayerOrderCatalogV1.COMPOSITE_01;
    private static final BlockPos3i ORIGIN = new BlockPos3i(100, 64, -40);

    @Test
    void placesEveryLockedHopperSoItPullsFromTheProducerAndPushesIntoTheConsumer() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        for (CompositeSiteLayout.RouteCells route : layout.routes()) {
            var edge = SPEC.graph().edges().stream()
                    .filter(value -> value.edgeId().equals(route.routeId())).findFirst().orElseThrow();
            BlockPos3i producerDelivery = layout.stage(edge.producerNodeId()).delivery();
            BlockPos3i consumerSource = layout.stage(edge.consumerNodeId()).source();

            assertThat(producerDelivery).isEqualTo(above(route.hopper()));
            assertThat(consumerSource).isEqualTo(east(route.hopper()));
            assertThat(route.lock()).isEqualTo(south(route.hopper()));
        }
    }

    @Test
    void givesEveryOverflowRouteASalvageChestAndNoOtherRouteOne() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        for (CompositeSiteLayout.RouteCells route : layout.routes()) {
            assertThat(route.overflow().isPresent())
                    .isEqualTo(SPEC.route(route.routeId()).overflowRequired());
            route.overflow().ifPresent(overflow ->
                    assertThat(overflow).isEqualTo(north(route.hopper())));
        }
    }

    @Test
    void ownedCellsAreDistinctSoPlacementAndCleanupCannotDisagree() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        List<BlockPos3i> owned = layout.ownedCells();

        // Seven charged chests, two hoppers and two route locks.
        assertThat(owned).doesNotHaveDuplicates().hasSize(11);
        assertThat(owned).hasSize(SPEC.infrastructureMaterials().values().stream()
                .mapToInt(Long::intValue).sum());
        layout.stages().forEach(stage -> assertThat(owned)
                .contains(stage.source(), stage.delivery()));
        layout.routes().forEach(route -> assertThat(owned).contains(route.hopper(), route.lock()));
    }

    @Test
    void derivedInfrastructureMatchesWhatTheReviewedSpecCharges() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        assertThat(layout.matchesDeclaredInfrastructure()).isTrue();
        assertThat(layout.infrastructureMaterials()).isEqualTo(SPEC.infrastructureMaterials());
    }

    @Test
    void keepsEveryStageMachineAnchorAndWorkerLaneInsideTheAuthorizedRegion() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        BlockPos3i minimum = layout.regionMinimum();
        BlockPos3i maximum = layout.regionMaximum();
        layout.ownedCells().forEach(cell -> assertThat(within(minimum, maximum, cell)).isTrue());
        layout.stages().forEach(stage -> {
            assertThat(within(minimum, maximum, stage.machineAnchor())).isTrue();
            stage.workerStarts().forEach(start ->
                    assertThat(within(minimum, maximum, start)).isTrue());
        });
    }

    @Test
    void spawnsOneBoundedThreeWorkerFleetPerStage() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        assertThat(layout.stages()).allSatisfy(stage ->
                assertThat(stage.workerStarts()).hasSize(3).doesNotHaveDuplicates());
        assertThat(layout.stages().stream().flatMap(stage -> stage.workerStarts().stream()).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void ordersStagesFromTheRootSoTheFirstSourceIsTheSiteOrigin() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        assertThat(layout.stages().get(0).nodeId())
                .isEqualTo(ResourceId.parse("steve_industrial:composite/01_cut_log"));
        assertThat(layout.stages().get(0).source()).isEqualTo(ORIGIN);
        assertThat(layout.stages().get(2).nodeId())
                .isEqualTo(ResourceId.parse("steve_industrial:composite/01_deploy"));
    }

    /**
     * The boundary lane runs east past the machine lane's x offset, so a shared lane
     * once put the last stage's delivery chest exactly on the first stage's anchor and
     * the whole order refused with COMPOSITE_SITE_CELLS_OVERLAP.
     */
    @Test
    void keepsEveryMachineAnchorAndWorkerStartOffTheBoundaryLane() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        List<BlockPos3i> owned = layout.ownedCells();
        layout.stages().forEach(stage -> {
            assertThat(owned).doesNotContain(stage.machineAnchor());
            stage.workerStarts().forEach(start -> assertThat(owned).doesNotContain(start));
        });
        assertThat(layout.stages().stream().map(CompositeSiteLayout.StageCells::machineAnchor)
                .toList()).doesNotHaveDuplicates();
    }

    @Test
    void refusesAGraphShapeThisLayoutCannotPhysicallyBuild() {
        assertThatThrownBy(() -> CompositeSiteLayout.forSpec(null, ORIGIN))
                .isInstanceOf(NullPointerException.class);
    }

    private static boolean within(BlockPos3i minimum, BlockPos3i maximum, BlockPos3i cell) {
        return cell.x() >= minimum.x() && cell.x() <= maximum.x()
                && cell.y() >= minimum.y() && cell.y() <= maximum.y()
                && cell.z() >= minimum.z() && cell.z() <= maximum.z();
    }

    private static BlockPos3i above(BlockPos3i value) {
        return new BlockPos3i(value.x(), value.y() + 1, value.z());
    }

    private static BlockPos3i east(BlockPos3i value) {
        return new BlockPos3i(value.x() + 1, value.y(), value.z());
    }

    private static BlockPos3i north(BlockPos3i value) {
        return new BlockPos3i(value.x(), value.y(), value.z() - 1);
    }

    private static BlockPos3i south(BlockPos3i value) {
        return new BlockPos3i(value.x(), value.y(), value.z() + 1);
    }
}
