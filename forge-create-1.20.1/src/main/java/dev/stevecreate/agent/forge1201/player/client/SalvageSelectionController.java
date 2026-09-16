package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowItems;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.BindSalvageC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Client-only targeting aid; the server validates the exact container and authority. */
@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class SalvageSelectionController {
    /**
     * How long a bind may be outstanding before the player gets their clicks back.
     *
     * <p>Generous, because a slow answer is not a lost one and re-clicking a container
     * that is already being validated would ask twice. Ten seconds is far longer than a
     * local round trip and far shorter than giving up on the session.</p>
     */
    private static final int PENDING_TIMEOUT_TICKS = 200;

    private static ProjectWire project;
    private static boolean pending;
    private static int pendingTicks;

    private SalvageSelectionController() {}

    public static void activate(ProjectWire value) {
        project = value;
        pending = false;
        pendingTicks = 0;
    }

    public static void clear() {
        project = null;
        pending = false;
        pendingTicks = 0;
    }

    /**
     * Gives the player their clicks back after a bind the server refused.
     *
     * <p>The refusal path used to show a chat line and return, leaving {@code pending}
     * set — and {@code pending} is what makes {@link #interaction} swallow every
     * right-click. Because this is a HUD overlay rather than a screen, there was no
     * Escape to press either: one refused container ended the session. Observed as
     * SALVAGE_DESTINATION_UNAVAILABLE followed by a player who could no longer interact
     * with anything.
     *
     * <p>The project stays selected. The container was wrong, not the order, and the
     * next thing the player wants is to point at a different one.</p>
     */
    public static void retryable() {
        pending = false;
        pendingTicks = 0;
    }

    public static ProjectWire project() {
        return project;
    }

    /**
     * Acceptance-only semantic equivalent of pointing the terminal at a container and
     * right-clicking it.  The bridge calls this on the client thread; the same packet
     * and project nonce are used as the ordinary input event above.  It intentionally
     * returns false instead of exposing a production-facing error type so the
     * dev-only bridge can provide the refusal code without coupling production classes
     * to the harness source set.
     */
    public static boolean bindForAcceptance(BlockPos position) {
        Minecraft minecraft = Minecraft.getInstance();
        if (project == null || pending || position == null || minecraft.player == null
                || minecraft.level == null
                || !isTerminal(minecraft.player.getMainHandItem())
                || !(minecraft.level.getBlockEntity(position) instanceof Container)) {
            return false;
        }
        pending = true;
        pendingTicks = 0;
        PlayerWorkflowNetwork.bindSalvage(new BindSalvageC2S(project.projectId(),
                project.projectNonce(), position.getX(), position.getY(), position.getZ()));
        return true;
    }

    @SubscribeEvent
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (project == null || pending || minecraft.player == null || minecraft.screen != null
                || event.getHand() != InteractionHand.MAIN_HAND || !event.isUseItem()) return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;
        // Salvage selection is a terminal gesture, not a global right-click lock. If the
        // player is holding a chest/barrel, allow normal placement; if they are holding
        // the terminal but looking at the ground or air, allow the terminal to open. Only
        // a known client-side container target is captured as a bind attempt.
        if (!isTerminal(minecraft.player.getMainHandItem())
                || minecraft.level == null
                || !(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) return;
        BlockPos position = hit.getBlockPos();
        pending = true;
        pendingTicks = 0;
        PlayerWorkflowNetwork.bindSalvage(new BindSalvageC2S(project.projectId(),
                project.projectNonce(), position.getX(), position.getY(), position.getZ()));
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void keyboard(InputEvent.Key event) {
        if (project == null || event.getAction() != InputConstants.PRESS
                || event.getKey() != GLFW.GLFW_KEY_ESCAPE) return;
        // Escape pauses this client-side targeting mode. The server approval remains
        // intact, so reopening the terminal exposes the same project and lets the player
        // re-enter selection after placing a container.
        clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) minecraft.player.displayClientMessage(
                Component.translatable("hud.steve_create_agent.salvage_paused"), false);
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || project == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        // A reply that never comes must not cost the player their controls either. The
        // refusal path is handled where the refusal arrives; this is for the case where
        // nothing arrives at all, which no amount of correct refusal handling covers.
        if (pending && ++pendingTicks > PENDING_TIMEOUT_TICKS) {
            retryable();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("hud.steve_create_agent.salvage_timeout"), false);
            }
        }
    }

    @SubscribeEvent
    public static void overlay(RenderGuiOverlayEvent.Post event) {
        if (project == null || Minecraft.getInstance().screen != null) return;
        GuiGraphics graphics = event.getGuiGraphics();
        Component text = Component.translatable(pending
                ? "hud.steve_create_agent.salvage_pending"
                : "hud.steve_create_agent.salvage_select");
        int width = Minecraft.getInstance().font.width(text);
        graphics.drawString(Minecraft.getInstance().font, text,
                (graphics.guiWidth() - width) / 2, graphics.guiHeight() - 62,
                pending ? 0xFFCC66 : 0x80C8FF, true);
    }

    private static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && stack.is(PlayerWorkflowItems.ENGINEER_TERMINAL.get());
    }
}
