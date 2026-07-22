package dev.stevecreate.agent.core.planning;

/** Stable typed failure identities for deterministic goal planning. */
public enum PlanningFailureCode {
    TARGET_NOT_FOUND,
    RECIPE_NOT_FOUND,
    MACHINE_CAPABILITY_MISSING,
    RESOURCE_TYPE_UNSUPPORTED,
    DEPENDENCY_CYCLE,
    DEPTH_LIMIT_EXCEEDED,
    CONSTRAINT_CONFLICT,
    REQUIRED_MOD_UNAVAILABLE,
    AMBIGUOUS_OUTPUT,
    UNSATISFIABLE_INPUT,
    INVALID_QUANTITY
}
