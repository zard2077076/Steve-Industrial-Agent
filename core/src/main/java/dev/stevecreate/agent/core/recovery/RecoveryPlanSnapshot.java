package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Serializable plan identity that must match a trusted runtime plan before recovery. */
public record RecoveryPlanSnapshot(
        ResourceId planId,
        RecoveryGraphSnapshot graph,
        ResourceId recipeId,
        ResourceId recipeType,
        List<ResourceId> orderedStepIds,
        String fingerprint) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public RecoveryPlanSnapshot {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(orderedStepIds, "orderedStepIds");
        if (orderedStepIds.isEmpty()
                || orderedStepIds.size() > GenericExecutionPlan.MAX_STEPS) {
            throw new IllegalArgumentException("orderedStepIds has an invalid count");
        }
        List<ResourceId> copy = new ArrayList<>(orderedStepIds.size());
        Set<ResourceId> unique = new HashSet<>();
        for (ResourceId value : orderedStepIds) {
            ResourceId id = Objects.requireNonNull(value, "orderedStepIds element");
            if (!unique.add(id)) {
                throw new IllegalArgumentException("orderedStepIds contains duplicate id " + id);
            }
            copy.add(id);
        }
        orderedStepIds = List.copyOf(copy);
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!SHA_256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("Plan fingerprint must be lowercase SHA-256 hex");
        }
    }

    public static RecoveryPlanSnapshot capture(GenericExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new RecoveryPlanSnapshot(
                plan.planId(),
                RecoveryGraphSnapshot.capture(plan.machineGraph()),
                plan.processSpec().recipeId(),
                plan.processSpec().recipeType(),
                plan.steps().stream().map(value -> value.stepId()).toList(),
                RecoveryFingerprint.plan(plan));
    }
}
