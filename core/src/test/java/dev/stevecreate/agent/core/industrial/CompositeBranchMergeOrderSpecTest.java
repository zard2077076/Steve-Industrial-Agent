package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Composite-03's root split hands out raw material instead of producing any, which is
 * the one place the linear rules do not apply.
 */
class CompositeBranchMergeOrderSpecTest {
    private static final CompositePlayerOrderSpecV1 SPEC = CompositePlayerOrderCatalogV1.COMPOSITE_03;
    private static final ResourceId SPLIT = id("steve_industrial:composite/03_split");
    private static final ResourceId RAW_WOOD = id("steve_industrial:composite/03_raw_wood");
    private static final ResourceId PLANKS = id("steve_industrial:composite/03_planks");

    @Test
    void chargesEverythingTheRootSplitHandsOutBecauseItProducesNone() {
        assertThat(SPEC.externalProcessInputs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("minecraft:stripped_oak_log"), 1L,
                id("create:shaft"), 1L,
                id("minecraft:oak_planks"), 1L));
    }

    @Test
    void countsOnlyTheBranchThatOverproducesAsSalvage() {
        // The wood branch cuts six planks and the merge claims one; the alloy branch
        // makes exactly the one cogwheel the merge takes.
        assertThat(SPEC.intermediateSalvage())
                .containsExactlyInAnyOrderEntriesOf(Map.of(id("minecraft:oak_planks"), 5L));
    }

    @Test
    void chargesTheSplitHopperAndBothMergeRoutes() {
        assertThat(SPEC.infrastructureMaterials()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("minecraft:chest"), 9L,
                id("minecraft:hopper"), 3L,
                id("minecraft:redstone_block"), 3L));
    }

    @Test
    void treatsTheSplitAsCoveringItsNodeWithoutBeingAStage() {
        assertThat(SPEC.rootSplit()).isPresent();
        assertThat(SPEC.rootSplit().orElseThrow().nodeId()).isEqualTo(SPLIT);
        assertThat(SPEC.stages()).extracting(CompositePlayerOrderSpecV1.StageSpec::nodeId)
                .doesNotContain(SPLIT)
                .hasSize(SPEC.graph().nodes().size() - 1);
    }

    @Test
    void keepsTheOrderTargetOnTheMergeSink() {
        assertThat(SPEC.target()).isEqualTo(id("create:large_cogwheel"));
        assertThat(SPEC.graph().shape())
                .isEqualTo(CompositeProductionGraph.Shape.BRANCH_MERGE);
    }

    @Test
    void refusesARootSplitRouteThatClaimsAnOverflowDestination() {
        assertThatThrownBy(() -> withRouteOverflow(RAW_WOOD, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot own an overflow destination");
    }

    /**
     * Every branch/merge route has a salvage boundary whether it fills or not, because
     * the wrapper's merge contract requires one. Dropping it must be refused even on
     * the branch that happens to produce no surplus.
     */
    @Test
    void refusesAMergeRouteWithoutItsSalvageBoundary() {
        assertThatThrownBy(() -> withRouteOverflow(PLANKS, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow declaration does not match");
    }

    @Test
    void refusesASplitThatHandsOutLessThanAnEdgeCarries() {
        assertThatThrownBy(() -> new CompositePlayerOrderSpecV1(
                SPEC.orderType(), SPEC.target(), SPEC.targetQuantity(), SPEC.graph(),
                SPEC.stages(), SPEC.routes(),
                Optional.of(new CompositePlayerOrderSpecV1.RootSplitSpec(SPLIT, Map.of(
                        id("create:shaft"), 1L, id("minecraft:oak_planks"), 1L))),
                SPEC.infrastructureMaterials(), SPEC.capabilities()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not hand out");
    }

    @Test
    void refusesASplitThatIsAlsoDeclaredAProcessingStage() {
        List<CompositePlayerOrderSpecV1.StageSpec> stages =
                new java.util.ArrayList<>(SPEC.stages());
        stages.add(new CompositePlayerOrderSpecV1.StageSpec(
                SPLIT, id("minecraft:stripped_oak_log"), 1,
                Map.of(id("minecraft:oak_log"), 1L), 3));

        assertThatThrownBy(() -> new CompositePlayerOrderSpecV1(
                SPEC.orderType(), SPEC.target(), SPEC.targetQuantity(), SPEC.graph(),
                stages, SPEC.routes(), SPEC.rootSplit(),
                SPEC.infrastructureMaterials(), SPEC.capabilities()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot also be a processing stage");
    }

    @Test
    void bothReviewedGraphsAreOrderable() {
        assertThat(CompositePlayerOrderCatalogV1.entries()).hasSize(2);
        assertThat(CompositePlayerOrderCatalogV1.find(id("steve_industrial:composite/03")))
                .contains(SPEC);
    }

    private static CompositePlayerOrderSpecV1 withRouteOverflow(
            ResourceId edgeId, boolean overflow) {
        List<CompositePlayerOrderSpecV1.RouteSpec> routes = SPEC.routes().stream()
                .map(route -> route.edgeId().equals(edgeId)
                        ? new CompositePlayerOrderSpecV1.RouteSpec(edgeId, overflow) : route)
                .toList();
        return new CompositePlayerOrderSpecV1(
                SPEC.orderType(), SPEC.target(), SPEC.targetQuantity(), SPEC.graph(),
                SPEC.stages(), routes, SPEC.rootSplit(),
                SPEC.infrastructureMaterials(), SPEC.capabilities());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
