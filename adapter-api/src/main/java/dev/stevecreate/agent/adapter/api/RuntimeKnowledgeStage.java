package dev.stevecreate.agent.adapter.api;

/** Bounded stage identity carried by every runtime-knowledge failure. */
public enum RuntimeKnowledgeStage {
    RUNTIME_VALIDATION,
    RECIPE_DISCOVERY,
    RECIPE_MAPPING,
    CATALOG_BUILD,
    RELOAD_VALIDATION,
    CAPABILITY_DISCOVERY,
    PLANNING,
    COMMAND
}
