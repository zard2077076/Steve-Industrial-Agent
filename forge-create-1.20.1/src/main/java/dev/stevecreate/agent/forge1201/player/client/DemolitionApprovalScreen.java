package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ApprovalResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ApproveProjectC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ConfirmationSummaryS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ReselectPlacementC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Single explicit review surface; only the server can issue the bound approval token. */
@OnlyIn(Dist.CLIENT)
public final class DemolitionApprovalScreen extends Screen {
    private final ConfirmationSummaryS2C summary;
    private Button approve;
    private Button reselect;
    private Button salvage;
    private String statusCode = "REVIEW_REQUIRED";
    private long projectNonce;
    private String tokenIdentity = "";
    private long expiresAtMillis;

    public DemolitionApprovalScreen(ConfirmationSummaryS2C summary) {
        super(Component.translatable("screen.steve_create_agent.confirmation"));
        this.summary = summary;
        this.projectNonce = summary.projectNonce();
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        int buttonY = Math.min(height / 2 + 103, height - 26);
        approve = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.approve_clear"),
                ignored -> approve())
                .bounds(left, buttonY, 100, 20).build());
        approve.active = summary.approvable() && tokenIdentity.isEmpty();
        salvage = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.select_salvage"),
                ignored -> selectSalvage())
                .bounds(left + 105, buttonY, 100, 20).build());
        salvage.active = !tokenIdentity.isEmpty();
        reselect = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.reselect"),
                ignored -> reselect())
                .bounds(left + 210, buttonY, 95, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 155;
        int y = Math.max(8, height / 2 - 116);
        graphics.drawCenteredString(font, title, width / 2, y, 0xFFFFFF);
        y += 17;
        line(graphics, left, y, "approval_target",
                Component.literal(summary.target() + " ×" + summary.quantity())); y += 14;
        line(graphics, left, y, "approval_layout",
                Component.literal(summary.layoutVariant() + " / " + summary.orientation())); y += 14;
        line(graphics, left, y, "approval_execution",
                Component.literal(summary.executionMode().name())); y += 14;
        line(graphics, left, y, "approval_footprint",
                Component.literal(sizeX() + " × " + sizeY() + " × " + sizeZ())); y += 14;
        line(graphics, left, y, "approval_work", Component.translatable(
                "screen.steve_create_agent.approval_work_value", summary.placeCount(),
                summary.reuseCount(), summary.clearCount())); y += 14;
        line(graphics, left, y, "approval_conflicts", Component.translatable(
                "screen.steve_create_agent.approval_conflicts_value",
                summary.protectedCount(), summary.containerCount(), summary.unknownCount(),
                summary.hazardCount())); y += 14;
        line(graphics, left, y, "approval_materials",
                Component.literal(trim(summary.requiredInputs(), 58))); y += 14;
        line(graphics, left, y, "approval_estimate", Component.translatable(
                "screen.steve_create_agent.approval_estimate_value", summary.recommendedBots(),
                summary.phaseEstimate(), summary.riskLevel())); y += 14;
        line(graphics, left, y, "approval_safety",
                Component.literal(summary.safetyPolicy())); y += 14;
        int statusColor = summary.approvable() ? 0x66FF99 : 0xFF6666;
        String displayed = tokenIdentity.isEmpty()
                ? (summary.approvable() ? statusCode : "HARD_CONFLICT_PRESENT")
                : "APPROVAL_ACTIVE · " + Math.max(0L,
                        (expiresAtMillis - System.currentTimeMillis()) / 1_000L) + "s";
        graphics.drawString(font, displayed, left, y, statusColor, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void approve() {
        approve.active = false;
        statusCode = "APPROVAL_PENDING";
        PlayerWorkflowNetwork.approveProject(new ApproveProjectC2S(summary.projectId(),
                projectNonce, summary.planHash(), summary.snapshotHash()));
    }

    private void reselect() {
        reselect.active = false;
        PlayerWorkflowNetwork.reselectPlacement(new ReselectPlacementC2S(
                summary.projectId(), projectNonce));
    }

    public void acceptApproval(ApprovalResultS2C result) {
        statusCode = result.statusCode();
        if (result.success()) {
            tokenIdentity = result.tokenIdentity();
            expiresAtMillis = result.expiresAtMillis();
            projectNonce = result.project().projectNonce();
            approve.active = false;
            salvage.active = true;
        } else {
            approve.active = summary.approvable();
        }
    }

    private void selectSalvage() {
        SalvageSelectionController.activate(resultProject());
        minecraft.setScreen(null);
    }

    private dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire resultProject() {
        return new dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire(
                summary.projectId(), summary.target(), summary.quantity(), "AWAITING_APPROVAL",
                "APPROVAL_ACTIVE", projectNonce, true,
                summary.anchorX(), summary.anchorY(), summary.anchorZ(), summary.orientation(),
                summary.layoutVariant(), summary.executionMode());
    }

    private int sizeX() { return summary.maxX() - summary.minX() + 1; }
    private int sizeY() { return summary.maxY() - summary.minY() + 1; }
    private int sizeZ() { return summary.maxZ() - summary.minZ() + 1; }

    private static String trim(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }

    private void line(GuiGraphics graphics, int x, int y, String labelKey, Component value) {
        graphics.drawString(font, Component.translatable(
                "screen.steve_create_agent." + labelKey), x, y, 0xB8C8D8, false);
        graphics.drawString(font, value, x + 82, y, 0xFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
