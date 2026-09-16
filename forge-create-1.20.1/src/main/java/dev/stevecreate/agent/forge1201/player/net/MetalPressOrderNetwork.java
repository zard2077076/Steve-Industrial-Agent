package dev.stevecreate.agent.forge1201.player.net;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressStatusS2C;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Additive IE-order channel that leaves the frozen Phase IV player/material wire untouched. */
public final class MetalPressOrderNetwork {
    public static final String PROTOCOL = "metal-press-order-v1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(
                    SteveIndustrialAgentMod.MOD_ID, "metal_press_order"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean initialized;

    private MetalPressOrderNetwork() {}

    public static synchronized void register() {
        if (initialized) return;
        initialized = true;
        CHANNEL.messageBuilder(MetalPressStatusS2C.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MetalPressStatusS2C::encode).decoder(MetalPressStatusS2C::decode)
                .consumerMainThread(MetalPressStatusS2C::handle).add();
        CHANNEL.messageBuilder(MetalPressControlC2S.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MetalPressControlC2S::encode).decoder(MetalPressControlC2S::decode)
                .consumerMainThread(MetalPressControlC2S::handle).add();
    }

    public static void openStatus(ServerPlayer player) {
        sendStatus(player, MetalPressStatusS2C.from(player, "STATUS_OPENED"));
    }

    public static void control(MetalPressControlC2S message) {
        CHANNEL.sendToServer(message);
    }

    static void sendStatus(ServerPlayer player, MetalPressStatusS2C status) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), status);
    }
}
