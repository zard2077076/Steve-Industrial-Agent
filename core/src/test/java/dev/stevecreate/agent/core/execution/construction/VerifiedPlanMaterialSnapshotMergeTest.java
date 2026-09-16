package dev.stevecreate.agent.core.execution.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifiedPlanMaterialSnapshotMergeTest {
    @Test
    void mergesEveryStageComponentAndRouteCellIntoOneReservationAuthority() {
        VerifiedPlanMaterialSnapshot merged = VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_01"),
                List.of(stageOne(), stageTwo()),
                List.of(new BlockPos3i(7, 2, 3), new BlockPos3i(11, 2, 3)));

        assertThat(merged.physicalPlanId()).isEqualTo(id("layout:verified_composite_01"));
        assertThat(merged.components()).hasSize(3);
        assertThat(merged.itemRoutePositions())
                .contains(new BlockPos3i(7, 2, 3), new BlockPos3i(11, 2, 3));
    }

    @Test
    void chargesEveryStageAndRouteMaterialOnceThroughTheVerifiedFactory() {
        VerifiedPlanMaterialSnapshot merged = VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_01"),
                List.of(stageOne(), stageTwo()),
                List.of(new BlockPos3i(7, 2, 3), new BlockPos3i(11, 2, 3)));

        VerifiedProjectMaterialPlan plan = new VerifiedProjectMaterialPlanFactory().create(
                new VerifiedProjectMaterialPlanFactory.Request(
                        id("player_project:composite"), id("create:cogwheel"), 1,
                        "forge47.4.10|create6.0.6|reload0", merged,
                        Map.of(id("minecraft:oak_log"), 1L, id("create:shaft"), 1L),
                        Map.of(id("minecraft:hopper"), 2L, id("minecraft:redstone_block"), 2L),
                        Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        assertThat(plan.legacyRequirementTotals()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("create:mechanical_saw"), 2L,
                id("create:deployer"), 1L,
                id("minecraft:hopper"), 2L,
                id("minecraft:redstone_block"), 2L,
                id("minecraft:oak_log"), 1L,
                id("create:shaft"), 1L));
        assertThat(plan.consumedTotals()).containsOnlyKeys(
                id("minecraft:oak_log"), id("create:shaft"));
    }

    @Test
    void refusesTwoStagesClaimingTheSameVerifiedCell() {
        assertThatThrownBy(() -> VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_01"), List.of(stageOne(), stageOne()), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claim the same verified cell");
    }

    @Test
    void refusesARouteCellThatOverlapsAVerifiedComponent() {
        assertThatThrownBy(() -> VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_01"), List.of(stageOne(), stageTwo()),
                List.of(new BlockPos3i(2, 2, 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlaps a verified component cell");
    }

    /**
     * A single stage merges, and it must: that is what a one-machine composite is.
     *
     * <p>The result is the stage's own components under the composite plan's identity,
     * which is the half of the merge that does not need a second stage. Refusing it kept
     * every derived single-machine product out of unattended production.</p>
     */
    @Test
    void mergesAOneStageCompositeUnderTheCompositePlanIdentity() {
        VerifiedPlanMaterialSnapshot merged = VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_one"), List.of(stageOne()), List.of());

        assertThat(merged.physicalPlanId()).isEqualTo(id("layout:verified_composite_one"));
        assertThat(merged.components()).hasSize(2);
        assertThat(merged.itemRoutePositions()).isEmpty();
    }

    /** Still nothing to merge is still a refusal. */
    @Test
    void refusesACompositeWithNoStages() {
        assertThatThrownBy(() -> VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_01"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a stage");
    }

    /** A one-stage merge does not stop checking routes against components. */
    @Test
    void stillRefusesARouteOverlappingTheOnlyStage() {
        assertThatThrownBy(() -> VerifiedPlanMaterialSnapshot.merge(
                id("layout:verified_composite_one"), List.of(stageOne()),
                List.of(new BlockPos3i(2, 2, 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlaps a verified component cell");
    }

    private static VerifiedPlanMaterialSnapshot stageOne() {
        return VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_cut_log"),
                List.of(
                        component("create:mechanical_saw", new BlockPos3i(2, 2, 3)),
                        component("create:mechanical_saw", new BlockPos3i(3, 2, 3))),
                List.of(), List.of());
    }

    private static VerifiedPlanMaterialSnapshot stageTwo() {
        return VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_deploy"),
                List.of(component("create:deployer", new BlockPos3i(12, 2, 3))),
                List.of(), List.of());
    }

    private static ResolvedGeometryComponent component(String blockId, BlockPos3i position) {
        return new ResolvedGeometryComponent(
                id("role:machine"), id(blockId), position, Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
