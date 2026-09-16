package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrushingWheelPlanTest {
    @Test
    void fixesOpposedWheelDrivesRuntimeGapAndRealOutputLogistics() {
        CrushingWheelPlan plan = CrushingWheelPlan.at(new BlockPos3i(10, 20, 30));

        assertThat(plan.placements())
                .extracting(CrushingWheelPlacement::role)
                .containsExactlyInAnyOrder(CrushingWheelRole.values());
        assertThat(plan.placement(CrushingWheelRole.LEFT_WHEEL).position())
                .isEqualTo(new BlockPos3i(10, 23, 30));
        assertThat(plan.controllerPosition()).isEqualTo(new BlockPos3i(11, 23, 30));
        assertThat(plan.placement(CrushingWheelRole.RIGHT_WHEEL).position())
                .isEqualTo(new BlockPos3i(12, 23, 30));
        assertThat(plan.placement(CrushingWheelRole.LEFT_DRIVE).blockId())
                .isEqualTo(id("create:water_wheel"));
        assertThat(plan.placement(CrushingWheelRole.RIGHT_DRIVE).blockId())
                .isEqualTo(id("create:water_wheel"));
        assertThat(plan.placement(CrushingWheelRole.OUTPUT_HOPPER).facing())
                .isEqualTo(PlanBlockFacing.DOWN);
        assertThat(plan.placementTargets()).hasSize(17);
        assertThat(plan.preflightPositions()).hasSize(150);
        assertThat(plan.process().optionalByproducts())
                .extracting(ProcessResource::resourceId)
                .containsExactly(id("minecraft:flint"), id("minecraft:clay_ball"));
    }

    @Test
    void rotatesPositionsAxesAndOpposedDriveFacingsTogether() {
        CrushingWheelPlan plan = CrushingWheelPlan.at(
                new BlockPos3i(0, 0, 0), QuarterTurn.CLOCKWISE_90);

        assertThat(plan.placement(CrushingWheelRole.LEFT_WHEEL).position())
                .isEqualTo(new BlockPos3i(0, 3, 0));
        assertThat(plan.controllerPosition()).isEqualTo(new BlockPos3i(0, 3, 1));
        assertThat(plan.placement(CrushingWheelRole.RIGHT_WHEEL).position())
                .isEqualTo(new BlockPos3i(0, 3, 2));
        assertThat(plan.placement(CrushingWheelRole.LEFT_WHEEL).rotationAxis())
                .isEqualTo(PlanBlockAxis.X);
        assertThat(plan.placement(CrushingWheelRole.LEFT_DRIVE).rotationAxis())
                .isEqualTo(PlanBlockAxis.X);
        assertThat(plan.placement(CrushingWheelRole.RIGHT_DRIVE).rotationAxis())
                .isEqualTo(PlanBlockAxis.X);
    }

    @Test
    void rejectsNonItemOrUnboundedByproductsBeforeExecution() {
        assertThatThrownBy(() -> new CrushingProcessSpec(
                id("test:recipe"),
                id("create:crushing"),
                id("test:input"),
                1,
                id("test:output"),
                1,
                List.of(new ProcessResource(
                        id("test:fluid"), GenericResourceType.FLUID, 1)),
                20,
                100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded ITEM");
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
