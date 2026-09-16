package dev.stevecreate.agent.core.execution.construction;

/** Phase-I cancellation never discards or redirects carried material. */
public enum MaterialReturnPolicy {
    RETURN_TO_ORIGINAL_SOURCE,
    REFUSE_CANCELLATION_WHILE_CARRYING
}
