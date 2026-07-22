package dev.stevecreate.agent.adapter.api;

/** Mekanism-specific extension point. Chemical, heat and side configuration APIs belong here. */
public interface MekanismVersionAdapter extends IndustrialModAdapter {
    @Override
    default String targetModId() {
        return "mekanism";
    }
}

