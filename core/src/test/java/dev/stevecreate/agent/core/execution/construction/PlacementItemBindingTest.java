package dev.stevecreate.agent.core.execution.construction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlacementItemBindingTest {
    /** The blocks with no item of their own, and what a player hands over instead. */
    @Test
    void namesTheItemThatPlacesEachBlockWithoutAnItemForm() {
        assertThat(PlacementItemBinding.itemFor(id("minecraft:water")))
                .contains(id("minecraft:water_bucket"));
        assertThat(PlacementItemBinding.itemFor(id("minecraft:lava")))
                .contains(id("minecraft:lava_bucket"));
        assertThat(PlacementItemBinding.itemFor(id("minecraft:fire")))
                .contains(id("minecraft:flint_and_steel"));
        assertThat(PlacementItemBinding.itemFor(id("minecraft:soul_fire")))
                .contains(id("minecraft:flint_and_steel"));
        assertThat(PlacementItemBinding.itemFor(id("create:belt")))
                .contains(id("create:belt_connector"));
    }

    /** A saw is a saw. Binding everything would hide the cases that actually differ. */
    @Test
    void leavesAnOrdinaryBlockAlone() {
        assertThat(PlacementItemBinding.itemFor(id("create:mechanical_saw"))).isEmpty();
        assertThat(PlacementItemBinding.itemFor(id("minecraft:chest"))).isEmpty();
    }

    /**
     * The bill a player is quoted names the bucket, not the water.
     *
     * <p>This is the whole point of the binding: the requirement totals are what the
     * reservation holds and what the exact-item check reads, and a reservation cannot
     * hold a water block.</p>
     */
    @Test
    void billsAFanWashingSiteInBucketsRatherThanInWater() {
        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_fan_washing"),
                List.of(
                        component("create:encased_fan", new BlockPos3i(2, 2, 3)),
                        component("minecraft:water", new BlockPos3i(2, 2, 4))),
                List.of(), List.of());

        VerifiedProjectMaterialPlan plan = new VerifiedProjectMaterialPlanFactory().create(
                new VerifiedProjectMaterialPlanFactory.Request(
                        id("player_project:washing"), id("minecraft:clay_ball"), 1,
                        "forge47.4.10|create6.0.6|reload0", snapshot,
                        Map.of(id("create:limestone"), 1L),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        assertThat(plan.installedTotals()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("create:encased_fan"), 1L,
                id("minecraft:water_bucket"), 1L));
        assertThat(plan.installedTotals()).doesNotContainKey(id("minecraft:water"));
    }

    /** C-08's construction water is likewise reserved as the bucket a player can supply. */
    @Test
    void billsTheProductionBasinMixerWaterSourceAsAWaterBucket() {
        BasinMixerPlan mixer = BasinMixerPlan.andesiteAlloy(new BlockPos3i(0, 64, 0));
        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_c08_basin_mixer"),
                mixer.placements().stream()
                        .map(value -> new ResolvedGeometryComponent(
                                id("steve_industrial:c08/role/"
                                        + value.role().name().toLowerCase(java.util.Locale.ROOT)),
                                value.blockId(), value.position(), Map.of()))
                        .toList(),
                List.of(), List.of());

        VerifiedProjectMaterialPlan plan = new VerifiedProjectMaterialPlanFactory().create(
                new VerifiedProjectMaterialPlanFactory.Request(
                        id("player_project:c08"), id("create:andesite_alloy"), 1,
                        "forge47.4.10|create6.0.6|reload0", snapshot,
                        Map.of(id("minecraft:andesite"), 1L,
                                id("minecraft:iron_nugget"), 1L),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        assertThat(plan.installedTotals()).containsEntry(id("minecraft:water_bucket"), 1L);
        assertThat(plan.installedTotals()).doesNotContainKey(id("minecraft:water"));
    }

    /** Two water cells are two buckets, not one — the count follows the placements. */
    @Test
    void countsOneItemPerPlacedCell() {
        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_two_fires"),
                List.of(
                        component("minecraft:fire", new BlockPos3i(0, 2, 0)),
                        component("minecraft:soul_fire", new BlockPos3i(0, 2, 1))),
                List.of(), List.of());

        VerifiedProjectMaterialPlan plan = new VerifiedProjectMaterialPlanFactory().create(
                new VerifiedProjectMaterialPlanFactory.Request(
                        id("player_project:fires"), id("minecraft:cooked_beef"), 1,
                        "forge47.4.10|create6.0.6|reload0", snapshot,
                        Map.of(id("minecraft:beef"), 1L),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        // Both fires are lit with the same tool, so they merge into one line of two.
        assertThat(plan.installedTotals())
                .containsExactlyInAnyOrderEntriesOf(Map.of(id("minecraft:flint_and_steel"), 2L));
    }

    private static ResolvedGeometryComponent component(String blockId, BlockPos3i position) {
        return new ResolvedGeometryComponent(
                id("role:machine"), id(blockId), position, Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
