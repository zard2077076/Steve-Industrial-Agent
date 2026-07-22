package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One exact planning recipe with immutable provenance to its unresolved runtime entry. */
public record ResolvedRuntimeRecipe(
        RuntimeRecipeCatalogEntry runtimeEntry,
        CatalogRecipe resolvedRecipe,
        List<RuntimeIngredientSelection> selections) {
    public ResolvedRuntimeRecipe {
        Objects.requireNonNull(runtimeEntry, "runtimeEntry");
        Objects.requireNonNull(resolvedRecipe, "resolvedRecipe");
        if (!runtimeEntry.recipeId().equals(resolvedRecipe.recipeId())
                || !runtimeEntry.recipeType().equals(resolvedRecipe.recipeType())
                || !runtimeEntry.source().equals(resolvedRecipe.source())) {
            throw new IllegalArgumentException(
                    "Resolved recipe identity, type and source must match its runtime entry");
        }
        Objects.requireNonNull(selections, "selections");
        if (selections.size() != runtimeEntry.inputs().size()) {
            throw new IllegalArgumentException("Every runtime input requires exactly one selection");
        }
        List<RuntimeIngredientSelection> sorted = new ArrayList<>(selections.size());
        for (RuntimeIngredientSelection selection : selections) {
            RuntimeIngredientSelection value = Objects.requireNonNull(selection, "selections element");
            if (!value.recipeId().equals(runtimeEntry.recipeId())) {
                throw new IllegalArgumentException("Selection recipe ID does not match its runtime entry");
            }
            sorted.add(value);
        }
        sorted.sort(Comparator.comparingInt(RuntimeIngredientSelection::inputIndex));
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index).inputIndex() != index) {
                throw new IllegalArgumentException("Selections must exactly cover every input index");
            }
        }
        selections = List.copyOf(sorted);
    }
}
