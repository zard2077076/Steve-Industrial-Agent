package dev.stevecreate.agent.core.safety;

/** Coarse execution risk used before a detailed plan is allowed to reach world mutation. */
public enum SafetyCategory {
    READ_ONLY,
    ROUTINE_WORLD_MUTATION,
    NUCLEAR_OR_RADIATION
}

