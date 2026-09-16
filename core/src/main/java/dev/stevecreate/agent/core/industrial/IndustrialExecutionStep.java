package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Set;
import java.util.Objects;

/** Deterministic lifecycle step derived from an adapter-owned action contract. */
public record IndustrialExecutionStep(
        ResourceId stepId,
        ResourceId actionId,
        IndustrialActionType actionType,
        Set<ResourceId> predecessorStepIds,
        boolean directSupported,
        boolean botSupported,
        int maximumAttempts,
        String verificationContract) {
    public IndustrialExecutionStep {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionType, "actionType");
        predecessorStepIds = Set.copyOf(Objects.requireNonNull(
                predecessorStepIds, "predecessorStepIds"));
        if (!directSupported && !botSupported) {
            throw new IllegalArgumentException("industrial step has no executor");
        }
        if (maximumAttempts < 1 || maximumAttempts > 16) {
            throw new IllegalArgumentException("industrial step attempt bound is invalid");
        }
        Objects.requireNonNull(verificationContract, "verificationContract");
        if (verificationContract.isBlank() || verificationContract.length() > 2_048) {
            throw new IllegalArgumentException("industrial step verification is blank or unbounded");
        }
    }
}
