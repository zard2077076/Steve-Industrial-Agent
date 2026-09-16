package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompletionRowWire;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeCompletionS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Read-only terminal report for a Composite order.  The values are the server's
 * persisted settlement, not a client-side estimate, so this screen deliberately has
 * no refresh or execution controls.  Closing it cannot mutate the order or its ledger.
 */
@OnlyIn(Dist.CLIENT)
public final class CompositeCompletionScreen extends Screen {
    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_HEIGHT = 340;
    private CompositeCompletionS2C report;
    private Button close;
    private int scroll;

    public CompositeCompletionScreen(CompositeCompletionS2C report) {
        super(Component.literal("Steve · Composite 完成报告"));
        this.report = report;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int left = (width - panelWidth) / 2;
        int bottom = Math.min(height - 12, (height + Math.min(PANEL_HEIGHT, height - 24)) / 2);
        close = addRenderableWidget(Button.builder(Component.literal("关闭报告"), ignored -> onClose())
                .bounds(left + panelWidth - 104, bottom - 29, 90, 20).build());
    }

    public void update(CompositeCompletionS2C next) {
        report = next;
        scroll = Math.min(scroll, maximumScroll(
                next == null ? 0 : next.rows().size(), visibleRows()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int right = left + panelWidth;
        int bottom = top + panelHeight;
        graphics.fill(left, top, right, bottom, 0xEE111820);
        graphics.fill(left, top, right, top + 3, accent());
        graphics.fill(left + 1, top + 3, right - 1, top + 44, 0xFF182633);
        graphics.drawString(font, "COMPOSITE 完成报告", left + 14, top + 11, 0xFF83D39B, false);
        graphics.drawString(font, report != null && report.success()
                        ? shortId(report.projectId()) + " · " + shortId(report.orderType())
                        : "Composite 结算状态",
                left + 14, top + 26, 0xFFD7E4EC, false);

        if (report == null || !report.success()) {
            graphics.drawCenteredString(font, readable(report == null ? "COMPOSITE_COMPLETION_FAILED"
                    : report.operationCode()), (left + right) / 2, top + 110, 0xFFFFC36A);
            graphics.drawCenteredString(font, "服务器没有返回可验证的完成报告",
                    (left + right) / 2, top + 130, 0xFF9FC1D2);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int x = left + 14;
        int y = top + 58;
        graphics.drawString(font, "目标", x, y, 0xFF9FC1D2, false);
        graphics.drawString(font, report.target(), x + 52, y, 0xFFD7E4EC, false);
        graphics.drawString(font, "状态", x, y + 17, 0xFF9FC1D2, false);
        graphics.drawString(font, "已结算 · generation " + report.generation(), x + 52, y + 17,
                0xFF83D39B, false);

        int rightX = left + panelWidth / 2 + 8;
        graphics.drawString(font, "账本", rightX, y, 0xFF9FC1D2, false);
        graphics.drawString(font, report.materialLedgerBalanced() ? "平衡" : "需核对",
                rightX + 45, y, report.materialLedgerBalanced() ? 0xFF83D39B : 0xFFFF8F73, false);
        graphics.drawString(font, "基线", rightX, y + 17, 0xFF9FC1D2, false);
        graphics.drawString(font, report.baselineRestored() ? "已恢复" : "未恢复",
                rightX + 45, y + 17, report.baselineRestored() ? 0xFF83D39B : 0xFFFF8F73, false);

        int tableTop = top + 102;
        graphics.drawString(font, "材料结算（计划 / 取出 / 消耗 / 返还 / 产出）",
                x, tableTop, 0xFF9FC1D2, false);
        int visibleRows = visibleRowsForPanelHeight(panelHeight);
        scroll = Math.min(scroll, maximumScroll(report.rows().size(), visibleRows));
        int shown = Math.min(report.rows().size() - scroll, visibleRows);
        if (report.rows().size() > visibleRows) {
            String page = (scroll + 1) + "–" + (scroll + shown) + " / "
                    + report.rows().size() + " · 滚轮";
            graphics.drawString(font, page, right - 14 - font.width(page), tableTop,
                    0xFF788A95, false);
        }
        for (int index = 0; index < shown; index++) {
            CompletionRowWire row = report.rows().get(scroll + index);
            int rowY = tableTop + 19 + index * 21;
            graphics.fill(x, rowY - 3, right - 14, rowY + 14, 0xFF1A2731);
            graphics.drawString(font, shortId(row.resource()), x + 8, rowY, 0xFFD7E4EC, false);
            String values = row.planned() + " / " + row.withdrawn() + " / " + row.consumed()
                    + " / " + row.returned() + " / " + row.output();
            graphics.drawString(font, values, right - 14 - font.width(values), rowY, 0xFFC9D9E2, false);
        }
        int footerY = bottom - 55;
        graphics.drawString(font, "回收 " + report.salvageTransferred()
                        + " · 重复取料 " + report.duplicateWithdrawals()
                        + " · 重复返还 " + report.duplicateReturns(),
                x, footerY, 0xFF9FC1D2, false);
        graphics.drawString(font, "重复耗电 " + report.duplicateEnergySettlements()
                        + " · 重复产出 " + report.duplicateOutputs()
                        + " · 未记账 " + report.unaccountedItems()
                        + " · 私人物品 " + report.privateItemsTouched(),
                x, footerY + 16, 0xFF9FC1D2, false);
        graphics.drawString(font, "证据 " + shortId(report.evidenceHash()) + "…",
                x, footerY + 32, 0xFF788A95, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visibleRows = visibleRows();
        if (report == null || report.rows().size() <= visibleRows) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        scroll = Math.max(0, Math.min(maximumScroll(report.rows().size(), visibleRows),
                scroll + (delta < 0 ? 1 : -1)));
        return true;
    }

    private int visibleRows() {
        return visibleRowsForPanelHeight(Math.min(PANEL_HEIGHT, height - 20));
    }

    static int visibleRowsForPanelHeight(int panelHeight) {
        int firstRowY = 121;
        int footerY = panelHeight - 55;
        return Math.max(1, 1 + (footerY - 18 - firstRowY) / 21);
    }

    static int maximumScroll(int rowCount, int visibleRows) {
        return Math.max(0, rowCount - visibleRows);
    }

    private int accent() {
        return report != null && report.success() && report.materialLedgerBalanced()
                && report.baselineRestored() ? 0xFF3FAAD2 : 0xFFFFA640;
    }

    private static String shortId(String value) {
        return value == null ? "" : value.length() <= 18 ? value : value.substring(0, 18);
    }

    private static String readable(String value) {
        if (value == null || value.isBlank()) return "";
        return switch (value) {
            case "NO_COMPOSITE_COMPLETION_REPORT" -> "没有 Composite 完成报告";
            case "COMPOSITE_COMPLETION_REPORT_TOO_LARGE" -> "完成报告超出客户端显示上限";
            default -> value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        };
    }

    @Override public boolean isPauseScreen() { return false; }
}
