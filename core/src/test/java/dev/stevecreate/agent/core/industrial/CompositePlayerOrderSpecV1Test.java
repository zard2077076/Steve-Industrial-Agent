package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.MaterialEdge;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Node;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Shape;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompositePlayerOrderSpecV1Test {
    private static final ResourceId CUT_LOG = id("steve_industrial:composite/01_cut_log");
    private static final ResourceId CUT_PLANKS = id("steve_industrial:composite/01_cut_planks");
    private static final ResourceId DEPLOY = id("steve_industrial:composite/01_deploy");
    private static final ResourceId STRIPPED_ROUTE = id("steve_industrial:composite/01_stripped_route");
    private static final ResourceId PLANK_ROUTE = id("steve_industrial:composite/01_plank_route");

    @Test
    void reservesOnlyInputsNoUpstreamEdgeDelivers() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;

        assertThat(spec.externalProcessInputs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("minecraft:oak_log"), 1L,
                id("create:shaft"), 1L));
    }

    @Test
    void reportsUnclaimedStageOutputAsSalvageInsteadOfLoss() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;

        assertThat(spec.intermediateSalvage())
                .containsExactlyInAnyOrderEntriesOf(Map.of(id("minecraft:oak_planks"), 5L));
    }

    @Test
    void chargesEveryBoundaryChestHopperAndRouteLock() {
        CompositePlayerOrderSpecV1 spec = CompositePlayerOrderCatalogV1.COMPOSITE_01;

        assertThat(spec.infrastructureMaterials()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("minecraft:chest"), 7L,
                id("minecraft:hopper"), 2L,
                id("minecraft:redstone_block"), 2L));
    }

    @Test
    void refusesAnEdgeThatDeliversAnInputItsConsumerNeverUses() {
        assertThatThrownBy(() -> spec(6, Map.of(id("create:shaft"), 1L), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delivers an input its consumer never uses");
    }

    @Test
    void refusesSurplusWithoutAnOverflowDestination() {
        assertThatThrownBy(() -> spec(6, Map.of(
                id("minecraft:oak_planks"), 1L, id("create:shaft"), 1L), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow declaration does not match");
    }

    @Test
    void refusesAnOverflowDestinationWhenNothingIsLeftOver() {
        assertThatThrownBy(() -> spec(1, Map.of(
                id("minecraft:oak_planks"), 1L, id("create:shaft"), 1L), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow declaration does not match");
    }

    @Test
    void refusesAStageSetThatDoesNotCoverEveryAdmittedNode() {
        assertThatThrownBy(() -> new CompositePlayerOrderSpecV1(
                id("steve_industrial:composite/01"), id("create:cogwheel"), 1, graph(),
                List.of(stage(CUT_LOG, id("minecraft:stripped_oak_log"), 1,
                        Map.of(id("minecraft:oak_log"), 1L))),
                List.of(new CompositePlayerOrderSpecV1.RouteSpec(STRIPPED_ROUTE, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(PLANK_ROUTE, true)),
                Optional.empty(), Map.of(id("minecraft:chest"), 7L),
                List.of(IndustrialCapability.ITEM_PROCESSING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cover exactly every admitted node once");
    }

    @Test
    void refusesAnOrderTargetThatIsNotTheSinkStageOutput() {
        assertThatThrownBy(() -> new CompositePlayerOrderSpecV1(
                id("steve_industrial:composite/01"), id("minecraft:oak_planks"), 1, graph(),
                List.of(
                        stage(CUT_LOG, id("minecraft:stripped_oak_log"), 1,
                                Map.of(id("minecraft:oak_log"), 1L)),
                        stage(CUT_PLANKS, id("minecraft:oak_planks"), 6,
                                Map.of(id("minecraft:stripped_oak_log"), 1L)),
                        stage(DEPLOY, id("create:cogwheel"), 1, Map.of(
                                id("minecraft:oak_planks"), 1L, id("create:shaft"), 1L))),
                List.of(new CompositePlayerOrderSpecV1.RouteSpec(STRIPPED_ROUTE, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(PLANK_ROUTE, true)),
                Optional.empty(), Map.of(id("minecraft:chest"), 7L),
                List.of(IndustrialCapability.ITEM_PROCESSING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admitted sink stage output");
    }

    @Test
    void catalogEntryIsAddressableByItsOrderType() {
        assertThat(CompositePlayerOrderCatalogV1.find(id("steve_industrial:composite/01")))
                .contains(CompositePlayerOrderCatalogV1.COMPOSITE_01);
        assertThat(CompositePlayerOrderCatalogV1.find(id("steve_industrial:composite/99"))).isEmpty();
    }

    private static CompositePlayerOrderSpecV1 spec(
            long plankQuantity, Map<ResourceId, Long> deployInputs, boolean plankOverflow) {
        return new CompositePlayerOrderSpecV1(
                id("steve_industrial:composite/01"), id("create:cogwheel"), 1, graph(),
                List.of(
                        stage(CUT_LOG, id("minecraft:stripped_oak_log"), 1,
                                Map.of(id("minecraft:oak_log"), 1L)),
                        stage(CUT_PLANKS, id("minecraft:oak_planks"), plankQuantity,
                                Map.of(id("minecraft:stripped_oak_log"), 1L)),
                        stage(DEPLOY, id("create:cogwheel"), 1, deployInputs)),
                List.of(new CompositePlayerOrderSpecV1.RouteSpec(STRIPPED_ROUTE, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(PLANK_ROUTE, plankOverflow)),
                Optional.empty(), Map.of(id("minecraft:chest"), 7L),
                List.of(IndustrialCapability.ITEM_PROCESSING));
    }

    private static CompositePlayerOrderSpecV1.StageSpec stage(
            ResourceId nodeId, ResourceId target, long quantity, Map<ResourceId, Long> inputs) {
        return new CompositePlayerOrderSpecV1.StageSpec(nodeId, target, quantity, inputs, 3);
    }

    private static CompositeProductionGraph graph() {
        ResourceId line = id("steve_industrial:composite/01_line");
        return new CompositeProductionGraph(
                id("steve_industrial:composite/01"), Shape.LINEAR_CHAIN,
                List.of(
                        new Node(CUT_LOG, id("create:cutting"), line, Set.of()),
                        new Node(CUT_PLANKS, id("create:cutting"), line, Set.of()),
                        new Node(DEPLOY, id("create:deploying"), line, Set.of())),
                List.of(
                        new MaterialEdge(STRIPPED_ROUTE, CUT_LOG, CUT_PLANKS,
                                id("minecraft:stripped_oak_log"), 1, 1),
                        new MaterialEdge(PLANK_ROUTE, CUT_PLANKS, DEPLOY,
                                id("minecraft:oak_planks"), 1, 1)));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
