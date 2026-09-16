package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingStatusS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Throttled packet-backed HUD. Counts are evidence, never a fabricated time estimate. */
@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class ConstructionProgressHud {
    private ConstructionProgressHud() {}

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClearingStatusS2C status = PlayerWorkflowClient.latestClearingStatus();
        if (status == null || !status.success() || status.project() == null
                || minecraft.player == null || minecraft.screen != null) return;
        String stage = status.project().stage();
        if (!("CLEARING".equals(stage) || "POST_CLEAR_RESCAN".equals(stage)
                || "CONSTRUCTION".equals(stage) || "PAUSED".equals(stage))) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int left = 8;
        int top = 8;
        int width = 214;
        int height = 58;
        graphics.fill(left, top, left + width, top + height, 0xA0101822);
        graphics.fill(left, top, left + 3, top + height,
                status.paused() ? 0xFFFFB347 : 0xFF4DB6FF);
        graphics.drawString(minecraft.font,
                Component.translatable("hud.steve_create_agent.project", status.project().target()),
                left + 9, top + 7, 0xFFFFFF, false);
        graphics.drawString(minecraft.font,
                Component.translatable("hud.steve_create_agent.stage", visibleStage(status)),
                left + 9, top + 19, status.paused() ? 0xFFFFCC66 : 0xFFB8D8F0, false);
        graphics.drawString(minecraft.font,
                Component.translatable("hud.steve_create_agent.bots", status.activeBots()),
                left + 9, top + 31, 0xFFC8D8E8, false);
        graphics.drawString(minecraft.font, evidenceLine(status),
                left + 9, top + 43, 0xFFA8B8C8, false);
    }

    private static Component visibleStage(ClearingStatusS2C status) {
        if (status.paused()) return Component.translatable(
                "hud.steve_create_agent.paused", status.statusCode());
        if ("CONSTRUCTION".equals(status.project().stage())) {
            return Component.translatable(
                    "hud.steve_create_agent.construction", status.statusCode());
        }
        return Component.literal(status.phase());
    }

    private static Component evidenceLine(ClearingStatusS2C status) {
        if (status.totalTargets() > 0) {
            return Component.translatable("hud.steve_create_agent.cleared",
                    status.completedTargets(), status.totalTargets(), status.salvageDelivered());
        }
        return Component.translatable("hud.steve_create_agent.mutations",
                status.mutations(), status.salvageDelivered());
    }
}
