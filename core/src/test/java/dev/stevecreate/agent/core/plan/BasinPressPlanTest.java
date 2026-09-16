package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class BasinPressPlanTest {
    @Test
    void buildsDistinctBasinTopologySeparateFromBeltPressing() {
        BasinPressPlan plan = BasinPressPlan.blazeCakeBase(new BlockPos3i(10, 64, 20));

        assertThat(plan.placements()).hasSize(22);
        assertThat(plan.placement(BasinPressRole.MECHANICAL_PRESS).position())
                .isEqualTo(new BlockPos3i(11, 69, 18));
        assertThat(plan.placement(BasinPressRole.BASIN).position())
                .isEqualTo(new BlockPos3i(11, 67, 18));
        assertThat(plan.placements())
                .extracting(value -> value.role().name())
                .doesNotContain("BELT_START", "BELT_PRESSING", "BELT_END");
        assertThat(plan.preflightPositions())
                .hasSizeLessThanOrEqualTo(BasinPressPlan.MAX_PREFLIGHT_POSITIONS);
    }

    @Test
    void rotatesAllTypedPlacementsAroundTheAnchor() {
        CompactingProcessSpec spec = spec(BasinHeatMode.NONE);
        BasinPressPlan plan = BasinPressPlan.at(
                new BlockPos3i(5, 70, 5), QuarterTurn.CLOCKWISE_90, spec);

        assertThat(plan.placement(BasinPressRole.WATER_WHEEL).position())
                .isEqualTo(new BlockPos3i(5, 73, 5));
        assertThat(plan.placement(BasinPressRole.HORIZONTAL_SHAFT).position())
                .isEqualTo(new BlockPos3i(6, 75, 6));
        assertThat(plan.placement(BasinPressRole.HORIZONTAL_SHAFT).rotationAxis())
                .isEqualTo(PlanBlockAxis.X);
    }

    @Test
    void acceptsMultiItemButRejectsHeatedUntilSafetyGateExists() {
        assertThat(spec(BasinHeatMode.NONE).totalInputCount()).isEqualTo(3);
        assertThat(spec(BasinHeatMode.NONE).genericSpec()
                .outputVerificationRequirement())
                .isEqualTo(OutputVerificationRequirement.EXACT_DECLARED);
        assertThatThrownBy(() -> spec(BasinHeatMode.HEATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HEATED remains typed unsupported");
    }

    @Test
    void separatesAndDeclaresExactFluidInputs() {
        CompactingProcessSpec spec = new CompactingProcessSpec(
                id("create:compacting/granite_from_flint"),
                List.of(
                        item("minecraft:flint", 2),
                        item("minecraft:red_sand", 1),
                        fluid("minecraft:lava", 100)),
                id("minecraft:granite"), 1,
                BasinHeatMode.NONE, 40, 200);

        assertThat(spec.itemInputs()).containsExactly(
                item("minecraft:flint", 2), item("minecraft:red_sand", 1));
        assertThat(spec.fluidInputs()).containsExactly(fluid("minecraft:lava", 100));
        assertThat(spec.totalInputCount()).isEqualTo(3);
        assertThat(spec.totalFluidInputMillibuckets()).isEqualTo(100);
        assertThat(spec.genericSpec().extensionData().values())
                .contains("minecraft:lava@100mB");
    }

    @Test
    void rejectsFluidOnlyOversizedAndUnsupportedResourceShapes() {
        assertThatThrownBy(() -> new CompactingProcessSpec(
                id("create:compacting/invalid"),
                List.of(fluid("minecraft:lava", 1)),
                id("minecraft:stone"), 1, BasinHeatMode.NONE, 40, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..9 counted item inputs");
        assertThatThrownBy(() -> new CompactingProcessSpec(
                id("create:compacting/invalid"),
                List.of(item("minecraft:stone", 1),
                        fluid("minecraft:lava", 64_001)),
                id("minecraft:stone"), 1, BasinHeatMode.NONE, 40, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded millibucket");
    }

    private static CompactingProcessSpec spec(BasinHeatMode heat) {
        return new CompactingProcessSpec(
                id("create:compacting/blaze_cake"),
                List.of(
                        item("create:cinder_flour", 1),
                        item("minecraft:sugar", 1),
                        item("minecraft:egg", 1)),
                id("create:blaze_cake_base"), 1, heat, 400, 2_000);
    }

    private static ProcessResource item(String value, int count) {
        return new ProcessResource(
                id(value), GenericResourceType.ITEM, count);
    }

    private static ProcessResource fluid(String value, long millibuckets) {
        return new ProcessResource(
                id(value), GenericResourceType.FLUID, millibuckets);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
