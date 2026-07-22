package dev.stevecreate.agent.core.planning;

/** Complete fixed checklist required before a logical candidate becomes a typed plan. */
public enum PlanningVerificationCheck {
    DEPENDENCIES_TRACEABLE,
    QUANTITIES_CONSISTENT,
    PRODUCTION_SOURCES_PRESENT,
    CAPABILITIES_DECLARED,
    EDGE_TYPES_MATCH,
    ACYCLIC,
    TARGET_SATISFIED,
    ADAPTERS_SUPPORTED
}
