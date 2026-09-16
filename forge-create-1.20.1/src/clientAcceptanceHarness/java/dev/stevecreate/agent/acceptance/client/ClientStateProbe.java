package dev.stevecreate.agent.acceptance.client;

import net.minecraft.client.Minecraft;

/** The minimum live state required to keep mutations inside the disposable world. */
final class ClientStateProbe {
    private ClientStateProbe() {}

    static String worldName(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() == null) return null;
        return minecraft.getSingleplayerServer().getWorldData().getLevelName();
    }

    static void requireAcceptanceWorld(Minecraft minecraft, String expectedWorld) {
        String actual = worldName(minecraft);
        if (!expectedWorld.equals(actual)) {
            throw new BridgeRefusal("ACCEPTANCE_WORLD_NOT_LOADED",
                    "expected=" + expectedWorld + ", actual=" + actual);
        }
    }
}
