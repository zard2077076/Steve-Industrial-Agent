package dev.stevecreate.agent.core.player;

/** Honest spatial policy used by preview, recommendation and physicalization. */
public enum LayoutVariant {
    COMPACT(0, false),
    STANDARD(16, false),
    EXPANDABLE(24, true);

    private final int fixedModuleSpacing;
    private final boolean reservesExpansionBay;

    LayoutVariant(int fixedModuleSpacing, boolean reservesExpansionBay) {
        this.fixedModuleSpacing = fixedModuleSpacing;
        this.reservesExpansionBay = reservesExpansionBay;
    }

    /** Zero means derive the smallest collision-free spacing from exact geometry. */
    public int fixedModuleSpacing() {
        return fixedModuleSpacing;
    }

    public boolean reservesExpansionBay() {
        return reservesExpansionBay;
    }
}
