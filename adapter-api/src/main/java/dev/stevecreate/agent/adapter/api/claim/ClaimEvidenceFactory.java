package dev.stevecreate.agent.adapter.api.claim;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionEvidenceState;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.model.ResourceId;

final class ClaimEvidenceFactory {
    private ClaimEvidenceFactory() {}

    static PermissionEvidence create(
            PermissionQuery query,
            PermissionDecision decision,
            PermissionEvidenceState state,
            ResourceId adapterId,
            String source,
            String provenance) {
        return new PermissionEvidence(
                query, decision, adapterId, state, source, query.requestedAt(),
                query.generation(), query.fingerprint(), provenance);
    }
}
