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

class BasinMixerPlanTest {
    @Test
    void ownsMixerBasinBurnerAndChestForBothHeatModes() {
        BasinMixerPlan none =
                BasinMixerPlan.andesiteAlloy(new BlockPos3i(10, 64, 20));
        BasinMixerPlan heated =
                BasinMixerPlan.brass(new BlockPos3i(20, 64, 20));

        assertThat(none.placements()).hasSize(24);
        assertThat(none.placement(BasinMixerRole.MECHANICAL_MIXER).position())
                .isEqualTo(new BlockPos3i(13, 70, 22));
        assertThat(none.placement(BasinMixerRole.HEAT_SOURCE).position())
                .isEqualTo(new BlockPos3i(13, 67, 22));
        assertThat(none.process().heatMode()).isEqualTo(BasinHeatMode.NONE);
        assertThat(heated.process().heatMode())
                .isEqualTo(BasinHeatMode.HEATED);
        assertThat(heated.heatingFuel().resourceId())
                .isEqualTo(BasinMixerPlan.HEATING_FUEL);
        assertThatThrownBy(none::heatingFuel)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotatesTheMixerDriveAndKeepsBoundedPreflight() {
        BasinMixerPlan plan = BasinMixerPlan.at(
                new BlockPos3i(5, 70, 5),
                QuarterTurn.CLOCKWISE_90,
                spec(BasinHeatMode.NONE));

        assertThat(plan.placement(BasinMixerRole.WATER_WHEEL).position())
                .isEqualTo(new BlockPos3i(5, 73, 5));
        assertThat(plan.placement(BasinMixerRole.SMALL_COGWHEEL).position())
                .isEqualTo(new BlockPos3i(4, 75, 7));
        assertThat(plan.placement(BasinMixerRole.SMALL_COGWHEEL).rotationAxis())
                .isEqualTo(PlanBlockAxis.Y);
        assertThat(plan.preflightPositions())
                .hasSizeLessThanOrEqualTo(
                        BasinMixerPlan.MAX_PREFLIGHT_POSITIONS);
    }

    @Test
    void preservesCountedTagResolvedInputsAndExactOutput() {
        MixingProcessSpec spec = spec(BasinHeatMode.HEATED);

        assertThat(spec.itemInputs())
                .extracting(value -> value.resourceId().toString())
                .containsExactly("minecraft:copper_ingot", "create:zinc_ingot");
        assertThat(spec.genericSpec().extensionData().values())
                .contains("live_tag_or_any_of");
        assertThat(spec.genericSpec().outputVerificationRequirement())
                .isEqualTo(OutputVerificationRequirement.EXACT_DECLARED);
    }

    @Test
    void separatesAndDeclaresExactFluidInputs() {
        MixingProcessSpec spec = new MixingProcessSpec(
                id("create:mixing/cardboard_pulp"),
                List.of(
                        item("minecraft:paper", 4),
                        fluid("minecraft:water", 250)),
                id("create:pulp"), 1,
                BasinHeatMode.NONE, 40, 200);

        assertThat(spec.itemInputs()).containsExactly(item("minecraft:paper", 4));
        assertThat(spec.fluidInputs()).containsExactly(fluid("minecraft:water", 250));
        assertThat(spec.totalInputCount()).isEqualTo(4);
        assertThat(spec.totalFluidInputMillibuckets()).isEqualTo(250);
        assertThat(spec.genericSpec().extensionData().values())
                .contains("minecraft:water@250mB");
    }

    @Test
    void rejectsFluidOnlyOversizedAndUnsupportedResourceShapes() {
        assertThatThrownBy(() -> new MixingProcessSpec(
                id("create:mixing/invalid"),
                List.of(fluid("minecraft:water", 1)),
                id("minecraft:stone"), 1,
                BasinHeatMode.NONE, 40, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..9 counted item inputs");
        assertThatThrownBy(() -> new MixingProcessSpec(
                id("create:mixing/invalid"),
                List.of(item("minecraft:stone", 1),
                        fluid("minecraft:water", 64_001)),
                id("minecraft:stone"), 1,
                BasinHeatMode.NONE, 40, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded millibucket");
    }

    private static MixingProcessSpec spec(BasinHeatMode heat) {
        return new MixingProcessSpec(
                id(heat == BasinHeatMode.NONE
                        ? "create:mixing/andesite_alloy"
                        : "create:mixing/brass_ingot"),
                heat == BasinHeatMode.NONE
                        ? List.of(
                                item("minecraft:andesite", 1),
                                item("minecraft:iron_nugget", 1))
                        : List.of(
                                item("minecraft:copper_ingot", 1),
                                item("create:zinc_ingot", 1)),
                id(heat == BasinHeatMode.NONE
                        ? "create:andesite_alloy"
                        : "create:brass_ingot"),
                heat == BasinHeatMode.NONE ? 1 : 2,
                heat, 400, 2_000);
    }

    private static ProcessResource item(String value, int count) {
        return new ProcessResource(
                id(value), GenericResourceType.ITEM, count);
    }

    private static ProcessResource fluid(String value, long millibuckets) {
        return new ProcessResource(
                id(value), GenericResourceType.FLUID, millibuckets);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
