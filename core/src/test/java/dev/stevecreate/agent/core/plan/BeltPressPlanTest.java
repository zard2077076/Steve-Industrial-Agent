package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class BeltPressPlanTest {
    @Test
    void fixesBuildSequenceFinalLayoutMaterialsAndRecipeFromOneTypedOrigin() {
        BeltPressPlan plan = BeltPressPlan.at(new BlockPos3i(100, 64, -20));

        assertThat(plan.buildSteps()).hasSize(8);
        assertThat(plan.buildSteps()).extracting(BeltPressBuildStep::order)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(plan.buildSteps()).extracting(BeltPressBuildStep::role)
                .containsExactlyElementsOf(EnumSet.allOf(BeltPressBuildRole.class));
        assertThat(plan.beltConnection().materialId().toString()).isEqualTo("create:belt_connector");
        assertThat(plan.beltConnection().expectedSegments()).isEqualTo(3);

        assertThat(plan.finalPlacements()).hasSize(8);
        assertThat(plan.finalPlacements()).extracting(BeltPressPlacement::role)
                .containsExactlyElementsOf(EnumSet.allOf(BeltPressRole.class));
        assertThat(plan.placement(BeltPressRole.BELT_START).position())
                .isEqualTo(new BlockPos3i(100, 65, -20));
        assertThat(plan.placement(BeltPressRole.BELT_PRESSING).position())
                .isEqualTo(new BlockPos3i(101, 65, -20));
        assertThat(plan.placement(BeltPressRole.MECHANICAL_PRESS).position())
                .isEqualTo(new BlockPos3i(101, 67, -20));
        assertThat(plan.placement(BeltPressRole.OUTPUT_CHEST).position())
                .isEqualTo(new BlockPos3i(103, 64, -20));
        assertThat(plan.placement(BeltPressRole.OUTPUT_FUNNEL).position())
                .isEqualTo(new BlockPos3i(103, 65, -20));
        assertThat(plan.placement(BeltPressRole.OUTPUT_FUNNEL).facing())
                .isEqualTo(PlanBlockFacing.UP);
        assertThat(plan.finalPlacements().stream().filter(placement -> placement.role().isBelt()))
                .allMatch(placement -> placement.blockId().toString().equals("create:belt"))
                .allMatch(placement -> placement.rotationAxis() == PlanBlockAxis.Z)
                .allMatch(placement -> placement.facing() == PlanBlockFacing.EAST);

        assertThat(plan.process().recipeId().toString()).isEqualTo("create:pressing/iron_ingot");
        assertThat(plan.process().recipeType().toString()).isEqualTo("create:pressing");
        assertThat(plan.process().inputItem().toString()).isEqualTo("minecraft:iron_ingot");
        assertThat(plan.process().expectedOutputItem().toString()).isEqualTo("create:iron_sheet");
    }

    @Test
    void exposesImmutableStrictlyBoundedConstructionAndPreflightData() {
        BeltPressPlan plan = BeltPressPlan.at(new BlockPos3i(0, 80, 0));

        assertThat(plan.preflightPositions()).hasSize(150);
        assertThat(plan.preflightPositions().size()).isLessThanOrEqualTo(BeltPressPlan.MAX_PREFLIGHT_POSITIONS);
        assertThatThrownBy(() -> plan.buildSteps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.finalPlacements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.preflightPositions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCoordinateOverflowBeforeAnyExecutorCanAccessAWorld() {
        assertThatThrownBy(() -> BeltPressPlan.at(new BlockPos3i(Integer.MAX_VALUE, 0, 0)))
                .isInstanceOf(ArithmeticException.class);
    }
}
