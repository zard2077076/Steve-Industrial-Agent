package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where withdrawn material goes, and in what slot order.
 *
 * <p>This session's most expensive defect lived here and cost eight dedicated server
 * runs: material was distributed correctly, but the raw chest's slot order sent the
 * wrong item down the hopper. Reasoning about it from the distribution code produced
 * "impossible" twice, because the distribution is not what decides the outcome.</p>
 */
class CompositeMaterialDistributionTest {
    private static final CompositePlayerOrderSpecV1 LINEAR =
            CompositePlayerOrderCatalogV1.COMPOSITE_01;
    private static final CompositePlayerOrderSpecV1 BRANCH_MERGE =
            CompositePlayerOrderCatalogV1.COMPOSITE_03;
    private static final BlockPos3i ORIGIN = new BlockPos3i(8, 64, 8);

    @Test
    void sendsEachStageItsOwnInputsAndInstallationEscrow() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(LINEAR, ORIGIN);
        ResourceId firstStage = LINEAR.stages().get(0).nodeId();
        Map<ResourceId, Map<ResourceId, Long>> installation = Map.of(
                firstStage, Map.of(id("create:mechanical_saw"), 1L));

        Map<BlockPos3i, Map<ResourceId, Long>> demand =
                CompositeMaterialDistribution.demand(LINEAR, layout, installation);

        assertThat(demand.get(layout.stage(firstStage).source()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        id("minecraft:oak_log"), 1L,
                        id("create:mechanical_saw"), 1L));
    }

    /** An input an incoming edge delivers is produced upstream, never bought again. */
    @Test
    void neverChargesForAnInputAnEdgeDelivers() {
        CompositePlayerOrderSpecV1.StageSpec middle = LINEAR.stages().get(1);

        assertThat(CompositeMaterialDistribution.externalInputs(LINEAR, middle)).isEmpty();
        assertThat(middle.processInputs())
                .containsKey(id("minecraft:stripped_oak_log"));
    }

    /**
     * The branch/merge wrapper asserts the raw chest holds exactly the split's units and
     * each branch source exactly its own escrow. That has to fall out of the same rule,
     * not a special case.
     */
    @Test
    void givesBranchSourcesEscrowOnlyAndTheRawChestTheSplitUnits() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(BRANCH_MERGE, ORIGIN);
        ResourceId branch = layout.split().orElseThrow().facingBranchNode();
        Map<ResourceId, Map<ResourceId, Long>> installation = Map.of(
                branch, Map.of(id("create:mechanical_saw"), 1L));

        Map<BlockPos3i, Map<ResourceId, Long>> demand =
                CompositeMaterialDistribution.demand(BRANCH_MERGE, layout, installation);

        assertThat(demand.get(layout.split().orElseThrow().rawSource()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        id("minecraft:stripped_oak_log"), 1L,
                        id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L));
        assertThat(demand.get(layout.stage(branch).source()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(id("create:mechanical_saw"), 1L));
    }

    @Test
    void aLinearRunHasNoRawChest() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(LINEAR, ORIGIN);

        assertThat(CompositeMaterialDistribution.demand(LINEAR, layout, Map.of()))
                .hasSize(LINEAR.stages().size());
    }

    /**
     * The defect that cost eight runs. A vanilla hopper draws by slot, so the facing
     * branch's raw unit has to be first — alphabetical withdrawal order put create:shaft
     * ahead of the log and served the wrong branch.
     */
    @Test
    void drawsTheFacingBranchesUnitFromTheFirstSlot() {
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(BRANCH_MERGE, ORIGIN);
        CompositeSiteLayout.SplitCells split = layout.split().orElseThrow();

        List<CompositeProductionGraph.MaterialEdge> order =
                CompositeMaterialDistribution.splitDrawOrder(BRANCH_MERGE, split);

        assertThat(order).hasSize(3);
        assertThat(order.get(0).consumerNodeId()).isEqualTo(split.facingBranchNode());
        assertThat(order.get(0).resourceId()).isEqualTo(id("minecraft:stripped_oak_log"));
        assertThat(order.get(1).consumerNodeId()).isEqualTo(split.adjacentBranchNode());
        assertThat(order.get(2).consumerNodeId()).isEqualTo(split.adjacentBranchNode());
        // Alphabetical order would have put create:shaft first, which is the bug.
        assertThat(order.get(0).resourceId().toString())
                .isGreaterThan(order.get(1).resourceId().toString());
    }

    @Test
    void everyDestinationIsAChestTheLayoutOwns() {
        for (CompositePlayerOrderSpecV1 spec : List.of(LINEAR, BRANCH_MERGE)) {
            CompositeSiteLayout layout = CompositeSiteLayout.forSpec(spec, ORIGIN);

            assertThat(CompositeMaterialDistribution.demand(spec, layout, Map.of()).keySet())
                    .as("%s", spec.orderType())
                    .isSubsetOf(layout.ownedCells());
        }
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
