package dev.stevecreate.agent.core.execution.readiness;

public enum ExecutionReadinessStage {
    PLAN_VALIDATION,
    RUNTIME_VALIDATION,
    WORLD_AUTHORITY,
    AREA_REVALIDATION,
    RESOURCE_VALIDATION,
    SAFETY_VALIDATION,
    SESSION_VALIDATION,
    EXECUTION
}
