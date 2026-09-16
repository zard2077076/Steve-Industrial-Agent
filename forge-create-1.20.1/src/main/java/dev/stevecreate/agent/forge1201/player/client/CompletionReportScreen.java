package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialSnapshotS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Terminal report rendered only from verified executor and balanced ledger evidence. */
@OnlyIn(Dist.CLIENT)
public final class CompletionReportScreen extends Screen {
    private final MaterialSnapshotS2C snapshot;

    public CompletionReportScreen(MaterialSnapshotS2C snapshot) {
        super(Component.translatable("screen.steve_create_agent.report_title"));
        this.snapshot = snapshot;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(width / 2 - 40, height / 2 + 76, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        var report = snapshot.report();
        int left = Math.max(10, width / 2 - 155);
        int right = Math.min(width - 10, width / 2 + 155);
        int top = Math.max(12, height / 2 - 108);
        graphics.fill(left, top, right, Math.min(height - 12, top + 190), 0xE9142030);
        graphics.fill(left, top, right, top + 2, 0xFF65D39A);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFF);
        graphics.drawString(font, Component.literal("服务器证据  ·  账本结算"), left + 16, top + 32, 0xA8C6DA);
        if (report != null) {
            line(graphics, left + 16, top + 50, "planned", report.planned());
            line(graphics, left + 16, top + 64, "withdrawn", report.withdrawn());
            line(graphics, left + 16, top + 78, "consumed", report.consumed());
            line(graphics, left + 16, top + 92, "returned", report.returned());
            line(graphics, left + 16, top + 106, "salvage", report.salvageTransferred());
            line(graphics, left + 16, top + 120, "output",
                    report.observedOutput() + "/" + report.expectedOutput());
            line(graphics, left + 16, top + 134, "difference", report.unaccountedItems());
            int color = report.balanced() && report.duplicateWithdrawals() == 0
                    && report.duplicateReturns() == 0 && report.privateItemsTouched() == 0
                    ? 0x80FFB0 : 0xFF6B6B;
            Component balance = Component.translatable(report.balanced()
                    ? "screen.steve_create_agent.report_yes"
                    : "screen.steve_create_agent.report_no");
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.report_integrity",
                    balance, report.privateItemsTouched()), left + 16, top + 153, color);
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.report_duplicates",
                    report.duplicateWithdrawals(), report.duplicateReturns()),
                    left + 16, top + 167, color);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void line(GuiGraphics graphics, int x, int y, String key, Object value) {
        graphics.drawString(font, Component.translatable(
                "screen.steve_create_agent.report_" + key, value), x, y, 0xC8D8E8);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
