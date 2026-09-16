package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FanProcessingPlanTest {
    @Test
    void mapsEveryMediumAndNeverMarksTheFanOrBotStandAsDangerous() {
        for (FanProcessingMode mode : FanProcessingMode.values()) {
            FanProcessingPlan plan = FanProcessingPlan.forProcess(
                    PlanAnchor.at(new BlockPos3i(10, 70, 20)),
                    spec(mode));
            var medium = plan.placement(FanProcessingRole.PROCESSING_MEDIUM);
            assertThat(medium.blockId()).isEqualTo(mode.mediumBlock());
            assertThat(medium.dangerousToBots()).isEqualTo(mode.dangerousToBots());
            assertThat(plan.botForbiddenPositions()).isEqualTo(
                    mode.dangerousToBots()
                            ? Set.of(
                                    medium.position(),
                                    plan.placement(FanProcessingRole.MEDIUM_STOP).position(),
                                    plan.inputEntityPosition())
                            : Set.of());
            assertThat(plan.placements()).allMatch(value ->
                    value.role() == FanProcessingRole.PROCESSING_MEDIUM
                            || !value.dangerousToBots());
            assertThat(plan.placement(FanProcessingRole.WATER_SOURCE).order())
                    .isEqualTo(plan.placements().size());
            assertThat(plan.placements()).extracting(FanProcessingPlacement::blockId)
                    .doesNotContain(ResourceId.parse("create:creative_motor"));
        }
    }

    @Test
    void rotationPreservesAirflowAlignmentAndHazardIdentity() {
        FanProcessingPlan plan = FanProcessingPlan.at(
                new BlockPos3i(0, 64, 0),
                QuarterTurn.CLOCKWISE_90,
                spec(FanProcessingMode.BLASTING));
        assertThat(plan.placement(FanProcessingRole.ENCASED_FAN).facing())
                .isEqualTo(PlanBlockFacing.EAST);
        assertThat(plan.placement(FanProcessingRole.ENCASED_FAN).rotationAxis())
                .isEqualTo(PlanBlockAxis.X);
        assertThat(plan.airflowFacing()).isEqualTo(PlanBlockFacing.EAST);
        assertThat(plan.botForbiddenPositions()).containsExactlyInAnyOrder(
                plan.placement(FanProcessingRole.PROCESSING_MEDIUM).position(),
                plan.placement(FanProcessingRole.MEDIUM_STOP).position(),
                plan.inputEntityPosition());
    }

    private static FanProcessingSpec spec(FanProcessingMode mode) {
        return new FanProcessingSpec(
                ResourceId.parse("test:" + mode.name().toLowerCase()),
                mode.recipeType(),
                ResourceId.parse("minecraft:cobblestone"),
                1,
                ResourceId.parse("minecraft:stone"),
                1,
                100,
                200);
    }
}
