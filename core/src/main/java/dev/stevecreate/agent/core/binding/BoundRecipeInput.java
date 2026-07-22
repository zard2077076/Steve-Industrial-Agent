package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import java.util.Objects;

/** Preserved runtime Ingredient identity plus the exact resource selected for planning. */
public record BoundRecipeInput(
        ResourceId recipeId,
        int inputIndex,
        RecipeIngredientKind ingredientKind,
        String ingredientIdentity,
        ResourceId selectedResource,
        long amount,
        String selectionReason) {
    public BoundRecipeInput {
        Objects.requireNonNull(recipeId, "recipeId");
        if (inputIndex < 0 || inputIndex >= 32) {
            throw new IllegalArgumentException("inputIndex is outside the recipe input bound");
        }
        Objects.requireNonNull(ingredientKind, "ingredientKind");
        ingredientIdentity = requireText(ingredientIdentity, "ingredientIdentity", 16_384);
        Objects.requireNonNull(selectedResource, "selectedResource");
        if (amount < 1 || amount > 1_000_000_000L) {
            throw new IllegalArgumentException("amount is outside the process-resource bound");
        }
        selectionReason = requireText(selectionReason, "selectionReason", 2_048);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
