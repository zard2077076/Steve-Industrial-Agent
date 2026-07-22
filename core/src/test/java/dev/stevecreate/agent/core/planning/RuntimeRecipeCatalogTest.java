package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeRecipeCatalogTest {
    @Test
    void keepsTagIdentityWhileIndexingRuntimeRecipesDeterministically() {
        RuntimeRecipeCatalogEntry pressing = recipe(
                "create:pressing/iron_ingot",
                "create:pressing",
                new RecipeIngredient.TagReference(
                        id("forge:ingots/iron"),
                        List.of(id("minecraft:iron_ingot")),
                        "sha256:runtime",
                        1),
                "create:iron_sheet");
        RuntimeRecipeCatalogEntry milling = recipe(
                "create:milling/cobblestone",
                "create:milling",
                new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1),
                "minecraft:gravel");
        RuntimeRecipeCatalog catalog = new ImmutableRuntimeRecipeCatalog(List.of(pressing, milling));

        assertThat(catalog.recipes()).extracting(RuntimeRecipeCatalogEntry::recipeId)
                .containsExactly(id("create:milling/cobblestone"), id("create:pressing/iron_ingot"));
        assertThat(catalog.find(id("create:pressing/iron_ingot"))).contains(pressing);
        assertThat(catalog.recipesProducing(id("create:iron_sheet"), GenericResourceType.ITEM))
                .containsExactly(pressing);
        assertThat(pressing.inputs()).singleElement().isInstanceOf(RecipeIngredient.TagReference.class);
    }

    @Test
    void rejectsDuplicateIdsAndUnsupportedIngredientEntries() {
        RuntimeRecipeCatalogEntry value = recipe(
                "create:milling/cobblestone",
                "create:milling",
                new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1),
                "minecraft:gravel");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ImmutableRuntimeRecipeCatalog(List.of(value, value)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeRecipeCatalogEntry(
                id("create:milling/complex"),
                id("create:milling"),
                List.of(new RecipeIngredient.UnsupportedComplexIngredient("forge:nbt", "NBT", 1)),
                List.of(item("minecraft:gravel")),
                List.of(),
                Set.of(id("create:milling")),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.empty(),
                source()));
    }

    private static RuntimeRecipeCatalogEntry recipe(
            String recipeId,
            String recipeType,
            RecipeIngredient ingredient,
            String output) {
        return new RuntimeRecipeCatalogEntry(
                id(recipeId),
                id(recipeType),
                List.of(ingredient),
                List.of(item(output)),
                List.of(),
                Set.of(id(recipeType)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(250),
                source());
    }

    private static RecipeSource source() {
        return new RecipeSource(id("test:adapter"), "create", "sha256:runtime", true);
    }

    private static ProcessResource item(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
