package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The branch/merge wrapper validates hopper facing, chest adjacency and salvage
 * boundaries against literal neighbours. A player order derives all of it from one site
 * origin, so these assert the derived geometry satisfies each rule the wrapper checks.
 */
class CompositeBranchMergeLayoutTest {
    private static final CompositePlayerOrderSpecV1 SPEC = CompositePlayerOrderCatalogV1.COMPOSITE_03;
    private static final BlockPos3i ORIGIN = new BlockPos3i(-220, 70, 512);

    @Test
    void putsTheRawChestDirectlyAboveTheSplitHopper() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        CompositeSiteLayout.SplitCells split = layout.split().orElseThrow();
        assertThat(split.rawSource()).isEqualTo(above(split.hopper()));
        assertThat(split.hopper()).isEqualTo(ORIGIN);
    }

    /**
     * The wrapper requires the split hopper to face one branch and merely be adjacent to
     * the other, so the two branch sources must sit on opposite sides of it.
     */
    @Test
    void facesOneBranchAndKeepsTheOtherAdjacent() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        CompositeSiteLayout.SplitCells split = layout.split().orElseThrow();
        CompositeSiteLayout.RouteCells splitRoute = layout.route(split.nodeId());
        assertThat(splitRoute.hopper()).isEqualTo(split.hopper());
        assertThat(splitRoute.pushesInto()).isEqualTo(split.firstBranchSource());
        assertThat(split.firstBranchSource()).isEqualTo(west(split.hopper()));
        assertThat(split.secondBranchSource()).isEqualTo(east(split.hopper()));
        assertThat(adjacent(split.hopper(), split.secondBranchSource())).isTrue();
    }

    /**
     * The wrapper's root split takes one unit for the branch its hopper faces and two
     * for the branch beside it. Ordering the branches by name instead put the two-input
     * branch on the facing side and the order refused before its first tick.
     */
    @Test
    void facesTheBranchThatTakesExactlyOneRawUnit() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        CompositeSiteLayout.SplitCells split = layout.split().orElseThrow();
        assertThat(rawEdgesTo(split.facingBranchNode())).isEqualTo(1);
        assertThat(rawEdgesTo(split.adjacentBranchNode())).isEqualTo(2);
        assertThat(layout.stage(split.facingBranchNode()).source())
                .isEqualTo(split.firstBranchSource());
        assertThat(layout.stage(split.adjacentBranchNode()).source())
                .isEqualTo(split.secondBranchSource());
        // Name order would have chosen the other branch, which is the bug this pins.
        assertThat(split.facingBranchNode().toString())
                .isGreaterThan(split.adjacentBranchNode().toString());
    }

    @Test
    void deliversEachBranchIntoTheChestAboveItsOwnMergeHopper() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        List<CompositeSiteLayout.RouteCells> merges = layout.routes().stream()
                .filter(route -> route.overflow().isPresent()).toList();
        assertThat(merges).hasSize(2);
        for (CompositeSiteLayout.RouteCells route : merges) {
            var edge = SPEC.graph().edges().stream()
                    .filter(value -> value.edgeId().equals(route.routeId())).findFirst().orElseThrow();
            assertThat(layout.stage(edge.producerNodeId()).delivery())
                    .isEqualTo(above(route.hopper()));
            assertThat(route.pushesInto())
                    .isEqualTo(layout.stage(edge.consumerNodeId()).source());
            assertThat(adjacent(route.hopper(), route.overflow().orElseThrow())).isTrue();
            assertThat(route.overflow().orElseThrow()).isNotEqualTo(route.pushesInto());
        }
    }

    /** Both merge hoppers push inward into the one shared merge source. */
    @Test
    void mergesBothBranchesIntoASingleSharedSource() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        assertThat(layout.routes().stream()
                .filter(route -> route.overflow().isPresent())
                .map(CompositeSiteLayout.RouteCells::pushesInto).distinct().toList())
                .hasSize(1);
    }

    @Test
    void installsExactlyTheInfrastructureTheSpecCharges() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        assertThat(layout.matchesDeclaredInfrastructure()).isTrue();
        assertThat(layout.ownedCells()).doesNotHaveDuplicates().hasSize(15);
    }

    @Test
    void keepsEveryOwnedCellAnchorAndWorkerInsideTheAuthorizedRegion() {
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
    void keepsMachineAnchorsAndWorkersOffEveryOwnedCell() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        List<BlockPos3i> owned = layout.ownedCells();
        layout.stages().forEach(stage -> {
            assertThat(owned).doesNotContain(stage.machineAnchor());
            stage.workerStarts().forEach(start -> assertThat(owned).doesNotContain(start));
        });
        assertThat(layout.stages().stream().map(CompositeSiteLayout.StageCells::machineAnchor)
                .toList()).doesNotHaveDuplicates();
        assertThat(layout.stages().stream().flatMap(stage -> stage.workerStarts().stream()).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void routesTheSplitThroughOneHopperRatherThanOnePerEdge() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(SPEC, ORIGIN);

        // Three graph edges leave the split, but they share one physical hopper.
        assertThat(SPEC.graph().outgoing(layout.split().orElseThrow().nodeId())).hasSize(3);
        assertThat(layout.routes()).hasSize(3);
        assertThat(layout.routes().stream().map(CompositeSiteLayout.RouteCells::hopper).toList())
                .doesNotHaveDuplicates();
    }

    private static long rawEdgesTo(dev.stevecreate.agent.core.model.ResourceId branch) {
        return SPEC.graph().outgoing(SPEC.rootSplit().orElseThrow().nodeId()).stream()
                .filter(edge -> edge.consumerNodeId().equals(branch)).count();
    }

    private static boolean adjacent(BlockPos3i left, BlockPos3i right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
                + Math.abs(left.z() - right.z()) == 1;
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

    private static BlockPos3i west(BlockPos3i value) {
        return new BlockPos3i(value.x() - 1, value.y(), value.z());
    }
}
