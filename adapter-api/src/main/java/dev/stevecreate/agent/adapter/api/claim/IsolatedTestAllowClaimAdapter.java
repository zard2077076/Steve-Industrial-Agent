package dev.stevecreate.agent.adapter.api.claim;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionEvidenceState;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public final class IsolatedTestAllowClaimAdapter implements ClaimAdapter {
    private static final ResourceId ID = ResourceId.parse("test:isolated-claim");

    @Override
    public ResourceId adapterId() { return ID; }

    @Override
    public PermissionEvidence query(PermissionQuery query) {
        Objects.requireNonNull(query, "query");
        boolean isolated = query.environmentClassification()
                == WorldEnvironmentType.ISOLATED_TEST_WORLD;
        return ClaimEvidenceFactory.create(
                query, isolated ? PermissionDecision.ALLOWED : PermissionDecision.DENIED,
                PermissionEvidenceState.VERIFIED, ID, "test:isolated-claim",
                "explicit isolated test adapter");
    }
}
