package dev.stevecreate.agent.forge1201.player.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.command.PlayerPreviewService.PreviewCategory;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewCellWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewSnapshotS2C;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** macOS-safe line preview with category-specific nested outline patterns. */
@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class PlacementPreviewRenderer {
    private static final int RENDER_BUDGET = 512;

    private PlacementPreviewRenderer() {}

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || !PlacementController.active()) return;
        PreviewSnapshotS2C preview = PlacementController.preview();
        if (preview == null || !preview.success()) return;
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        pose.pushPose();
        pose.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
        int rendered = 0;
        for (PreviewCellWire cell : preview.cells()) {
            if (++rendered > RENDER_BUDGET) break;
            float[] color = color(cell.category());
            AABB box = new AABB(cell.x(), cell.y(), cell.z(),
                    cell.x() + 1.0D, cell.y() + 1.0D, cell.z() + 1.0D).inflate(0.002D);
            LevelRenderer.renderLineBox(pose, lines, box,
                    color[0], color[1], color[2], 0.92F);
            int pattern = pattern(cell.category());
            for (int index = 1; index < pattern; index++) {
                double inset = index * 0.055D;
                LevelRenderer.renderLineBox(pose, lines, box.inflate(-inset),
                        color[0], color[1], color[2], 0.72F);
            }
            if (cell.role().contains("input")) {
                LevelRenderer.renderLineBox(pose, lines, box.inflate(0.035D),
                        0.15F, 1.0F, 1.0F, 1.0F);
            } else if (cell.role().contains("output")) {
                LevelRenderer.renderLineBox(pose, lines, box.inflate(0.035D),
                        1.0F, 0.25F, 0.85F, 1.0F);
            }
        }
        if (PlacementController.anchor() != null) {
            var anchor = PlacementController.anchor();
            LevelRenderer.renderLineBox(pose, lines,
                    new AABB(anchor).inflate(0.12D), 1.0F, 1.0F, 1.0F, 1.0F);
            int dx = 0;
            int dz = 0;
            QuarterTurn turn = PlacementController.orientation();
            switch (turn) {
                case ZERO -> dx = 1;
                case CLOCKWISE_90 -> dz = 1;
                case CLOCKWISE_180 -> dx = -1;
                case CLOCKWISE_270 -> dz = -1;
            }
            LevelRenderer.renderLineBox(pose, lines,
                    new AABB(anchor.offset(dx, 0, dz)).inflate(-0.2D),
                    1.0F, 1.0F, 1.0F, 1.0F);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiOverlayEvent.Post event) {
        if (!PlacementController.active()) return;
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        int center = graphics.guiWidth() / 2;
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("hud.steve_create_agent.placement",
                        PlacementController.orientation().name(),
                        PlacementController.variant().name()),
                center, graphics.guiHeight() - 54, 0xFFFFFF);
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("hud.steve_create_agent.placement_controls"),
                center, graphics.guiHeight() - 42, 0xC8D8E8);
        PreviewSnapshotS2C preview = PlacementController.preview();
        if (preview != null) {
            String status = preview.success()
                    ? "place=" + preview.placeCount() + " clear=" + preview.clearCount()
                            + " protected=" + preview.protectedCount()
                            + " unknown=" + preview.unknownCount()
                    : preview.statusCode();
            graphics.drawCenteredString(minecraft.font, status,
                    center, graphics.guiHeight() - 30,
                    preview.success() && preview.safeForConfirmation() ? 0x66FF99 : 0xFFCC66);
        }
    }

    private static int pattern(PreviewCategory category) {
        return switch (category) {
            case PLACE -> 1;
            case CLEAR -> 2;
            case REUSE -> 3;
            case PROTECTED -> 4;
            case SHARED_INFRASTRUCTURE -> 2;
            case OUTSIDE_AUTHORITY -> 3;
            case UNKNOWN -> 4;
            case HAZARD -> 5;
        };
    }

    private static float[] color(PreviewCategory category) {
        return switch (category) {
            case PLACE -> new float[] {0.20F, 0.55F, 1.00F};
            case CLEAR -> new float[] {1.00F, 0.82F, 0.12F};
            case REUSE -> new float[] {0.20F, 0.95F, 0.40F};
            case PROTECTED -> new float[] {1.00F, 0.15F, 0.15F};
            case SHARED_INFRASTRUCTURE -> new float[] {0.72F, 0.30F, 1.00F};
            case OUTSIDE_AUTHORITY -> new float[] {0.55F, 0.55F, 0.55F};
            case UNKNOWN -> new float[] {1.00F, 0.46F, 0.12F};
            case HAZARD -> new float[] {1.00F, 0.05F, 0.55F};
        };
    }
}
