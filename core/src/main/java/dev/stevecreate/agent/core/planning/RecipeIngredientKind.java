package dev.stevecreate.agent.core.planning;

/** Stable loader-neutral ingredient forms supported by runtime recipe discovery. */
public enum RecipeIngredientKind {
    EXACT_RESOURCE,
    ANY_OF_RESOURCES,
    TAG_REFERENCE,
    UNSUPPORTED_COMPLEX_INGREDIENT
}
