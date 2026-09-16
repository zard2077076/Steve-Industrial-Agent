package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialAction;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialSnapshotS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Required material-source review; it exposes only requirement-relevant aggregate counts. */
@OnlyIn(Dist.CLIENT)
public final class MaterialSourceScreen extends Screen {
    private MaterialSnapshotS2C snapshot;
    private boolean allowSalvage;
    private Button confirm;
    private Button salvage;
    private Button addSource;
    private Button reset;

    public MaterialSourceScreen(MaterialSnapshotS2C snapshot) {
        super(Component.translatable("screen.steve_create_agent.material_title"));
        this.snapshot = snapshot;
        this.allowSalvage = snapshot.allowSalvage();
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        int y = height / 2 + 62;
        confirm = addRenderableWidget(Button.builder(
                confirmText(), ignored -> send("MATERIAL_RESERVED".equals(snapshot.project().stage())
                        ? MaterialAction.START : MaterialAction.CONFIRM))
                .bounds(left, y, 100, 20).build());
        addSource = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.material_add"), ignored -> {
                    MaterialSourceSelectionController.activate(snapshot.project());
                    Minecraft.getInstance().setScreen(null);
                }).bounds(left + 104, y, 86, 20).build());
        reset = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.material_reset"),
                ignored -> send(MaterialAction.RESET)).bounds(left + 194, y, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> send(MaterialAction.CANCEL)).bounds(left + 276, y, 62, 20).build());
        salvage = addRenderableWidget(Button.builder(salvageText(), ignored -> {
            allowSalvage = !allowSalvage;
            salvage.setMessage(salvageText());
        }).bounds(left, y + 24, 210, 20).build());
        refresh();
    }

    public void update(MaterialSnapshotS2C value) {
        snapshot = value;
        allowSalvage = value.allowSalvage();
        if (salvage != null) salvage.setMessage(salvageText());
        refresh();
    }

    /** A rejected server action must never leave the ordinary player trapped behind a disabled button. */
    public void rejectAction() {
        refresh();
    }

    private void refresh() {
        if (confirm != null) confirm.active = snapshot.sufficient()
                && ("MATERIAL_SOURCE_SELECTION".equals(snapshot.project().stage())
                || "MATERIAL_RESERVED".equals(snapshot.project().stage()));
        if (confirm != null) confirm.setMessage(confirmText());
        boolean selecting = "MATERIAL_SOURCE_SELECTION".equals(snapshot.project().stage());
        if (addSource != null) addSource.active = selecting;
        if (reset != null) reset.active = selecting;
        if (salvage != null) salvage.active = selecting;
    }

    private Component confirmText() {
        return Component.translatable("MATERIAL_RESERVED".equals(snapshot.project().stage())
                ? "screen.steve_create_agent.material_start"
                : "screen.steve_create_agent.material_confirm");
    }

    private Component salvageText() {
        return Component.translatable("screen.steve_create_agent.material_salvage",
                allowSalvage ? "ON" : "OFF");
    }

    private void send(MaterialAction action) {
        confirm.active = false;
        PlayerWorkflowNetwork.controlMaterials(new MaterialControlC2S(
                snapshot.project().projectId(), snapshot.project().projectNonce(), action,
                allowSalvage));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int cardLeft = Math.max(10, width / 2 - 166);
        int cardRight = Math.min(width - 10, width / 2 + 166);
        int top = Math.max(12, height / 2 - 118);
        int bottom = Math.min(height - 12, height / 2 + 112);
        graphics.fill(cardLeft, top, cardRight, bottom, 0xE9142030);
        graphics.fill(cardLeft, top, cardRight, top + 2, 0xFF4DB6E8);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFF);
        graphics.drawString(font, Component.literal("材料来源  ·  " + snapshot.sourceCount() + " 个容器"),
                cardLeft + 16, top + 32, 0xB8D8F0);
        graphics.drawString(font, Component.literal("服务器会在确认前重新扫描并锁定槽位"),
                cardLeft + 16, top + 46, 0x8198AD);
        int y = top + 66;
        graphics.fill(cardLeft + 12, y - 5, cardRight - 12, y + 12, 0xAA1D3042);
        graphics.drawString(font, Component.literal("材料"), cardLeft + 18, y, 0x8EB6D3);
        graphics.drawString(font, Component.literal("可用 / 需求"), cardRight - 88, y, 0x8EB6D3);
        y += 20;
        for (var line : snapshot.lines()) {
            int color = line.available() >= line.required() ? 0x9FE09F : 0xFF9B6A;
            graphics.drawString(font, Component.literal(line.item()), cardLeft + 18, y, color);
            Component count = Component.literal(line.available() + " / " + line.required());
            graphics.drawString(font, count, cardRight - 18 - font.width(count), y, color);
            y += 15;
        }
        if (!snapshot.sufficient()) {
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.material_missing"), cardLeft + 18, y + 5, 0xFF9B6A);
        } else if ("MATERIAL_RESERVED".equals(snapshot.project().stage())) {
            graphics.drawString(font, Component.translatable(
                    "screen.steve_create_agent.material_reserved", snapshot.reserved()),
                    cardLeft + 18, y + 5, 0x80FFB0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
