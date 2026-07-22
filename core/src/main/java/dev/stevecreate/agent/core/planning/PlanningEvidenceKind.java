package dev.stevecreate.agent.core.planning;

/** Loader-neutral reasons recorded while deriving a coordinate-free candidate. */
public enum PlanningEvidenceKind {
    GOAL_SATISFIED,
    RECIPE_SELECTED,
    QUANTITY_SCALED,
    DEPENDENCY_RESOLVED,
    OWNED_RESOURCE_APPLIED,
    CAPABILITY_REQUIRED
}
