package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Loader-neutral execution-step contract. Runtime work is delegated by typed handler IDs. */
public sealed interface GenericExecutionStep permits BoundedExecutionStep {
    ResourceId stepId();

    GenericExecutionPhase phase();

    List<StepCondition> preconditions();

    StepActionDescriptor action();

    List<StepCondition> successConditions();

    List<StepCondition> failureConditions();

    int timeoutTicks();

    RetryPolicy retryPolicy();

    boolean cancellable();

    Optional<StepActionDescriptor> rollbackAction();

    Set<ResourceId> requiredEvidence();
}
