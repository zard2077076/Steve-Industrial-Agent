package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.OpenTerminalS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class EngineerTerminalScreen extends Screen {
    private final OpenTerminalS2C snapshot;

    public EngineerTerminalScreen(OpenTerminalS2C snapshot) {
        super(Component.translatable("screen.steve_create_agent.terminal"));
        this.snapshot = snapshot;
    }

    /**
     * The top of the panel, which both the text and the buttons hang from.
     *
     * <p>They used to hang from different things — the panel from height/2 - 102, the
     * buttons from height/2 - 65 — so the order line landed inside the first button
     * whenever a project existed. Not a small-window problem: the two origins are a fixed
     * distance apart, so it overlapped always.</p>
     */
    /** Four buttons at 25px pitch, the last one 20 tall, plus a margin under it. */
    private static final int BUTTON_BLOCK_HEIGHT = 3 * 25 + 20 + 12;
    /** Title and the four lines of project detail above the buttons. */
    private static final int HEADER_HEIGHT = 100;
    private static final int PANEL_HEIGHT = HEADER_HEIGHT + BUTTON_BLOCK_HEIGHT;

    /**
     * The top of the panel, which both the text and the buttons hang from.
     *
     * <p>They used to hang from different things — the panel from height/2 - 102, the
     * buttons from height/2 - 65 — so the order line landed inside the first button
     * whenever a project existed. Not a small-window problem: the two origins are a fixed
     * distance apart, so it overlapped always.</p>
     *
     * <p>Pushed up off the bottom edge in a short window, because the panel is as tall as
     * what it holds: giving the buttons their own origin without growing the panel to
     * match put the last one outside it.</p>
     */
    private int panelTop() {
        return Math.max(12, Math.min(height / 2 - PANEL_HEIGHT / 2, height - 12 - PANEL_HEIGHT));
    }

    /** Below the four lines of project detail, whether or not there is a project to show. */
    private int buttonTop() {
        return panelTop() + HEADER_HEIGHT;
    }

    @Override
    protected void init() {
        int left = width / 2 - 110;
        int top = buttonTop();
        Button newLine = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.new_line"),
                ignored -> minecraft.setScreen(new GoalPickerScreen(this, snapshot)))
                .bounds(left, top, 220, 20).build());
        newLine.active = snapshot.constructionAuthorized()
                && (snapshot.project() == null
                || "COMPLETED".equals(snapshot.project().stage())
                || "CANCELLED".equals(snapshot.project().stage())
                || "REFUSED".equals(snapshot.project().stage()));
        Button continueProject = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.continue"), ignored ->
                        PlayerWorkflowClient.continueProject(snapshot.project()))
                .bounds(left, top + 25, 220, 20).build());
        continueProject.active = snapshot.project() != null
                && !"COMPLETED".equals(snapshot.project().stage())
                && !"CANCELLED".equals(snapshot.project().stage())
                && !"REFUSED".equals(snapshot.project().stage());
        Button manage = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.manage"), ignored ->
                        PlayerWorkflowClient.manageProject(snapshot.project()))
                .bounds(left, top + 50, 220, 20).build());
        // Any project that has not finished can be managed, because managing is where the
        // cancel button lives.
        //
        // This named four stages, and a project sitting in MATERIAL_SOURCE_SELECTION
        // matched none of them — so a player whose site preparation had been lost to a
        // restart could neither continue (the prepared site is gone) nor reach the screen
        // that would let them give up. Fifth time in two days that an exit existed only
        // on the paths someone had thought of.
        manage.active = snapshot.project() != null
                && !"COMPLETED".equals(snapshot.project().stage())
                && !"CANCELLED".equals(snapshot.project().stage())
                && !"REFUSED".equals(snapshot.project().stage());
        Button remove = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.remove_owned"), ignored -> {})
                .bounds(left, top + 75, 220, 20).build());
        remove.active = false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = Math.max(10, width / 2 - 125);
        int right = Math.min(width - 10, width / 2 + 125);
        int top = panelTop();
        graphics.fill(left, top, right, Math.min(height - 4, top + PANEL_HEIGHT), 0xE9142030);
        graphics.fill(left, top, right, top + 2, 0xFF4DB6E8);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFF);
        if (snapshot.project() != null) {
            var project = snapshot.project();
            graphics.drawString(font, Component.literal("当前订单"), left + 14, top + 34, 0x8EB6D3);
            graphics.drawString(font, Component.literal(project.target().toString() + "  ×" + project.quantity()),
                    left + 14, top + 49, 0xFFFFFF);
            graphics.drawString(font, Component.literal("阶段  " + project.stage()),
                    left + 14, top + 64, 0xB8D8F0);
            graphics.drawString(font, Component.literal("模式  " + project.executionMode()),
                    left + 14, top + 79, 0xB8D8F0);
        }
        if (!snapshot.constructionAuthorized()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.steve_create_agent.world_not_authorized"),
                    width / 2, top + 118, 0xFF6B6B);
        } else if (snapshot.project() == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.steve_create_agent.no_project"),
                    width / 2, top + 78, 0xA0A0A0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
