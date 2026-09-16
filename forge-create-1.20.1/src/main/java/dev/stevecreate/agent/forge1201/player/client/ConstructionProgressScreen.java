package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.command.PlayerClearingService;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Player-facing project controls over the existing server-authoritative clearing session. */
@OnlyIn(Dist.CLIENT)
public final class ConstructionProgressScreen extends Screen {
    private ClearingStatusS2C status;
    private Button pause;
    private Button resume;
    private Button cancel;

    public ConstructionProgressScreen(ClearingStatusS2C status) {
        super(Component.translatable("screen.steve_create_agent.progress"));
        this.status = status;
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        int y = height / 2 + 54;
        pause = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.pause"),
                ignored -> send(PlayerClearingService.Action.PAUSE))
                .bounds(left, y, 95, 20).build());
        resume = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.resume"),
                ignored -> send(PlayerClearingService.Action.RESUME))
                .bounds(left + 100, y, 95, 20).build());
        cancel = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.cancel_project"),
                ignored -> send(PlayerClearingService.Action.CANCEL))
                .bounds(left + 200, y, 110, 20).build());
        refreshButtons();
    }

    public void update(ClearingStatusS2C next) {
        status = next;
        refreshButtons();
    }

    private void refreshButtons() {
        if (pause == null) return;
        boolean clearing = status != null && status.project() != null
                && "CLEARING".equals(status.project().stage()) && status.active();
        boolean paused = status != null && status.project() != null
                && "PAUSED".equals(status.project().stage()) && status.active();
        pause.active = clearing && !status.paused();
        resume.active = paused && status.paused();
        boolean construction = status != null && status.project() != null
                && "CONSTRUCTION".equals(status.project().stage());
        // Cancel asks the server and takes its answer.
        //
        // This has now been narrowed twice. First it listed the pause reasons allowed to
        // cancel; I replaced that with "the project is PAUSED" and wrote in the comment
        // that a whitelist cannot help but go stale — then a project resting in
        // MATERIAL_SOURCE_SELECTION matched neither, and the button was grey again on a
        // project with no way forward.
        //
        // safeToCancel comes from the server, which knows what is in flight and says no
        // while anything is. There is nothing left for this screen to add: every extra
        // condition here can only refuse what the server has already allowed.
        cancel.active = status != null && status.safeToCancel();
    }

    private void send(PlayerClearingService.Action action) {
        ProjectWire project = status == null ? null : status.project();
        if (project == null) return;
        pause.active = false;
        resume.active = false;
        cancel.active = false;
        // Cancelling a project is not the same as cancelling a clearing session, and this
        // sent the latter for both. Once clearing had finished the session was gone, so
        // the button that says "cancel the project" answered CLEARING_NOT_ACTIVE and the
        // project stayed — with no other way to be rid of it, and no new project possible
        // while it existed.
        if (action == PlayerClearingService.Action.CANCEL && !status.active()) {
            PlayerWorkflowNetwork.cancelPreview(new PlayerWorkflowPackets.CancelPreviewC2S(
                    project.projectId(), project.projectNonce()));
            return;
        }
        PlayerWorkflowNetwork.controlClearing(new ClearingControlC2S(
                project.projectId(), project.projectNonce(), action));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 82, 0xFFFFFF);
        if (status != null && status.project() != null) {
            int left = width / 2 - 150;
            int y = height / 2 - 54;
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.progress_goal", status.project().target()),
                    left, y, 0xFFFFFF);
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.progress_stage", status.project().stage()),
                    left, y + 14, 0xB8D8F0);
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.progress_state", status.statusCode()), left, y + 28,
                    status.paused() ? 0xFFCC66 : 0xA8D8A8);
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.progress_bots", status.activeBots()),
                    left, y + 42, 0xC8D8E8);
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.progress_cleared", status.completedTargets(),
                    status.totalTargets(), status.salvageDelivered()),
                    left, y + 56, 0xC8D8E8);
            if (status.statusCode().contains("RECOVERY_")) {
                graphics.drawString(font,
                        Component.translatable("screen.steve_create_agent.recovery_reapproval"),
                        left, y + 74, 0xFF9B6A);
            } else if ("CONSTRUCTION".equals(status.project().stage())) {
                graphics.drawString(font,
                        Component.translatable("screen.steve_create_agent.construction_running"),
                        left, y + 74, 0xFFCC66);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
