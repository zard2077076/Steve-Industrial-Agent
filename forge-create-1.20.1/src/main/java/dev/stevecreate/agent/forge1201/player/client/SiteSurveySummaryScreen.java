package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.StartRelocationC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RequestConfirmationC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** One-page server-observed site summary and bounded relocation search launcher. */
@OnlyIn(Dist.CLIENT)
public final class SiteSurveySummaryScreen extends Screen {
    private final PreviewSnapshotS2C preview;
    private Button recommendation;
    private int progress;
    private int progressTotal;

    public SiteSurveySummaryScreen(PreviewSnapshotS2C preview) {
        super(Component.translatable("screen.steve_create_agent.site_survey"));
        this.preview = preview;
    }

    @Override
    protected void init() {
        int left = width / 2 - 120;
        addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.review_confirm"),
                ignored -> PlayerWorkflowNetwork.requestConfirmation(new RequestConfirmationC2S(
                        preview.projectId(), preview.projectNonce())))
                .bounds(left, height / 2 + 72, 160, 20).build());
        recommendation = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.find_recommendation"),
                ignored -> startRelocation())
                .bounds(left, height / 2 + 96, 160, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                ignored -> onClose()).bounds(left + 165, height / 2 + 96, 75, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 120;
        int y = height / 2 - 82;
        graphics.drawCenteredString(font, title, width / 2, y, 0xFFFFFF);
        y += 22;
        int sizeX = preview.maxX() - preview.minX() + 1;
        int sizeY = preview.maxY() - preview.minY() + 1;
        int sizeZ = preview.maxZ() - preview.minZ() + 1;
        line(graphics, left, y, "survey_footprint", sizeX + " × " + sizeY + " × " + sizeZ); y += 14;
        line(graphics, left, y, "survey_place", Integer.toString(preview.placeCount())); y += 14;
        line(graphics, left, y, "survey_reuse", Integer.toString(preview.reuseCount())); y += 14;
        line(graphics, left, y, "survey_clear", Integer.toString(preview.clearCount())); y += 14;
        line(graphics, left, y, "survey_protected", Integer.toString(preview.protectedCount())); y += 14;
        line(graphics, left, y, "survey_containers", Integer.toString(preview.containerCount())); y += 14;
        line(graphics, left, y, "survey_unknown", Integer.toString(preview.unknownCount())); y += 14;
        line(graphics, left, y, "survey_hazard", Integer.toString(preview.hazardCount())); y += 18;
        graphics.drawString(font,
                Component.translatable(preview.safeForConfirmation()
                        ? "screen.steve_create_agent.survey_safe"
                        : "screen.steve_create_agent.survey_needs_relocation"),
                left, y, preview.safeForConfirmation() ? 0x66FF99 : 0xFFCC66);
        if (progressTotal > 0) {
            graphics.drawString(font,
                    Component.translatable("screen.steve_create_agent.relocation_progress",
                            progress, progressTotal), left, y + 14, 0x80C8FF, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void startRelocation() {
        recommendation.active = false;
        progress = 0;
        progressTotal = 216;
        PlayerWorkflowNetwork.startRelocation(new StartRelocationC2S(
                preview.projectId(), preview.projectNonce()));
    }

    public void updateProgress(int completed, int total) {
        progress = Math.max(0, completed);
        progressTotal = Math.max(0, total);
    }

    private void line(GuiGraphics graphics, int x, int y, String labelKey, String value) {
        graphics.drawString(font, Component.translatable(
                "screen.steve_create_agent." + labelKey), x, y, 0xB8C8D8, false);
        graphics.drawString(font, value, x + 100, y, 0xFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
