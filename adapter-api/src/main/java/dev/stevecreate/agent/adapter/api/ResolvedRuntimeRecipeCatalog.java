package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.RecipeCatalog;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Request-specific exact catalog plus complete runtime ingredient provenance and limitations. */
public record ResolvedRuntimeRecipeCatalog(
        RecipeCatalog catalog,
        List<ResolvedRuntimeRecipe> resolutions,
        List<RuntimeKnowledgeFailure> limitations,
        String runtimeFingerprint) {
    public ResolvedRuntimeRecipeCatalog {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(resolutions, "resolutions");
        if (resolutions.isEmpty() || resolutions.size() != catalog.recipes().size()) {
            throw new IllegalArgumentException(
                    "Resolutions must exactly cover the nonempty exact recipe catalog");
        }
        List<ResolvedRuntimeRecipe> sorted = new ArrayList<>(resolutions.size());
        Set<ResourceId> ids = new HashSet<>();
        for (ResolvedRuntimeRecipe resolution : resolutions) {
            ResolvedRuntimeRecipe value = Objects.requireNonNull(resolution, "resolutions element");
            if (!ids.add(value.resolvedRecipe().recipeId())) {
                throw new IllegalArgumentException(
                        "Duplicate resolved runtime recipe ID: " + value.resolvedRecipe().recipeId());
            }
            sorted.add(value);
        }
        sorted.sort(Comparator.comparing(value -> value.resolvedRecipe().recipeId().toString()));
        List<CatalogRecipe> exact = sorted.stream().map(ResolvedRuntimeRecipe::resolvedRecipe).toList();
        if (!catalog.recipes().equals(exact)) {
            throw new IllegalArgumentException("Exact catalog order/content does not match resolutions");
        }
        resolutions = List.copyOf(sorted);
        Objects.requireNonNull(limitations, "limitations");
        List<RuntimeKnowledgeFailure> limitationCopy = new ArrayList<>(limitations.size());
        limitations.forEach(value -> limitationCopy.add(
                Objects.requireNonNull(value, "limitations element")));
        limitationCopy.sort(Comparator
                .comparing((RuntimeKnowledgeFailure value) ->
                        value.recipeId().map(Object::toString).orElse(""))
                .thenComparing(value -> value.code().ordinal()));
        limitations = List.copyOf(limitationCopy);
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 2_048) {
            throw new IllegalArgumentException("runtimeFingerprint is blank or unbounded");
        }
    }
}
