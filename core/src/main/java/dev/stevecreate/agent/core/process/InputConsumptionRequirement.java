package dev.stevecreate.agent.core.process;

/** How declared input quantities must be proven consumed by a completed process. */
public enum InputConsumptionRequirement {
    NONE,
    AT_LEAST_DECLARED,
    EXACT_DECLARED
}
