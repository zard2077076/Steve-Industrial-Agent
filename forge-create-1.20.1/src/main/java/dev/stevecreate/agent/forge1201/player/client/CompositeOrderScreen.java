package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.CompositeOrderNetwork;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeAction;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeControlC2S;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.NodeWire;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Readable, packet-backed Composite progress card. It exposes the graph cursor and exact
 * intermediate buffers without pretending that a status screen owns execution authority.
 */
@OnlyIn(Dist.CLIENT)
public final class CompositeOrderScreen extends Screen {
    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 330;
    private CompositeStatusS2C status;
    private Button refresh;
    private Button cancel;
    private Button close;
    private int refreshTicks;
    private int cancelArmTicks;

    public CompositeOrderScreen(CompositeStatusS2C status) {
        super(Component.literal("Steve · Composite 工程"));
        this.status = status;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int left = (width - panelWidth) / 2;
        int bottom = Math.min(height - 12, (height + Math.min(PANEL_HEIGHT, height - 24)) / 2);
        int buttonY = bottom - 29;
        refresh = addRenderableWidget(Button.builder(Component.literal("刷新"), ignored -> refresh())
                .bounds(left + 14, buttonY, 82, 20).build());
        cancel = addRenderableWidget(Button.builder(Component.literal("安全取消"), ignored -> cancel())
                .bounds(left + panelWidth - 206, buttonY, 112, 20).build());
        close = addRenderableWidget(Button.builder(Component.literal("关闭"), ignored -> onClose())
                .bounds(left + panelWidth - 86, buttonY, 72, 20).build());
        refreshButtons();
    }

    public void update(CompositeStatusS2C next) {
        status = next;
        refreshTicks = 0;
        cancelArmTicks = 0;
        refreshButtons();
    }

    @Override
    public void tick() {
        if (cancelArmTicks > 0 && --cancelArmTicks == 0 && cancel != null) {
            cancel.setMessage(Component.literal("安全取消"));
        }
        if (++refreshTicks >= 40 && status != null && status.success()) refresh();
    }

    private void refresh() {
        refreshTicks = 0;
        if (refresh != null) refresh.active = false;
        CompositeOrderNetwork.control(new CompositeControlC2S(CompositeAction.REFRESH));
    }

    private void cancel() {
        if (status == null || !status.success()) return;
        if (cancelArmTicks <= 0) {
            cancelArmTicks = 80;
            cancel.setMessage(Component.literal("再次点击确认"));
            return;
        }
        cancelArmTicks = 0;
        cancel.active = false;
        CompositeOrderNetwork.control(new CompositeControlC2S(CompositeAction.CANCEL));
    }

    private void refreshButtons() {
        if (refresh == null) return;
        refresh.active = true;
        cancel.active = status != null && status.success();
        if (!cancel.active) cancel.setMessage(Component.literal("不可取消"));
        else if (cancelArmTicks <= 0) cancel.setMessage(Component.literal("安全取消"));
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
        graphics.fill(left + 1, top + 3, right - 1, top + 42, 0xFF182633);
        graphics.drawString(font, "COMPOSITE 工程", left + 14, top + 11, 0xFF70C7E8, false);
        graphics.drawString(font, status != null && status.success()
                        ? "图 " + shortId(status.graphId()) + " · #" + shortId(status.projectId())
                        : "Composite 工程状态",
                left + 14, top + 25, 0xFFD7E4EC, false);
        graphics.drawString(font, status != null && status.success()
                        ? "generation " + status.generation() : readable(status == null
                                ? "" : status.operationCode()),
                right - 14 - font.width(status != null && status.success()
                        ? "generation " + status.generation() : readable(status == null
                                ? "" : status.operationCode())),
                top + 18, status != null && status.success() ? 0xFFA9DCA9 : 0xFFFFB34D, false);

        if (status == null || !status.success()) {
            graphics.drawCenteredString(font, readable(status == null ? "NO_ACTIVE_COMPOSITE_ORDER"
                    : status.operationCode()), (left + right) / 2, top + 100, 0xFFFFC36A);
            graphics.drawCenteredString(font, "没有可展示的活动 Composite 订单", (left + right) / 2,
                    top + 120, 0xFF9FC1D2);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int contentLeft = left + 14;
        int contentRight = right - 14;
        int y = top + 55;
        graphics.drawString(font, "节点进度", contentLeft, y, 0xFF9FC1D2, false);
        int rowY = y + 16;
        int maxRows = Math.min(status.nodes().size(), 9);
        for (int index = 0; index < maxRows; index++) {
            NodeWire node = status.nodes().get(index);
            int rowTop = rowY + index * 20;
            graphics.fill(contentLeft, rowTop - 3, contentRight, rowTop + 14, 0xFF1A2731);
            graphics.fill(contentLeft, rowTop - 3, contentLeft + 3, rowTop + 14,
                    nodeColor(node.status()));
            graphics.drawString(font, node.nodeId(), contentLeft + 9, rowTop,
                    0xFFD7E4EC, false);
            String state = readable(node.status());
            graphics.drawString(font, state, contentRight - 9 - font.width(state), rowTop,
                    nodeColor(node.status()), false);
        }
        if (status.nodes().size() > maxRows) {
            graphics.drawString(font, "… 其余 " + (status.nodes().size() - maxRows) + " 个节点已在服务器快照中保留",
                    contentLeft, rowY + maxRows * 20 + 2, 0xFF788A95, false);
        }

        int bufferX = contentLeft + (contentRight - contentLeft) / 2 + 8;
        graphics.drawString(font, "中间缓存", bufferX, y, 0xFF9FC1D2, false);
        int bufferY = y + 16;
        int shownBuffers = Math.min(status.buffers().size(), 9);
        for (int index = 0; index < shownBuffers; index++) {
            var buffer = status.buffers().get(index);
            String line = shortId(buffer.resource()) + " ×" + buffer.quantity();
            graphics.drawString(font, line, bufferX, bufferY + index * 20, 0xFFC9D9E2, false);
        }
        graphics.drawString(font, "指纹 " + status.graphFingerprint().substring(0,
                        Math.min(12, status.graphFingerprint().length())) + "…",
                contentLeft, bottom - 54, 0xFF788A95, false);
        graphics.drawString(font, readable(status.operationCode()), contentLeft, bottom - 40,
                0xFFFFC36A, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int accent() {
        if (status == null || !status.success()) return 0xFFFFA640;
        return status.nodes().stream().anyMatch(node -> "FAILED".equals(node.status()))
                ? 0xFFFF8066 : 0xFF3FAAD2;
    }

    private static int nodeColor(String value) {
        return switch (value) {
            case "SUCCEEDED" -> 0xFF83D39B;
            case "RUNNING" -> 0xFF78B6E8;
            case "FAILED", "RECOVERY_REQUIRED" -> 0xFFFF8F73;
            case "CANCELLED" -> 0xFFFFC36A;
            default -> 0xFF9AAAB5;
        };
    }

    private static String shortId(String value) {
        return value == null ? "" : value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String readable(String value) {
        if (value == null || value.isBlank()) return "";
        return switch (value) {
            case "WAITING" -> "等待输入";
            case "RUNNING" -> "执行中";
            case "SUCCEEDED" -> "已完成";
            case "RECOVERY_REQUIRED" -> "等待恢复";
            case "FAILED" -> "失败暂停";
            case "CANCELLED" -> "已取消";
            case "STATUS_REFRESHED" -> "已从服务器刷新";
            case "NO_ACTIVE_COMPOSITE_ORDER" -> "没有活动 Composite 订单";
            default -> value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        };
    }

    @Override public boolean isPauseScreen() { return false; }
}
