package dev.stevecreate.agent.adapter.api.claim;

import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.model.ResourceId;

/** Version/mod-specific claim integrations implement this loader-neutral boundary. */
public interface ClaimAdapter {
    ResourceId adapterId();

    PermissionEvidence query(PermissionQuery query);
}
