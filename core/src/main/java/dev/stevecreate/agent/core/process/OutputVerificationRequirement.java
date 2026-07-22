package dev.stevecreate.agent.core.process;

/** How declared output quantities must be verified before process completion. */
public enum OutputVerificationRequirement {
    PRESENCE,
    AT_LEAST_DECLARED,
    EXACT_DECLARED
}
