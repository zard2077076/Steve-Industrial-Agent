package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable runtime catalog with canonical recipe and output-candidate ordering. */
public final class ImmutableRuntimeRecipeCatalog implements RuntimeRecipeCatalog {
    public static final int MAX_RECIPES = 4_096;

    private final List<RuntimeRecipeCatalogEntry> recipes;
    private final Map<ResourceId, RuntimeRecipeCatalogEntry> byId;
    private final Map<ResourceKey, List<RuntimeRecipeCatalogEntry>> byOutput;

    public ImmutableRuntimeRecipeCatalog(List<RuntimeRecipeCatalogEntry> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        if (recipes.isEmpty() || recipes.size() > MAX_RECIPES) {
            throw new IllegalArgumentException(
                    "Runtime recipe catalog count must be between 1 and " + MAX_RECIPES);
        }
        List<RuntimeRecipeCatalogEntry> sorted = new ArrayList<>(recipes.size());
        Map<ResourceId, RuntimeRecipeCatalogEntry> unique = new LinkedHashMap<>();
        for (RuntimeRecipeCatalogEntry value : recipes) {
            RuntimeRecipeCatalogEntry recipe = Objects.requireNonNull(value, "recipes element");
            if (unique.putIfAbsent(recipe.recipeId(), recipe) != null) {
                throw new IllegalArgumentException("Duplicate runtime recipe ID: " + recipe.recipeId());
            }
            sorted.add(recipe);
        }
        sorted.sort(Comparator.comparing(value -> value.recipeId().toString()));
        this.recipes = List.copyOf(sorted);

        Map<ResourceId, RuntimeRecipeCatalogEntry> orderedIds = new LinkedHashMap<>();
        Map<ResourceKey, List<RuntimeRecipeCatalogEntry>> outputs = new LinkedHashMap<>();
        for (RuntimeRecipeCatalogEntry recipe : this.recipes) {
            orderedIds.put(recipe.recipeId(), recipe);
            recipe.outputs().forEach(output -> outputs
                    .computeIfAbsent(ResourceKey.of(output), ignored -> new ArrayList<>())
                    .add(recipe));
            recipe.optionalByproducts().forEach(output -> outputs
                    .computeIfAbsent(ResourceKey.of(output), ignored -> new ArrayList<>())
                    .add(recipe));
        }
        Map<ResourceKey, List<RuntimeRecipeCatalogEntry>> immutableOutputs = new LinkedHashMap<>();
        outputs.forEach((key, value) -> immutableOutputs.put(key, List.copyOf(value)));
        this.byId = Collections.unmodifiableMap(orderedIds);
        this.byOutput = Collections.unmodifiableMap(immutableOutputs);
    }

    @Override
    public List<RuntimeRecipeCatalogEntry> recipes() {
        return recipes;
    }

    @Override
    public Optional<RuntimeRecipeCatalogEntry> find(ResourceId recipeId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(recipeId, "recipeId")));
    }

    @Override
    public List<RuntimeRecipeCatalogEntry> recipesProducing(
            ResourceId resourceId,
            GenericResourceType resourceType) {
        return byOutput.getOrDefault(
                new ResourceKey(
                        Objects.requireNonNull(resourceId, "resourceId"),
                        Objects.requireNonNull(resourceType, "resourceType")),
                List.of());
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }
}
