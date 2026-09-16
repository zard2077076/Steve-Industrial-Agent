package dev.stevecreate.agent.acceptance.client;

import net.minecraftforge.fml.common.Mod;

/**
 * A second, dev-only mod. This source set is never part of Steve Agent's production JAR.
 */
@Mod(SteveClientAcceptanceBridgeMod.MOD_ID)
public final class SteveClientAcceptanceBridgeMod {
    static final String MOD_ID = "steve_acceptance_bridge";

    public SteveClientAcceptanceBridgeMod() {
        ClientAcceptanceBridge.startFromProperties();
    }
}
