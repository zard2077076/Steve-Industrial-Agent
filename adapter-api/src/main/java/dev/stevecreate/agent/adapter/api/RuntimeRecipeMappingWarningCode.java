package dev.stevecreate.agent.adapter.api;

/** Non-fatal runtime mapping facts that may affect later planning guarantees. */
public enum RuntimeRecipeMappingWarningCode {
    PROBABILISTIC_BYPRODUCT,
    GUARANTEED_SECONDARY_OUTPUT
}
