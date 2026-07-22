package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;

/** Loader-neutral adapter boundary for runtime-attributed machine capability declarations. */
public interface RuntimeMachineCapabilityCatalogAdapter {
    ResourceId adapterId();

    RuntimeMachineCapabilityCatalogResult snapshot(RuntimeRecipeCatalogSnapshot recipeCatalog);
}
