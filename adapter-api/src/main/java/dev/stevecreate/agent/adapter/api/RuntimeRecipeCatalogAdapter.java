package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;

/** Loader-neutral lifecycle contract implemented by an exact game/mod adapter. */
public interface RuntimeRecipeCatalogAdapter {
    ResourceId adapterId();

    RuntimeFingerprint runtime();

    RuntimeRecipeCatalogResult snapshot();

    long invalidateForReload();
}
