package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecipeCatalogTest {
    @Test
    void ordersRecipesAndOutputCandidatesDeterministically() {
        CatalogRecipe pressing = recipe(
                "create:pressing/iron_ingot", "create:pressing",
                "minecraft:iron_ingot", "create:iron_sheet");
        CatalogRecipe compacting = recipe(
                "example:compacting/iron_ingot", "example:compacting",
                "minecraft:iron_ingot", "create:iron_sheet");
        CatalogRecipe milling = recipe(
                "create:milling/cobblestone", "create:milling",
                "minecraft:cobblestone", "minecraft:gravel");

        RecipeCatalog catalog = new ImmutableRecipeCatalog(List.of(pressing, milling, compacting));

        assertThat(catalog.recipes()).extracting(recipe -> recipe.recipeId().toString())
                .containsExactly(
                        "create:milling/cobblestone",
                        "create:pressing/iron_ingot",
                        "example:compacting/iron_ingot");
        assertThat(catalog.recipesProducing(id("create:iron_sheet"), GenericResourceType.ITEM))
                .extracting(recipe -> recipe.recipeId().toString())
                .containsExactly("create:pressing/iron_ingot", "example:compacting/iron_ingot");
        assertThat(catalog.find(id("create:milling/cobblestone"))).contains(milling);
        assertThat(catalog.find(id("test:missing"))).isEmpty();
    }

    @Test
    void defensivelyCopiesAndRejectsDuplicateRecipeIds() {
        CatalogRecipe recipe = recipe(
                "test:one", "test:type", "test:input", "test:output");
        List<CatalogRecipe> source = new ArrayList<>(List.of(recipe));
        RecipeCatalog catalog = new ImmutableRecipeCatalog(source);
        source.clear();

        assertThat(catalog.recipes()).containsExactly(recipe);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> catalog.recipes().clear());
        assertThatIllegalArgumentException().isThrownBy(() -> new ImmutableRecipeCatalog(
                List.of(recipe, recipe)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ImmutableRecipeCatalog(List.of()));
    }

    private static CatalogRecipe recipe(
            String recipeId,
            String recipeType,
            String input,
            String output) {
        return new CatalogRecipe(
                id(recipeId),
                id(recipeType),
                List.of(item(input)),
                List.of(item(output)),
                List.of(),
                Set.of(id("test:processing")),
                Set.of(GenericResourceType.ITEM),
                OptionalLong.of(20),
                new RecipeSource(id("test:adapter"), "test", "fixture=1", false));
    }

    private static ProcessResource item(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
