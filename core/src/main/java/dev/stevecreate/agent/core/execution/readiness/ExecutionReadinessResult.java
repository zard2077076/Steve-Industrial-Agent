package dev.stevecreate.agent.core.execution.readiness;

public sealed interface ExecutionReadinessResult
        permits ExecutionReadinessSuccess, ExecutionReadinessRefusal {
}
