package dev.stevecreate.agent.core.planning;

/** Stable typed reasons that a logical candidate cannot become a verified plan. */
public enum PlanningVerificationFailureCode {
    CANDIDATE_GRAPH_MISMATCH,
    DEPENDENCY_UNTRACEABLE,
    QUANTITY_INCONSISTENT,
    PRODUCTION_SOURCE_MISSING,
    MACHINE_CAPABILITY_UNDECLARED,
    EDGE_RESOURCE_MISMATCH,
    DEPENDENCY_CYCLE,
    TARGET_UNSATISFIED,
    ADAPTER_UNAVAILABLE,
    RESOURCE_TYPE_UNSUPPORTED
}
