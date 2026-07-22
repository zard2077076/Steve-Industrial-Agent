package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Optional;

/** Deterministic loader-neutral machine-capability lookup contract. */
public interface MachineCapabilityCatalog {
    List<MachineCapability> capabilities();

    Optional<MachineCapability> find(ResourceId capabilityId);

    List<MachineCapability> capabilitiesForRecipeType(ResourceId recipeType);

    List<MachineCapability> capabilitiesForAdapter(ResourceId adapterId);

    List<MachineCapability> compatibleCapabilities(CatalogRecipe recipe);
}
