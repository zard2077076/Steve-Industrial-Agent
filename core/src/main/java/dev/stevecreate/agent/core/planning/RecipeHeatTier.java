package dev.stevecreate.agent.core.planning;

/** Loader-neutral heat tier observed from one authoritative runtime recipe. */
public enum RecipeHeatTier {
    NONE,
    HEATED,
    SUPERHEATED,
    UNSUPPORTED_HEAT_REQUIREMENT
}
