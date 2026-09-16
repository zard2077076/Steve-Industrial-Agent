package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable material boundary generated from a verified runtime plan. */
public record MaterialRequirementPlan(
        ResourceId planId,
        ResourceId projectId,
        ResourceId target,
        long targetQuantity,
        String runtimeFingerprint,
        String planSha256,
        List<MaterialRequirement> requirements) {
    public MaterialRequirementPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(target, "target");
        if (targetQuantity < 1 || targetQuantity > 64) throw new IllegalArgumentException("target quantity invalid");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
            throw new IllegalArgumentException("runtime fingerprint invalid");
        }
        Objects.requireNonNull(planSha256, "planSha256");
        if (!planSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("plan hash invalid");
        Objects.requireNonNull(requirements, "requirements");
        if (requirements.isEmpty() || requirements.size() > 128) {
            throw new IllegalArgumentException("requirements are unbounded or empty");
        }
        requirements = requirements.stream()
                .sorted(Comparator.comparing(value -> value.requirementId().toString())).toList();
        if (requirements.stream().map(MaterialRequirement::requirementId).distinct().count()
                != requirements.size()) throw new IllegalArgumentException("duplicate requirement identity");
    }
}
