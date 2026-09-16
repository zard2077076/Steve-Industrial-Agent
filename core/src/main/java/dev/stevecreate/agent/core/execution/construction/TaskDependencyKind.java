package dev.stevecreate.agent.core.execution.construction;

public enum TaskDependencyKind {
    FINISH_TO_START,
    MATERIAL_DELIVERY,
    SHARED_INFRASTRUCTURE_READY,
    VERIFICATION_GATE
}
