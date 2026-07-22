package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class WaterWheelMillstonePlanTest {
    @Test
    void fixesCoordinatesMaterialsOrderAndRecipeFromOneTypedOrigin() {
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(new BlockPos3i(100, 64, -20));

        assertThat(plan.placements()).hasSize(17);
        assertThat(plan.placements()).extracting(ResolvedPlanPlacement::order)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertThat(plan.placements()).extracting(ResolvedPlanPlacement::role)
                .containsExactlyElementsOf(EnumSet.allOf(WaterWheelMillstoneRole.class));
        assertThat(plan.placement(WaterWheelMillstoneRole.WATER_WHEEL).position())
                .isEqualTo(new BlockPos3i(100, 67, -20));
        assertThat(plan.placement(WaterWheelMillstoneRole.GEARBOX).position())
                .isEqualTo(new BlockPos3i(101, 67, -20));
        assertThat(plan.placement(WaterWheelMillstoneRole.VERTICAL_SHAFT).position())
                .isEqualTo(new BlockPos3i(101, 68, -20));
        assertThat(plan.placement(WaterWheelMillstoneRole.MILLSTONE).position())
                .isEqualTo(new BlockPos3i(101, 69, -20));
        assertThat(plan.placement(WaterWheelMillstoneRole.WATER_SOURCE).position())
                .isEqualTo(new BlockPos3i(100, 68, -21));
        assertThat(plan.placements().get(16).role()).isEqualTo(WaterWheelMillstoneRole.WATER_SOURCE);
        assertThat(plan.process().recipeId().toString()).isEqualTo("create:milling/cobblestone");
        assertThat(plan.process().inputItem().toString()).isEqualTo("minecraft:cobblestone");
        assertThat(plan.process().expectedOutputItem().toString()).isEqualTo("minecraft:gravel");
    }

    @Test
    void exposesAnImmutableStrictlyBoundedPreflightVolume() {
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(new BlockPos3i(0, 80, 0));

        assertThat(plan.preflightPositions()).hasSize(175);
        assertThat(plan.preflightPositions().size()).isLessThanOrEqualTo(WaterWheelMillstonePlan.MAX_PREFLIGHT_POSITIONS);
        assertThatThrownBy(() -> plan.placements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.preflightPositions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCoordinateOverflowBeforeAnyExecutorCanSeeThePlan() {
        assertThatThrownBy(() -> WaterWheelMillstonePlan.at(new BlockPos3i(Integer.MAX_VALUE, 0, 0)))
                .isInstanceOf(ArithmeticException.class);
    }
}
