package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A recipe asking for a thing four times is asking for four of it.
 *
 * <p>Duplicate ingredients were rejected outright, and create:compacting/ice — two by two
 * of one item, which is what compacting is — was the only recipe in the whole registry
 * that crashed mapping instead of being refused for a stated reason.
 */
class DuplicateIngredientMergeTest {
    private static final ResourceId SNOW = ResourceId.parse("minecraft:snow_block");

    /** Compacting ice: four snow blocks arriving as four separate ingredients. */
    @Test
    void mergesRepeatsOfTheSameExactItemIntoOneAmount() {
        RuntimeRecipeCatalogEntry entry = entry(List.of(
                exact(SNOW, 1), exact(SNOW, 1), exact(SNOW, 1), exact(SNOW, 1)));

        assertThat(entry.inputs()).hasSize(1);
        assertThat(entry.inputs().get(0).amount()).isEqualTo(4);
        assertThat(entry.inputs().get(0).runtimeCandidates()).containsExactly(SNOW);
    }

    /** Uneven amounts add up the same way. */
    @Test
    void addsTheAmountsRatherThanTakingTheLargest() {
        RuntimeRecipeCatalogEntry entry = entry(List.of(exact(SNOW, 3), exact(SNOW, 5)));

        assertThat(entry.inputs().get(0).amount()).isEqualTo(8);
    }

    /** Distinct items stay distinct — merging is by identity, not by position. */
    @Test
    void keepsDifferentItemsApart() {
        RuntimeRecipeCatalogEntry entry = entry(List.of(
                exact(SNOW, 1), exact(ResourceId.parse("minecraft:ice"), 2), exact(SNOW, 1)));

        assertThat(entry.inputs()).hasSize(2);
        assertThat(entry.inputs()).anySatisfy(input -> {
            assertThat(input.runtimeCandidates()).containsExactly(SNOW);
            assertThat(input.amount()).isEqualTo(2);
        });
    }

    /** An unsupported ingredient is still refused, before anything is merged. */
    @Test
    void stillRefusesAnUnsupportedComplexIngredient() {
        assertThatThrownBy(() -> entry(List.of(
                new RecipeIngredient.UnsupportedComplexIngredient("custom", "not modelled", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported complex ingredients");
    }

    private static RecipeIngredient exact(ResourceId resource, long amount) {
        return new RecipeIngredient.ExactResource(resource, amount);
    }

    private static RuntimeRecipeCatalogEntry entry(List<RecipeIngredient> inputs) {
        ResourceId recipeId = ResourceId.parse("create:compacting/ice");
        ResourceId recipeType = ResourceId.parse("create:compacting");
        return new RuntimeRecipeCatalogEntry(recipeId, recipeType, inputs,
                List.of(new ProcessResource(ResourceId.parse("minecraft:ice"),
                        GenericResourceType.ITEM, 1)),
                List.of(), Set.of(recipeType), Set.of(GenericResourceType.ITEM),
                OptionalLong.of(100),
                new RecipeSource(ResourceId.parse("steve_industrial:adapter/create_v606"),
                        "create", "fingerprint", true),
                RecipeHeatRequirement.none(recipeId, recipeType));
    }
}
