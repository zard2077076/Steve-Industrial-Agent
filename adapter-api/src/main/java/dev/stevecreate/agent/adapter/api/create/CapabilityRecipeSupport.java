package dev.stevecreate.agent.adapter.api.create;

/** Honest Phase-I support state; semantics-only rows cannot be submitted for execution. */
public enum CapabilityRecipeSupport {
    SUPPORTED_PHASE_I,
    SEMANTICS_ONLY,
    UNSUPPORTED
}
