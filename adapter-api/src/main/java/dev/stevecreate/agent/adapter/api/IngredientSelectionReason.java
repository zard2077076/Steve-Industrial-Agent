package dev.stevecreate.agent.adapter.api;

/** Stable reason for one request-specific runtime ingredient selection. */
public enum IngredientSelectionReason {
    EXACT_RESOURCE,
    OWNED_RESOURCE,
    CANONICAL_CANDIDATE
}
