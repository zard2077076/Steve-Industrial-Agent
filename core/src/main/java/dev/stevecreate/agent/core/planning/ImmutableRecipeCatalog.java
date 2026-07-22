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

/** Immutable recipe catalog with canonical recipe and output-candidate ordering. */
public final class ImmutableRecipeCatalog implements RecipeCatalog {
    public static final int MAX_RECIPES = 4_096;

    private static final Comparator<CatalogRecipe> RECIPE_ORDER = Comparator
            .comparing(recipe -> recipe.recipeId().toString());

    private final List<CatalogRecipe> recipes;
    private final Map<ResourceId, CatalogRecipe> byId;
    private final Map<ResourceKey, List<CatalogRecipe>> byOutput;

    public ImmutableRecipeCatalog(List<CatalogRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        if (recipes.isEmpty() || recipes.size() > MAX_RECIPES) {
            throw new IllegalArgumentException("Recipe catalog count must be between 1 and " + MAX_RECIPES);
        }
        List<CatalogRecipe> sorted = new ArrayList<>(recipes.size());
        Map<ResourceId, CatalogRecipe> ids = new LinkedHashMap<>();
        for (CatalogRecipe value : recipes) {
            CatalogRecipe recipe = Objects.requireNonNull(value, "recipes element");
            if (ids.putIfAbsent(recipe.recipeId(), recipe) != null) {
                throw new IllegalArgumentException("Duplicate recipe ID: " + recipe.recipeId());
            }
            sorted.add(recipe);
        }
        sorted.sort(RECIPE_ORDER);
        this.recipes = List.copyOf(sorted);

        Map<ResourceId, CatalogRecipe> orderedIds = new LinkedHashMap<>();
        Map<ResourceKey, List<CatalogRecipe>> outputs = new LinkedHashMap<>();
        for (CatalogRecipe recipe : this.recipes) {
            orderedIds.put(recipe.recipeId(), recipe);
            for (ProcessResource output : recipe.outputs()) {
                outputs.computeIfAbsent(ResourceKey.of(output), ignored -> new ArrayList<>())
                        .add(recipe);
            }
            for (ProcessResource byproduct : recipe.optionalByproducts()) {
                outputs.computeIfAbsent(ResourceKey.of(byproduct), ignored -> new ArrayList<>())
                        .add(recipe);
            }
        }
        Map<ResourceKey, List<CatalogRecipe>> immutableOutputs = new LinkedHashMap<>();
        outputs.forEach((key, value) -> immutableOutputs.put(key, List.copyOf(value)));
        this.byId = Collections.unmodifiableMap(orderedIds);
        this.byOutput = Collections.unmodifiableMap(immutableOutputs);
    }

    @Override
    public List<CatalogRecipe> recipes() {
        return recipes;
    }

    @Override
    public Optional<CatalogRecipe> find(ResourceId recipeId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(recipeId, "recipeId")));
    }

    @Override
    public List<CatalogRecipe> recipesProducing(
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
