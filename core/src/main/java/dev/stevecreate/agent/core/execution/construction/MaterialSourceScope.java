package dev.stevecreate.agent.core.execution.construction;

/** Phase-I material authority deliberately excludes player and arbitrary private storage. */
public enum MaterialSourceScope {
    TEST_ONLY_BOUNDED_SOURCE,
    AUTHORIZED_DEDICATED_CONTAINER
}
