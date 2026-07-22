package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Optional;

/** Read-only loader-neutral catalog of runtime-discovered, ingredient-unresolved entries. */
public interface RuntimeRecipeCatalog {
    List<RuntimeRecipeCatalogEntry> recipes();

    Optional<RuntimeRecipeCatalogEntry> find(ResourceId recipeId);

    List<RuntimeRecipeCatalogEntry> recipesProducing(
            ResourceId resourceId,
            GenericResourceType resourceType);
}
