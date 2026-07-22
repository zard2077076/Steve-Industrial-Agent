package dev.stevecreate.agent.core.deployment;

/** Fail-closed classification used before any deployment or execution authority is considered. */
public enum WorldEnvironmentType {
    ISOLATED_TEST_WORLD,
    ISOLATED_PACK_WORLD,
    DEVELOPMENT_WORLD,
    FORMAL_PLAYER_WORLD,
    UNKNOWN_WORLD,
    FORBIDDEN_WORLD
}
