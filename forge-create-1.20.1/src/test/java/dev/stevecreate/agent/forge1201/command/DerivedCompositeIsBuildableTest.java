package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.industrial.CompositeGraphExpander;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The point of everything built before this: a graph nobody wrote by hand can be judged
 * physically buildable in a unit test.
 *
 * <p>Adding the two hand-written graphs cost roughly eight dedicated server runs each,
 * because the wrappers' physical preconditions were only discoverable by watching a
 * server refuse. If automatic expansion still needed that, expanding to arbitrary
 * products would be unaffordable — a hundred candidate graphs would mean hundreds of
 * server boots. These tests are the claim that it does not.</p>
 */
class DerivedCompositeIsBuildableTest {
    private static final ResourceId OAK_LOG = id("minecraft:oak_log");
    private static final ResourceId STRIPPED = id("minecraft:stripped_oak_log");
    private static final ResourceId PLANKS = id("minecraft:oak_planks");
    private static final ResourceId COGWHEEL = id("create:cogwheel");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId CUTTING = id("create:cutting");
    private static final ResourceId DEPLOYING = id("create:deploying");

    private static final List<GoalCatalogEntry> WOOD_CHAIN = List.of(
            recipe(STRIPPED, "create:cutting/oak_log", CUTTING, Map.of(OAK_LOG, 1L), 1),
            recipe(PLANKS, "create:cutting/stripped_oak_log", CUTTING, Map.of(STRIPPED, 1L), 6),
            recipe(COGWHEEL, "create:deploying/cogwheel", DEPLOYING,
                    Map.of(PLANKS, 1L, SHAFT, 1L), 1));

    @Test
    void aDerivedGraphSatisfiesTheSamePhysicalContractAsAReviewedOne() {
        CompositePlayerOrderSpecV1 derived = derive();

        for (BlockPos3i origin : List.of(new BlockPos3i(0, 64, 0),
                new BlockPos3i(-4100, -50, 2700), new BlockPos3i(9000, 300, -9000))) {
            CompositeSiteLayout layout = CompositeSiteLayout.forSpec(derived, origin);

            assertThat(CompositeLayoutContract.violations(derived, layout))
                    .as("derived graph at %s", origin)
                    .isEmpty();
        }
    }

    /** Same site geometry as the graph a human wrote, since the shape is the same. */
    @Test
    void laysOutIdenticallyToTheHandWrittenEquivalent() {
        BlockPos3i origin = new BlockPos3i(12, 70, -30);
        CompositeSiteLayout derived = CompositeSiteLayout.forSpec(derive(), origin);
        CompositeSiteLayout handWritten = CompositeSiteLayout.forSpec(
                CompositePlayerOrderCatalogV1.COMPOSITE_01, origin);

        assertThat(derived.ownedCells()).isEqualTo(handWritten.ownedCells());
        assertThat(derived.regionMinimum()).isEqualTo(handWritten.regionMinimum());
        assertThat(derived.regionMaximum()).isEqualTo(handWritten.regionMaximum());
    }

    /** Material lands in the same chests, so the derived order charges what it builds. */
    @Test
    void distributesMaterialTheSameWayTheReviewedOrderDoes() {
        BlockPos3i origin = new BlockPos3i(5, 64, 5);
        CompositePlayerOrderSpecV1 derived = derive();
        CompositeSiteLayout layout = CompositeSiteLayout.forSpec(derived, origin);

        Map<BlockPos3i, Map<ResourceId, Long>> demand =
                CompositeMaterialDistribution.demand(derived, layout, Map.of());

        assertThat(demand.keySet()).isSubsetOf(layout.ownedCells());
        assertThat(demand.values().stream()
                .flatMap(wanted -> wanted.keySet().stream()).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(derived.externalProcessInputs().keySet());
    }

    private static CompositePlayerOrderSpecV1 derive() {
        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                COGWHEEL, 1, WOOD_CHAIN, Set.of(OAK_LOG, SHAFT));
        assertThat(result.success()).as(result.code()).isTrue();
        return result.spec().orElseThrow();
    }

    private static GoalCatalogEntry recipe(
            ResourceId target, String recipeId, ResourceId capability,
            Map<ResourceId, Long> inputs, long outputPerBatch) {
        return new GoalCatalogEntry(target, id(recipeId), capability, inputs,
                outputPerBatch, 1, true, true);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
