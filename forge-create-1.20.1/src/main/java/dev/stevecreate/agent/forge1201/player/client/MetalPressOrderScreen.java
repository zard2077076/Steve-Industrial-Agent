package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.MetalPressOrderNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressAction;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressStatusS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Responsive in-game order card backed only by bounded server evidence packets. */
@OnlyIn(Dist.CLIENT)
public final class MetalPressOrderScreen extends Screen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 268;
    private MetalPressStatusS2C status;
    private Button refresh;
    private Button cancel;
    private Button close;
    private int refreshTicks;
    private int cancelArmTicks;

    public MetalPressOrderScreen(MetalPressStatusS2C status) {
        super(Component.literal("Steve · IE Metal Press"));
        this.status = status;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int bottom = Math.min(height - 12, (height + Math.min(PANEL_HEIGHT, height - 24)) / 2);
        int buttonY = bottom - 29;
        refresh = addRenderableWidget(Button.builder(Component.literal("刷新"), ignored -> requestRefresh())
                .bounds(left + 14, buttonY, 82, 20).build());
        cancel = addRenderableWidget(Button.builder(Component.literal("安全取消"), ignored -> cancel())
                .bounds(left + panelWidth - 206, buttonY, 112, 20).build());
        close = addRenderableWidget(Button.builder(Component.literal("关闭"), ignored -> onClose())
                .bounds(left + panelWidth - 86, buttonY, 72, 20).build());
        refreshButtons();
    }

    public void update(MetalPressStatusS2C next) {
        status = next;
        refreshTicks = 0;
        refreshButtons();
    }

    @Override
    public void tick() {
        if (cancelArmTicks > 0 && --cancelArmTicks == 0 && cancel != null) {
            cancel.setMessage(Component.literal("安全取消"));
        }
        if (++refreshTicks >= 40 && status != null && status.success()
                && !"COMPLETED".equals(status.stage()) && !"CANCELLED".equals(status.stage())) {
            requestRefresh();
        }
    }

    private void requestRefresh() {
        refreshTicks = 0;
        if (refresh != null) refresh.active = false;
        MetalPressOrderNetwork.control(new MetalPressControlC2S(MetalPressAction.REFRESH));
    }

    private void cancel() {
        if (status == null || !status.success() || !status.safeToCancel()) return;
        if (cancelArmTicks <= 0) {
            cancelArmTicks = 80;
            cancel.setMessage(Component.literal("再次点击确认"));
            return;
        }
        cancelArmTicks = 0;
        cancel.active = false;
        MetalPressOrderNetwork.control(new MetalPressControlC2S(MetalPressAction.CANCEL));
    }

    private void refreshButtons() {
        if (refresh == null) return;
        refresh.active = true;
        cancel.active = status != null && status.success() && status.safeToCancel();
        if (!cancel.active) cancel.setMessage(Component.literal("不可取消"));
        else if (cancelArmTicks <= 0) cancel.setMessage(Component.literal("安全取消"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int right = left + panelWidth;
        int bottom = top + panelHeight;
        boolean compact = panelHeight < 250;
        graphics.fill(left, top, right, bottom, 0xEE111820);
        graphics.fill(left, top, right, top + 3, accent());
        graphics.fill(left + 1, top + 3, right - 1, top + 38, 0xFF182633);

        graphics.drawString(font, "IE METAL PRESS", left + 14, top + 11, 0xFF70C7E8, false);
        graphics.drawString(font, status != null && status.success()
                        ? "铁板生产订单 · #" + status.orderId().substring(0, 8)
                        : "铁板生产订单",
                left + 14, top + 24, 0xFFD7E4EC, false);
        graphics.drawString(font, status != null && status.success()
                        ? friendlyStage(status.stage()) : "尚无订单",
                right - 14 - font.width(status != null && status.success()
                        ? friendlyStage(status.stage()) : "尚无订单"),
                top + 17, status != null && status.paused() ? 0xFFFFB34D : 0xFFA9DCA9, false);

        if (status == null || !status.success()) {
            renderEmpty(graphics, left, top, right);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int contentLeft = left + 14;
        int contentRight = right - 14;
        int progressTop = top + (compact ? 39 : 52);
        graphics.drawString(font, status.progress() + "%", contentRight - font.width(status.progress() + "%"),
                progressTop - 1, 0xFFEAF4F8, false);
        graphics.drawString(font, stageHint(status.stage(), status.statusCode()), contentLeft,
                progressTop - 1, status.paused() ? 0xFFFFC36A : 0xFFC9D9E2, false);
        int barTop = progressTop + 12;
        graphics.fill(contentLeft, barTop, contentRight, barTop + 8, 0xFF25343F);
        int filled = Math.max(1, (contentRight - contentLeft) * status.progress() / 100);
        graphics.fill(contentLeft, barTop, contentLeft + filled, barTop + 8, accent());
        for (int marker : new int[] {20, 45, 65, 88}) {
            int x = contentLeft + (contentRight - contentLeft) * marker / 100;
            graphics.fill(x, barTop, x + 1, barTop + 8, 0x997A8B96);
        }

        int cardsTop = top + (compact ? 61 : 83);
        int gap = 7;
        int cardWidth = (contentRight - contentLeft - gap * 2) / 3;
        statCard(graphics, contentLeft, cardsTop, cardWidth, "材料账本",
                status.consumed() + " 消耗 · " + status.returned() + " 返还",
                status.withdrawn() + "/" + status.planned() + " 已取", 0xFF75C6A6);
        statCard(graphics, contentLeft + cardWidth + gap, cardsTop, cardWidth, "真实能耗",
                status.energyConsumed() + "/2400 FE",
                status.energyConsumed() == 2_400 ? "已精确结算" : "等待机器实测", 0xFF78B6E8);
        statCard(graphics, contentLeft + (cardWidth + gap) * 2, cardsTop, cardWidth, "唯一产出",
                status.outputCount() + "/1 铁板",
                status.outputCount() == 1 ? "唯一性已验证" : "尚未验证", 0xFFE6B96C);

        int infoTop = cardsTop + 57;
        graphics.drawString(font, "材料箱  " + coordinates(status.sourceX(), status.sourceY(), status.sourceZ()),
                contentLeft, infoTop, 0xFFAFC2CE, false);
        graphics.drawString(font, "施工点  " + coordinates(status.originX(), status.originY(), status.originZ()),
                contentLeft, infoTop + 13, 0xFFAFC2CE, false);
        String operationCode = status.operationCode();
        boolean hasOperation = !"STATUS_REFRESHED".equals(operationCode)
                && !"STATUS_OPENED".equals(operationCode);
        graphics.drawString(font, hasOperation ? operation(operationCode)
                        : readableStatus(status.statusCode()), contentLeft, infoTop + 29,
                status.paused() ? 0xFFFFB34D : 0xFF7F919D, false);

        if (status.reportPresent()) renderReport(graphics, contentLeft, contentRight, infoTop + 47);
        else renderNextStep(graphics, contentLeft, infoTop + 47);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderEmpty(GuiGraphics graphics, int left, int top, int right) {
        graphics.drawCenteredString(font, "还没有 IE Metal Press 订单", (left + right) / 2,
                top + 82, 0xFFE5EDF2);
        graphics.drawCenteredString(font, "1  手持工程终端，潜行右击材料箱", (left + right) / 2,
                top + 111, 0xFF9FC1D2);
        graphics.drawCenteredString(font, "2  潜行右击施工地面，Bot 自动开始", (left + right) / 2,
                top + 128, 0xFF9FC1D2);
        graphics.drawCenteredString(font, status == null ? "" : readableStatus(status.operationCode()),
                (left + right) / 2, top + 157, 0xFFFFB34D);
    }

    private void statCard(GuiGraphics graphics, int x, int y, int width, String label,
            String value, String foot, int color) {
        graphics.fill(x, y, x + width, y + 49, 0xFF1A2731);
        graphics.fill(x, y, x + 3, y + 49, color);
        graphics.drawString(font, label, x + 9, y + 7, 0xFF91A5B1, false);
        graphics.drawString(font, value, x + 9, y + 20, 0xFFF0F5F7, false);
        graphics.drawString(font, foot, x + 9, y + 34, 0xFF788A95, false);
    }

    private void renderReport(GuiGraphics graphics, int left, int right, int y) {
        int color = status.ledgerBalanced() && status.baselineRestored()
                && status.unaccountedItems() == 0 ? 0xFF83D39B : 0xFFFF8F73;
        graphics.fill(left, y - 4, right, y + 25, 0xFF15251F);
        graphics.drawString(font, "完成报告", left + 8, y + 2, color, false);
        String line = "差额 " + status.unaccountedItems() + " · 重复 取"
                + status.duplicateWithdrawals() + "/能" + status.duplicateEnergy()
                + "/产" + status.duplicateOutputs() + "/返" + status.duplicateReturns()
                + " · 私人物品 " + status.privateItemsTouched();
        graphics.drawString(font, line, left + 8, y + 14, 0xFFB7C9C0, false);
    }

    private void renderNextStep(GuiGraphics graphics, int x, int y) {
        graphics.drawString(font, "下一步  " + nextStep(status.stage()), x, y, 0xFF9BBAC9, false);
    }

    private int accent() {
        if (status != null && status.paused()) return 0xFFFFA640;
        if (status != null && "COMPLETED".equals(status.stage())) return 0xFF55C67A;
        return 0xFF3FAAD2;
    }

    private static String friendlyStage(String stage) {
        return switch (stage) {
            case "MATERIALS_RESERVED" -> "Bot 取料";
            case "MATERIALS_WITHDRAWN" -> "Bot 运输";
            case "MATERIALS_DELIVERED" -> "材料送达";
            case "STRUCTURE_BUILT" -> "结构完成";
            case "MULTIBLOCK_FORMED" -> "锤击成型";
            case "MOLD_INSTALLED" -> "模具安装";
            case "POWER_NETWORK_BUILT" -> "FE 电网搭建";
            case "POWER_VERIFIED" -> "机器通电";
            case "INPUT_QUEUED" -> "Bot 投料";
            case "PROCESSING" -> "压制中";
            case "ENERGY_SETTLED" -> "能耗结算";
            case "OUTPUT_OBSERVED" -> "产出验证";
            case "TEARDOWN" -> "自动拆除";
            case "BASELINE_RESTORED" -> "基线恢复";
            case "MATERIALS_RETURNED" -> "材料返还";
            case "REPORT_GENERATED" -> "生成报告";
            case "COMPLETED" -> "订单完成";
            case "PAUSED" -> "安全暂停";
            case "CANCELLED" -> "已取消";
            default -> "准备订单";
        };
    }

    private static String stageHint(String stage, String status) {
        return "PAUSED".equals(stage) ? "工程已暂停 · " + readableStatus(status)
                : friendlyStage(stage);
    }

    private static String nextStep(String stage) {
        return switch (stage) {
            case "MATERIALS_RESERVED", "MATERIALS_WITHDRAWN" -> "等待 Bot 完成精确取送";
            case "MATERIALS_DELIVERED" -> "搭建 7 方块 Metal Press 结构";
            case "STRUCTURE_BUILT" -> "使用工程师锤形成多方块";
            case "MULTIBLOCK_FORMED" -> "安装铁板模具";
            case "MOLD_INSTALLED", "POWER_NETWORK_BUILT" -> "建立并验证真实 FE 供电";
            case "POWER_VERIFIED", "INPUT_QUEUED" -> "投放一枚铁锭";
            case "PROCESSING", "ENERGY_SETTLED" -> "等待唯一铁板与 2400 FE 证据";
            case "OUTPUT_OBSERVED", "TEARDOWN" -> "拆除临时设施并恢复现场";
            case "BASELINE_RESTORED" -> "精确返还模具和剩余材料";
            case "MATERIALS_RETURNED", "REPORT_GENERATED" -> "生成并核对完成报告";
            case "PAUSED" -> "按暂停原因处理后重试或安全取消";
            default -> "无需操作";
        };
    }

    private static String readableStatus(String code) {
        if (code == null || code.isBlank()) return "";
        if (code.startsWith("RETURN_PENDING")) return "返还箱空间不足或材料箱已变化";
        if (code.startsWith("MATERIAL_COURIER")) return "材料箱或运输路径发生变化";
        if (code.startsWith("ORDER_EXCEPTION")) return "执行证据异常，已安全暂停";
        return switch (code) {
            case "ORDER_NOT_FOUND" -> "先潜行右击材料箱，再潜行右击施工地面";
            case "ORDER_COMPLETED" -> "所有证据已结算";
            case "ORDER_CANCELLED_AND_BASELINE_RESTORED" -> "订单已取消，现场已恢复";
            case "MATERIAL_RECONCILIATION_REQUIRED" -> "材料账本需要重新核对";
            case "WITHDRAWN_MATERIAL_RECOVERY_REQUIRED" -> "Bot 携带材料证据需要恢复";
            default -> code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String operation(String code) {
        return switch (code) {
            case "ORDER_CANCELLED" -> "✓ 已安全取消并返还未消耗材料";
            case "ORDER_COMMITTED_CANNOT_CANCEL_AFTER_INPUT" -> "投料后订单必须完成结算，不能取消";
            case "RETURN_PENDING_SOURCE_FULL_OR_CHANGED" -> "取消暂停：请腾出材料箱空间后重试";
            default -> readableStatus(code);
        };
    }

    private static String coordinates(int x, int y, int z) { return x + ", " + y + ", " + z; }

    @Override public boolean isPauseScreen() { return false; }
}
