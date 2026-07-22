package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;

/** Versioned adapter boundary that publishes concrete implementation descriptions. */
public interface RuntimeMachineImplementationCatalogAdapter {
    ResourceId adapterId();

    RuntimeMachineImplementationCatalogResult snapshot(
            RuntimeRecipeCatalogSnapshot recipes,
            RuntimeMachineCapabilityCatalogSnapshot capabilities,
            boolean reloadInProgress);
}
