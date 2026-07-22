package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** The identity and optional provenance constraints for one verification observation. */
public record EvidenceRequirement(
        ResourceId requirementId,
        VerificationEvidenceKind kind,
        Optional<ResourceId> requiredSourceId,
        Optional<ResourceId> requiredTargetId) {
    public EvidenceRequirement {
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(kind, "kind");
        requiredSourceId = Objects.requireNonNull(requiredSourceId, "requiredSourceId");
        requiredTargetId = Objects.requireNonNull(requiredTargetId, "requiredTargetId");
    }

    public static EvidenceRequirement anySourceAndTarget(
            ResourceId requirementId,
            VerificationEvidenceKind kind) {
        return new EvidenceRequirement(
                requirementId, kind, Optional.empty(), Optional.empty());
    }

    public static EvidenceRequirement fromSourceToTarget(
            ResourceId requirementId,
            VerificationEvidenceKind kind,
            ResourceId sourceId,
            ResourceId targetId) {
        return new EvidenceRequirement(
                requirementId,
                kind,
                Optional.of(Objects.requireNonNull(sourceId, "sourceId")),
                Optional.of(Objects.requireNonNull(targetId, "targetId")));
    }
}
