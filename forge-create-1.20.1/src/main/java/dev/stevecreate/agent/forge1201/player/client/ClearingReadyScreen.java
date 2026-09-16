package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.StartClearingC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Final short undo window before the shared Site Preparation executor starts. */
@OnlyIn(Dist.CLIENT)
public final class ClearingReadyScreen extends Screen {
    private final ProjectWire project;
    private Button start;
    private String status = "READY";

    public ClearingReadyScreen(ProjectWire project) {
        super(Component.translatable("screen.steve_create_agent.clearing_ready"));
        this.project = project;
    }

    @Override
    protected void init() {
        int left = width / 2 - 120;
        start = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.start_clearing"),
                ignored -> start()).bounds(left, height / 2 + 34, 160, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> onClose()).bounds(left + 165, height / 2 + 34, 75, 20).build());
    }

    private void start() {
        start.active = false;
        status = "STARTING";
        PlayerWorkflowNetwork.startClearing(new StartClearingC2S(
                project.projectId(), project.projectNonce()));
    }

    public void showFailure(String code) {
        status = code;
        start.active = true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 36, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("screen.steve_create_agent.clearing_ready_detail"),
                width / 2, height / 2 - 12, 0xB8C8D8);
        graphics.drawCenteredString(font, status, width / 2, height / 2 + 8, 0x80C8FF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
