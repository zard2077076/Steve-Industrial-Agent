package dev.stevecreate.agent.core.layout;

/** One observed cell in an explicit, bounded, read-only world snapshot. */
public enum LayoutCellState {
    REPLACEABLE,
    OCCUPIED,
    PROTECTED,
    UNLOADED
}
