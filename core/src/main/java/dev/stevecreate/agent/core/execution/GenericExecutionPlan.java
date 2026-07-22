package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable in-memory plan joining one graph, process, and ordered bounded step sequence. */
public record GenericExecutionPlan(
        ResourceId planId,
        UnifiedMachineGraph machineGraph,
        GenericProcessSpec processSpec,
        List<GenericExecutionStep> steps) {
    public static final int MAX_STEPS = 256;

    public GenericExecutionPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(machineGraph, "machineGraph");
        Objects.requireNonNull(processSpec, "processSpec");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "steps count must be between 1 and " + MAX_STEPS);
        }
        List<GenericExecutionStep> copy = new ArrayList<>(steps.size());
        Set<ResourceId> stepIds = new HashSet<>();
        for (GenericExecutionStep step : steps) {
            GenericExecutionStep value = Objects.requireNonNull(step, "steps element");
            if (!stepIds.add(value.stepId())) {
                throw new IllegalArgumentException(
                        "Duplicate execution step id: " + value.stepId());
            }
            copy.add(value);
        }
        steps = Collections.unmodifiableList(copy);
    }

    public GenericExecutionStep step(ResourceId stepId) {
        Objects.requireNonNull(stepId, "stepId");
        return steps.stream()
                .filter(step -> step.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown execution step: " + stepId));
    }
}
