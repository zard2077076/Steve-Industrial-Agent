package dev.stevecreate.agent.core.placement;

/** Stable fail-before-mutation reasons for the basic placement gate. */
public enum PlacementConflictKind {
    UNLOADED,
    NOT_REPLACEABLE,
    PROTECTED,
    INTERNAL_ROLE_CONFLICT
}
