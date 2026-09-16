package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The contract only earns its keep if each rule catches a defect that really happened.
 *
 * <p>Every negative case below reproduces a failure that previously cost a dedicated
 * server run to discover — between thirty seconds and ten minutes each, and one of them
 * took eight runs to isolate. If a rule here can be deleted without a test going red,
 * that rule is decoration.</p>
 */
class CompositeLayoutContractTest {
    private static final BlockPos3i ORIGIN = new BlockPos3i(40, 64, -120);

    @Test
    void bothReviewedGraphsSatisfyTheContractAtAnyOrigin() {
        for (CompositePlayerOrderSpecV1 spec : CompositePlayerOrderCatalogV1.entries()) {
            for (BlockPos3i origin : List.of(ORIGIN, new BlockPos3i(-9000, -50, 9000),
                    new BlockPos3i(0, 320, 0))) {
                assertThat(CompositeLayoutContract.violations(
                        spec, CompositeSiteLayout.forSpec(spec, origin)))
                        .as("%s at %s", spec.orderType(), origin)
                        .isEmpty();
            }
        }
    }

    /**
     * Run 2 of the Composite-03 gate. Branches ordered by name put the two-input branch
     * on the side the hopper faces, and the wrapper refused before its first tick.
     */
    @Test
    void refusesASplitThatFacesTheTwoInputBranch() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_03;
        CompositeSiteLayout good = CompositeSiteLayout.forSpec(spec, ORIGIN);
        CompositeSiteLayout.SplitCells split = good.split().orElseThrow();

        List<String> violations = CompositeLayoutContract.violations(spec,
                withSplit(good, new CompositeSiteLayout.SplitCells(
                        split.nodeId(), split.rawSource(), split.hopper(), split.lock(),
                        split.adjacentBranchNode(), split.firstBranchSource(),
                        split.facingBranchNode(), split.secondBranchSource())));

        assertThat(violations).anyMatch(value -> value.contains("single-input branch"));
    }

    /**
     * Run 5 of the Composite-01 gate. The boundary lane runs east past the machine
     * lane's x offset, so a shared lane put the last delivery chest exactly on the first
     * machine anchor and the order refused with COMPOSITE_SITE_CELLS_OVERLAP.
     */
    @Test
    void refusesAMachineAnchorSittingOnABoundaryChest() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;
        CompositeSiteLayout good = CompositeSiteLayout.forSpec(spec, ORIGIN);
        BlockPos3i occupied = good.stages().get(0).source();

        List<String> violations = CompositeLayoutContract.violations(spec,
                withStageAnchor(good, good.stages().get(2).nodeId(), occupied));

        assertThat(violations).anyMatch(value -> value.contains("machine anchor sits on an owned cell"));
    }

    /** A hopper that does not sit under its producer's delivery chest moves nothing. */
    @Test
    void refusesARouteDetachedFromItsProducersDelivery() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;
        CompositeSiteLayout good = CompositeSiteLayout.forSpec(spec, ORIGIN);
        CompositeSiteLayout.RouteCells route = good.routes().get(0);

        List<String> violations = CompositeLayoutContract.violations(spec,
                withRoute(good, new CompositeSiteLayout.RouteCells(
                        route.routeId(), shift(route.hopper(), 0, 0, 3), route.lock(),
                        route.pushesInto(), route.overflow())));

        assertThat(violations).anyMatch(value -> value.contains("does not pull from its producer"));
    }

    /** The salvage chest may not be the cell the hopper faces, or it eats the route. */
    @Test
    void refusesASalvageChestOnTheCellTheHopperFaces() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;
        CompositeSiteLayout good = CompositeSiteLayout.forSpec(spec, ORIGIN);
        CompositeSiteLayout.RouteCells overflowRoute = good.routes().stream()
                .filter(route -> route.overflow().isPresent()).findFirst().orElseThrow();

        List<String> violations = CompositeLayoutContract.violations(spec,
                withRoute(good, new CompositeSiteLayout.RouteCells(
                        overflowRoute.routeId(), overflowRoute.hopper(), overflowRoute.lock(),
                        overflowRoute.pushesInto(), Optional.of(overflowRoute.pushesInto()))));

        assertThat(violations).anyMatch(value -> value.contains("the cell the hopper faces"));
    }

    /**
     * Run 6 of the Composite-03 gate. Both wrappers assert an exact peak worker count
     * and concurrent branches add up, so three per stage overshot the required five.
     */
    @Test
    void refusesABranchMergeFleetThatDoesNotPeakAtFive() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_03;
        CompositePlayerOrderSpecV1 overSized = withEveryStageFleet(spec, 3);

        assertThat(CompositeLayoutContract.violations(
                overSized, CompositeSiteLayout.forSpec(spec, ORIGIN)))
                .anyMatch(value -> value.contains("five workers at once"));
    }

    @Test
    void refusesALinearFleetThatIsNotThreePerStage() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;
        CompositePlayerOrderSpecV1 undersized = withEveryStageFleet(spec, 2);

        assertThat(CompositeLayoutContract.violations(
                undersized, CompositeSiteLayout.forSpec(spec, ORIGIN)))
                .anyMatch(value -> value.contains("three workers per stage"));
    }

    /** The contract runs at construction, so a bad layout cannot reach a server at all. */
    @Test
    void forSpecRefusesToBuildALayoutThatViolatesTheContract() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_03;

        assertThatThrownBy(() -> CompositeSiteLayout.forSpec(withEveryStageFleet(spec, 3), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("violates its physical contract");
    }

    private static CompositePlayerOrderSpecV1 withEveryStageFleet(
            CompositePlayerOrderSpecV1 spec, int workers) {
        List<CompositePlayerOrderSpecV1.StageSpec> stages = spec.stages().stream()
                .map(stage -> new CompositePlayerOrderSpecV1.StageSpec(stage.nodeId(),
                        stage.target(), stage.targetQuantity(), stage.processInputs(), workers))
                .toList();
        return new CompositePlayerOrderSpecV1(spec.orderType(), spec.target(),
                spec.targetQuantity(), spec.graph(), stages, spec.routes(), spec.rootSplit(),
                spec.infrastructureMaterials(), spec.capabilities());
    }

    private static CompositeSiteLayout withSplit(
            CompositeSiteLayout layout, CompositeSiteLayout.SplitCells split) {
        return CompositeSiteLayout.forTesting(layout, layout.stages(), layout.routes(),
                Optional.of(split));
    }

    private static CompositeSiteLayout withRoute(
            CompositeSiteLayout layout, CompositeSiteLayout.RouteCells replacement) {
        List<CompositeSiteLayout.RouteCells> routes = layout.routes().stream()
                .map(route -> route.routeId().equals(replacement.routeId()) ? replacement : route)
                .toList();
        return CompositeSiteLayout.forTesting(layout, layout.stages(), routes, layout.split());
    }

    private static CompositeSiteLayout withStageAnchor(
            CompositeSiteLayout layout, ResourceId nodeId, BlockPos3i anchor) {
        List<CompositeSiteLayout.StageCells> stages = layout.stages().stream()
                .map(stage -> stage.nodeId().equals(nodeId)
                        ? new CompositeSiteLayout.StageCells(stage.nodeId(), stage.source(),
                                stage.delivery(), anchor, stage.workerStarts())
                        : stage)
                .toList();
        return CompositeSiteLayout.forTesting(layout, stages, layout.routes(), layout.split());
    }

    private static BlockPos3i shift(BlockPos3i value, int x, int y, int z) {
        return new BlockPos3i(value.x() + x, value.y() + y, value.z() + z);
    }
}
