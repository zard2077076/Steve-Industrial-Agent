package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One exact or runtime-candidate material requirement before source allocation. */
public record MaterialRequirement(
        ResourceId requirementId,
        ResourceId recipeId,
        int inputIndex,
        RecipeIngredientKind ingredientKind,
        String ingredientIdentity,
        List<ResourceId> acceptedResources,
        long quantity) {
    public MaterialRequirement {
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(recipeId, "recipeId");
        if (inputIndex < 0 || inputIndex >= 32) throw new IllegalArgumentException("input index invalid");
        Objects.requireNonNull(ingredientKind, "ingredientKind");
        Objects.requireNonNull(ingredientIdentity, "ingredientIdentity");
        if (ingredientIdentity.isBlank() || ingredientIdentity.length() > 16_384) {
            throw new IllegalArgumentException("ingredient identity invalid");
        }
        Objects.requireNonNull(acceptedResources, "acceptedResources");
        if (acceptedResources.isEmpty() || acceptedResources.size() > 4_096) {
            throw new IllegalArgumentException("accepted resources are unbounded or empty");
        }
        acceptedResources = acceptedResources.stream().distinct()
                .sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (quantity < 1 || quantity > 1_000_000_000L) {
            throw new IllegalArgumentException("requirement quantity invalid");
        }
    }
}
