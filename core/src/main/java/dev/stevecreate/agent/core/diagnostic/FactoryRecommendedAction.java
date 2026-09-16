package dev.stevecreate.agent.core.diagnostic;

/** Advice only. The diagnostic report never authorizes or invokes these actions. */
public enum FactoryRecommendedAction {
    RESTOCK_BOUND_SOURCES,
    FREE_OUTPUT_CAPACITY,
    INSPECT_APPROVED_ROUTE,
    RESTORE_POWER,
    REVALIDATE_STRUCTURE,
    RESELECT_MATERIAL_SOURCE
}
