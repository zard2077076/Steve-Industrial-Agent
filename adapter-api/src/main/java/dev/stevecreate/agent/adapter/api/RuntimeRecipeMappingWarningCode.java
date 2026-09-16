package dev.stevecreate.agent.adapter.api;

/** Non-fatal runtime mapping facts that may affect later planning guarantees. */
public enum RuntimeRecipeMappingWarningCode {
    PROBABILISTIC_BYPRODUCT,
    GUARANTEED_SECONDARY_OUTPUT,
    /**
     * One byproduct listed by several independent rolls, folded into a single line.
     *
     * <p>Create states a crushing byproduct as separate chances — one guaranteed, one at
     * three quarters, one at a quarter — and this model holds one line per resource. The
     * folded amount is what all of them landing would produce, which is an upper bound
     * rather than an expectation.
     *
     * <p>Recorded rather than assumed harmless: nothing downstream reads a byproduct's
     * declared amount today, because the executor counts what the world actually made,
     * and anything that starts reading it should know the number came from here.</p>
     */
    INDEPENDENT_ROLLS_FOLDED
}
