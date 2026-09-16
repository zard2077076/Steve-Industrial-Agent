package dev.stevecreate.agent.forge1201.player.net;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeControlC2S;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeCompletionS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeStatusS2C;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Additive Composite status channel; the frozen player/material protocol is untouched. */
public final class CompositeOrderNetwork {
    public static final String PROTOCOL = "composite-order-v1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(
                    SteveIndustrialAgentMod.MOD_ID, "composite_order"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean initialized;

    private CompositeOrderNetwork() {}

    public static synchronized void register() {
        if (initialized) return;
        initialized = true;
        CHANNEL.messageBuilder(CompositeStatusS2C.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CompositeStatusS2C::encode).decoder(CompositeStatusS2C::decode)
                .consumerMainThread(CompositeStatusS2C::handle).add();
        CHANNEL.messageBuilder(CompositeControlC2S.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CompositeControlC2S::encode).decoder(CompositeControlC2S::decode)
                .consumerMainThread(CompositeControlC2S::handle).add();
        CHANNEL.messageBuilder(CompositeCompletionS2C.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CompositeCompletionS2C::encode).decoder(CompositeCompletionS2C::decode)
                .consumerMainThread(CompositeCompletionS2C::handle).add();
    }

    public static void openStatus(ServerPlayer player) {
        var status = CompositeStatusS2C.from(player, "STATUS_OPENED");
        if (status.success()) {
            sendStatus(player, status);
            return;
        }
        sendCompletion(player, CompositeCompletionS2C.from(player, "STATUS_OPENED"));
    }

    public static void control(CompositeControlC2S message) {
        CHANNEL.sendToServer(message);
    }

    static void sendStatus(ServerPlayer player, CompositeStatusS2C status) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), status);
    }

    /** Sends the durable terminal report; no executor handle is ever placed on wire. */
    public static void sendCompletion(ServerPlayer player, CompositeCompletionS2C completion) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), completion);
        } catch (RuntimeException ignored) {
            // Dev acceptance fixtures use FakePlayers without a network connection. A
            // missing client is not allowed to turn a committed server report into a
            // failed settlement; real players still receive the packet above.
        }
    }

    public static void sendFailure(ServerPlayer player, String code) {
        sendStatus(player, CompositeStatusS2C.failure(code));
    }
}
