package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecipeHeatRequirementTest {
    @Test
    void explicitNoneCarriesEvidenceWithoutFuelOrContinuousHeat() {
        RecipeHeatRequirement value = RecipeHeatRequirement.none(
                id("create:mixing/andesite_alloy"), id("create:mixing"));

        assertThat(value.heatTier()).isEqualTo(RecipeHeatTier.NONE);
        assertThat(value.continuouslyRequired()).isFalse();
        assertThat(value.fuelPerExecution()).isEmpty();
        assertThat(value.preStartVerificationIds()).isNotEmpty();
        assertThat(value.runtimeEvidenceIds()).isNotEmpty();
        assertThat(value.failureDiagnosticIds()).isNotEmpty();
    }

    @Test
    void heatedRequirementRetainsExactItemFuelAndSortedEvidence() {
        RecipeHeatRequirement value = heated(item("minecraft:coal", 1));

        assertThat(value.heatTier()).isEqualTo(RecipeHeatTier.HEATED);
        assertThat(value.continuouslyRequired()).isTrue();
        assertThat(value.fuelPerExecution()).contains(item("minecraft:coal", 1));
        assertThat(value.preStartVerificationIds())
                .containsExactly(id("test:preflight_a"), id("test:preflight_z"));
    }

    @Test
    void refusesImplicitOrNonItemHeatedFuelAndFuelOnOtherTiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> heated(null));
        assertThatIllegalArgumentException().isThrownBy(() -> heated(
                new ProcessResource(
                        id("minecraft:lava"), GenericResourceType.FLUID, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecipeHeatRequirement(
                id("test:none"), RecipeHeatTier.NONE,
                id("create:mixing/example"), id("create:mixing"), false,
                Set.of(id("test:preflight")), Set.of(id("test:evidence")),
                Set.of(id("test:diagnostic")), Optional.of(item("minecraft:coal", 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecipeHeatRequirement(
                id("test:heated"), RecipeHeatTier.HEATED,
                id("create:mixing/example"), id("create:mixing"), false,
                Set.of(id("test:preflight")), Set.of(id("test:evidence")),
                Set.of(id("test:diagnostic")), Optional.of(item("minecraft:coal", 1))));
    }

    private static RecipeHeatRequirement heated(ProcessResource fuel) {
        return new RecipeHeatRequirement(
                id("test:heated"), RecipeHeatTier.HEATED,
                id("create:mixing/brass_ingot"), id("create:mixing"), true,
                Set.of(id("test:preflight_z"), id("test:preflight_a")),
                Set.of(id("test:evidence")), Set.of(id("test:diagnostic")),
                Optional.ofNullable(fuel));
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
