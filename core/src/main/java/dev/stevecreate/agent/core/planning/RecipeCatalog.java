package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Optional;

/** Deterministic loader-neutral recipe lookup contract. */
public interface RecipeCatalog {
    List<CatalogRecipe> recipes();

    Optional<CatalogRecipe> find(ResourceId recipeId);

    List<CatalogRecipe> recipesProducing(ResourceId resourceId, GenericResourceType resourceType);
}
