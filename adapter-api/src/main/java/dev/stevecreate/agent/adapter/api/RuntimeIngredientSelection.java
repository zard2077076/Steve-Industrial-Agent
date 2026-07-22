package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import java.util.Objects;

/** Immutable evidence linking one resolved input back to its runtime ingredient identity. */
public record RuntimeIngredientSelection(
        ResourceId recipeId,
        int inputIndex,
        RecipeIngredientKind ingredientKind,
        String ingredientIdentity,
        ResourceId selectedResource,
        long amount,
        IngredientSelectionReason reason) {
    public RuntimeIngredientSelection {
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
        Objects.requireNonNull(reason, "reason");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximumLength + " characters");
        }
        return value;
    }
}
