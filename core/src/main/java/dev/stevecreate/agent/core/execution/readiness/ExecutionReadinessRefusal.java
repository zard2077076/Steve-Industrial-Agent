package dev.stevecreate.agent.core.execution.readiness;

import java.util.Objects;

public record ExecutionReadinessRefusal(ExecutionReadinessFailure failure)
        implements ExecutionReadinessResult {
    public ExecutionReadinessRefusal {
        Objects.requireNonNull(failure, "failure");
    }
}
