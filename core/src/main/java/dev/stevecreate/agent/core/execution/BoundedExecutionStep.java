package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable bounded implementation of the generic execution-step contract. */
public record BoundedExecutionStep(
        ResourceId stepId,
        GenericExecutionPhase phase,
        List<StepCondition> preconditions,
        StepActionDescriptor action,
        List<StepCondition> successConditions,
        List<StepCondition> failureConditions,
        int timeoutTicks,
        RetryPolicy retryPolicy,
        boolean cancellable,
        Optional<StepActionDescriptor> rollbackAction,
        Set<ResourceId> requiredEvidence) implements GenericExecutionStep {
    public static final int MAX_CONDITIONS_PER_KIND = 32;
    public static final int MAX_TIMEOUT_TICKS = 2_400;

    public BoundedExecutionStep {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(phase, "phase");
        preconditions = copyConditions(preconditions, "preconditions", false);
        Objects.requireNonNull(action, "action");
        successConditions = copyConditions(successConditions, "successConditions", true);
        failureConditions = copyConditions(failureConditions, "failureConditions", true);
        rejectDuplicateConditionIds(preconditions, successConditions, failureConditions);
        if (timeoutTicks < 1 || timeoutTicks > MAX_TIMEOUT_TICKS) {
            throw new IllegalArgumentException(
                    "timeoutTicks must be between 1 and " + MAX_TIMEOUT_TICKS);
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        rollbackAction = Objects.requireNonNull(rollbackAction, "rollbackAction");
        requiredEvidence = ExecutionModelValues.copyRequirements(
                requiredEvidence, "requiredEvidence", true);
    }

    private static List<StepCondition> copyConditions(
            List<StepCondition> values,
            String name,
            boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty())
                || values.size() > MAX_CONDITIONS_PER_KIND) {
            String minimum = requireNonEmpty ? "1" : "0";
            throw new IllegalArgumentException(
                    name + " count must be between " + minimum
                            + " and " + MAX_CONDITIONS_PER_KIND);
        }
        List<StepCondition> copy = new ArrayList<>(values.size());
        for (StepCondition value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    private static void rejectDuplicateConditionIds(List<StepCondition>... conditionLists) {
        Set<ResourceId> ids = new HashSet<>();
        for (List<StepCondition> conditions : conditionLists) {
            for (StepCondition condition : conditions) {
                if (!ids.add(condition.conditionId())) {
                    throw new IllegalArgumentException(
                            "Duplicate condition id in execution step: " + condition.conditionId());
                }
            }
        }
    }
}
