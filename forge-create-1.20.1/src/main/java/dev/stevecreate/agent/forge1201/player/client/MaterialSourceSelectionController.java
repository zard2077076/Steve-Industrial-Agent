package dev.stevecreate.agent.forge1201.player.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.BindMaterialSourceC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client targeting aid only; source contents and authority are always rescanned by the server. */
@Mod.EventBusSubscriber(modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MaterialSourceSelectionController {
    private static ProjectWire project;
    private static boolean pending;
    private static BlockPos selected;

    private MaterialSourceSelectionController() {}

    public static void activate(ProjectWire value) { project = value; pending = false; }
    public static void reject() { pending = false; selected = null; }
    public static void clear() { project = null; pending = false; selected = null; }

    /**
     * Acceptance-only semantic equivalent of the normal block-target gesture.  It
     * deliberately sends the same server packet as the event handler; the server still
     * rescans the container, ownership and slot state before accepting the bind.
     */
    public static boolean bindForAcceptance(BlockPos position, net.minecraft.core.Direction face) {
        Minecraft minecraft = Minecraft.getInstance();
        if (project == null || pending || position == null || face == null
                || minecraft.player == null || minecraft.level == null) return false;
        selected = position.immutable();
        pending = true;
        PlayerWorkflowNetwork.bindMaterialSource(new BindMaterialSourceC2S(
                project.projectId(), project.projectNonce(), selected.getX(), selected.getY(),
                selected.getZ(), face));
        return true;
    }

    @SubscribeEvent
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (project == null || pending || minecraft.screen != null
                || event.getHand() != InteractionHand.MAIN_HAND || !event.isUseItem()) return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;
        selected = hit.getBlockPos().immutable();
        pending = true;
        PlayerWorkflowNetwork.bindMaterialSource(new BindMaterialSourceC2S(
                project.projectId(), project.projectNonce(), selected.getX(), selected.getY(),
                selected.getZ(), hit.getDirection()));
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || project == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) clear();
    }

    @SubscribeEvent
    public static void renderSelection(RenderLevelStageEvent event) {
        if (project == null || selected == null
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        pose.pushPose();
        pose.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
        AABB box = new AABB(selected).inflate(pending ? 0.025D : 0.01D);
        LevelRenderer.renderLineBox(pose, lines, box,
                pending ? 1.0F : 0.2F, pending ? 0.75F : 1.0F, 0.25F, 1.0F);
        LevelRenderer.renderLineBox(pose, lines, box.inflate(0.035D),
                pending ? 1.0F : 0.35F, 1.0F, 0.55F, 0.75F);
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void overlay(RenderGuiOverlayEvent.Post event) {
        if (project == null || Minecraft.getInstance().screen != null) return;
        GuiGraphics graphics = event.getGuiGraphics();
        Component text = Component.translatable(pending
                ? "hud.steve_create_agent.material_pending"
                : "hud.steve_create_agent.material_select");
        if (selected != null) text = Component.literal(text.getString() + "  "
                + selected.getX() + "," + selected.getY() + "," + selected.getZ());
        int width = Minecraft.getInstance().font.width(text);
        graphics.drawString(Minecraft.getInstance().font, text,
                (graphics.guiWidth() - width) / 2, graphics.guiHeight() - 62,
                pending ? 0xFFCC66 : 0x80FFB0, true);
    }
}
