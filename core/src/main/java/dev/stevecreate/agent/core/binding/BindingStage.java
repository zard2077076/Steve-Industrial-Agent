package dev.stevecreate.agent.core.binding;

/** Stable stage attribution for a typed binding failure. */
public enum BindingStage {
    CATALOG_VALIDATION,
    CANDIDATE_GENERATION,
    IMPLEMENTATION_SELECTION,
    GRAPH_CONSTRUCTION,
    VERIFICATION,
    COMMAND,
    LAYOUT_BOUNDARY,
    THREAD_GUARD
}
