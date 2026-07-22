package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogRecipeTest {
    @Test
    void describesACompleteLoaderNeutralRuntimeAttributedRecipe() {
        List<ProcessResource> inputs = new ArrayList<>(List.of(item("minecraft:cobblestone", 1)));
        List<ProcessResource> outputs = new ArrayList<>(List.of(item("minecraft:gravel", 1)));
        Set<ResourceId> capabilities = new LinkedHashSet<>(Set.of(id("industrial:milling")));
        Set<GenericResourceType> resourceTypes = new LinkedHashSet<>(Set.of(
                GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER));

        CatalogRecipe recipe = new CatalogRecipe(
                id("create:milling/cobblestone"),
                id("create:milling"),
                inputs,
                outputs,
                List.of(item("minecraft:flint", 1)),
                capabilities,
                resourceTypes,
                OptionalLong.of(250),
                new RecipeSource(
                        id("steve_industrial:create_6_0_6"),
                        "create",
                        "create=6.0.6-150",
                        true));

        inputs.clear();
        outputs.clear();
        capabilities.clear();
        resourceTypes.clear();

        assertThat(recipe.inputs()).containsExactly(item("minecraft:cobblestone", 1));
        assertThat(recipe.outputs()).containsExactly(item("minecraft:gravel", 1));
        assertThat(recipe.optionalByproducts()).containsExactly(item("minecraft:flint", 1));
        assertThat(recipe.requiredMachineCapabilities()).containsExactly(id("industrial:milling"));
        assertThat(recipe.requiredResourceTypes()).containsExactly(
                GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER);
        assertThat(recipe.processingTicks()).hasValue(250);
        assertThat(recipe.source().sourceModId()).isEqualTo("create");
        assertThat(recipe.source().runtimeVerified()).isTrue();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> recipe.inputs().add(item("test:item", 1)));
    }

    @Test
    void rejectsDuplicateOrOverlappingResourcesAndIncompleteTypeDeclarations() {
        assertThatIllegalArgumentException().isThrownBy(() -> recipe(
                List.of(item("minecraft:cobblestone", 1), item("minecraft:cobblestone", 2)),
                List.of(item("minecraft:gravel", 1)), List.of(), Set.of(GenericResourceType.ITEM)));
        assertThatIllegalArgumentException().isThrownBy(() -> recipe(
                List.of(item("minecraft:cobblestone", 1)),
                List.of(item("minecraft:gravel", 1)),
                List.of(item("minecraft:gravel", 1)), Set.of(GenericResourceType.ITEM)));
        assertThatIllegalArgumentException().isThrownBy(() -> recipe(
                List.of(item("minecraft:cobblestone", 1)),
                List.of(item("minecraft:gravel", 1)), List.of(),
                Set.of(GenericResourceType.ROTATIONAL_POWER)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecipeSource(
                id("test:adapter"), "test", " ", false));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecipeSource(
                id("test:adapter"), "Create", "fixture=1", false));
        assertThatIllegalArgumentException().isThrownBy(() -> recipe(
                List.of(item("minecraft:cobblestone", 1)),
                List.of(item("minecraft:gravel", 1)), List.of(), Set.of(GenericResourceType.ITEM),
                OptionalLong.of(0)));
    }

    private static CatalogRecipe recipe(
            List<ProcessResource> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> byproducts,
            Set<GenericResourceType> resourceTypes) {
        return recipe(inputs, outputs, byproducts, resourceTypes, OptionalLong.empty());
    }

    private static CatalogRecipe recipe(
            List<ProcessResource> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> byproducts,
            Set<GenericResourceType> resourceTypes,
            OptionalLong ticks) {
        return new CatalogRecipe(
                id("test:recipe"), id("test:type"), inputs, outputs, byproducts,
                Set.of(id("test:capability")), resourceTypes, ticks,
                new RecipeSource(id("test:adapter"), "test", "fixture=1", false));
    }

    private static ProcessResource item(String id, long amount) {
        return new ProcessResource(ResourceId.parse(id), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
