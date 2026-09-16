package dev.stevecreate.agent.adapter.api.create;

/** Loader-neutral heat requirement read from one runtime recipe. */
public enum HeatRequirement {
    NONE,
    HEATED,
    SUPERHEATED,
    UNSUPPORTED_HEAT_REQUIREMENT
}
