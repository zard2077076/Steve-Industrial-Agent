package dev.stevecreate.agent.core.execution.readiness;

import java.util.Objects;

public record ExecutionReadinessSuccess(ExecutionReadyPlan plan)
        implements ExecutionReadinessResult {
    public ExecutionReadinessSuccess {
        Objects.requireNonNull(plan, "plan");
    }
}
