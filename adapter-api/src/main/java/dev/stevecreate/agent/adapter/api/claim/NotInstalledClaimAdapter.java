package dev.stevecreate.agent.adapter.api.claim;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionEvidenceState;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public final class NotInstalledClaimAdapter implements ClaimAdapter {
    private static final ResourceId ID = ResourceId.parse("generic:not-installed-claim");

    @Override
    public ResourceId adapterId() { return ID; }

    @Override
    public PermissionEvidence query(PermissionQuery query) {
        Objects.requireNonNull(query, "query");
        return ClaimEvidenceFactory.create(
                query, PermissionDecision.NOT_INSTALLED, PermissionEvidenceState.VERIFIED,
                ID, "generic:not-installed", "verified adapter absence");
    }
}
