package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringRecipe;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringResourceRegistration;
import dev.stevecreate.agent.core.industrial.IndustrialCapability;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImmersiveEngineeringAdapterContractTest {
    @Test
    void retainsOfficialMetalPressEnergyMoldInputAndTagBackedOutputSemantics() {
        RuntimeFingerprint runtime = new RuntimeFingerprint("1.20.1", "forge", "47.4.10",
                Map.of("immersiveengineering", "10.2.0-183"), "ie1020", 1);
        ImmersiveEngineeringRecipe recipe = new ImmersiveEngineeringRecipe(
                id("immersiveengineering:metalpress/plate_iron"),
                id("immersiveengineering:metal_press"),
                List.of(new ProcessResource(id("forge:ingots/iron"), GenericResourceType.ITEM, 1)),
                List.of(new ProcessResource(id("forge:plates/iron"), GenericResourceType.ITEM, 1)),
                List.of(), Optional.of(new ProcessResource(
                        id("immersiveengineering:mold_plate"), GenericResourceType.ITEM, 1)),
                2_400, true, "a".repeat(64), List.of());
        ImmersiveEngineeringRecipeCatalogSnapshot snapshot =
                new ImmersiveEngineeringRecipeCatalogSnapshot(runtime, 0,
                        "b".repeat(64), List.of(recipe));

        assertThat(snapshot.recipes()).containsExactly(recipe);
        assertThat(recipe.energyRequiredFe()).isEqualTo(2_400);
        assertThat(recipe.retainedMoldOrTool()).contains(new ProcessResource(
                id("immersiveengineering:mold_plate"), GenericResourceType.ITEM, 1));
        assertThat(recipe.outputs().get(0).resourceId().toString()).isEqualTo("forge:plates/iron");
    }

    @Test
    void resourceRegistrationIsReviewedReadOnlyAndRequiresExactReobservation() {
        ImmersiveEngineeringResourceRegistration registration =
                new ImmersiveEngineeringResourceRegistration(
                        id("steve_industrial:ie_metal_press"),
                        id("immersiveengineering:metal_press"),
                        List.of(id("immersiveengineering:metalpress/plate_iron")),
                        Set.of(GenericResourceType.ITEM,
                                GenericResourceType.ELECTRICAL_ENERGY),
                        Set.of(IndustrialCapability.ITEM_PROCESSING,
                                IndustrialCapability.ELECTRICAL_POWER,
                                IndustrialCapability.MULTIBLOCK,
                                IndustrialCapability.LOGISTICS),
                        true, false);

        assertThat(registration.supports(id("steve_industrial:ie_metal_press"),
                id("immersiveengineering:metalpress/plate_iron"))).isTrue();
        assertThat(registration.grantsMutationAuthority()).isFalse();
        assertThat(registration.exactEndpointReobservationRequired()).isTrue();
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
