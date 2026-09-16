package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationCandidateWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ReselectPlacementC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.SelectRelocationCandidateC2S;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Shows only player-meaningful candidate explanations, never internal weight noise. */
@OnlyIn(Dist.CLIENT)
public final class RelocationAdvisorScreen extends Screen {
    private final RelocationResultS2C result;
    private final List<Button> choiceButtons = new ArrayList<>();

    public RelocationAdvisorScreen(RelocationResultS2C result) {
        super(Component.translatable("screen.steve_create_agent.relocation"));
        this.result = result;
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int y = height / 2 - 54;
        for (RelocationCandidateWire candidate : result.candidates()) {
            Component label = candidate.candidateId().equals(result.recommendedId())
                    ? Component.translatable("screen.steve_create_agent.use_recommended")
                    : Component.translatable("screen.steve_create_agent.keep_current");
            Button button = addRenderableWidget(Button.builder(label,
                    ignored -> select(candidate)).bounds(left + 205, y, 95, 20).build());
            choiceButtons.add(button);
            y += 52;
        }
        Button reselect = addRenderableWidget(Button.builder(
                Component.translatable("screen.steve_create_agent.reselect"),
                ignored -> reselect()).bounds(left, height - 34, 145, 20).build());
        reselect.active = !result.projectId().startsWith("00000000");
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> onClose()).bounds(left + 155, height - 34, 145, 20).build());
    }

    private void select(RelocationCandidateWire candidate) {
        choiceButtons.forEach(button -> button.active = false);
        PlayerWorkflowNetwork.selectRelocation(new SelectRelocationCandidateC2S(
                result.projectId(), result.projectNonce(), candidate.candidateId()));
    }

    private void reselect() {
        PlayerWorkflowNetwork.reselectPlacement(new ReselectPlacementC2S(
                result.projectId(), result.projectNonce()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 150;
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 86, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable(result.success()
                        ? "screen.steve_create_agent.relocation_found"
                        : "screen.steve_create_agent.relocation_not_found"),
                width / 2, height / 2 - 70, result.success() ? 0x66FF99 : 0xFFCC66);
        int y = height / 2 - 54;
        for (RelocationCandidateWire candidate : result.candidates()) {
            int background = candidate.safe() ? 0x60306048 : 0x60584030;
            graphics.fill(left, y, left + 200, y + 46, background);
            String heading = candidate.current()
                    ? Component.translatable("screen.steve_create_agent.current_site").getString()
                    : Component.translatable("screen.steve_create_agent.recommended_site").getString();
            graphics.drawString(font, heading + "  " + candidate.x() + ", "
                    + candidate.y() + ", " + candidate.z(), left + 5, y + 4, 0xFFFFFF, false);
            graphics.drawString(font, candidate.orientation().name() + " / "
                    + candidate.variant().name(), left + 5, y + 15, 0xC8D8E8, false);
            graphics.drawString(font, "clear=" + candidate.demolitionCount()
                    + " protected=" + candidate.protectedCount()
                    + " unknown=" + candidate.unknownCount()
                    + " containers=" + candidate.containerCount(),
                    left + 5, y + 26, candidate.safe() ? 0x99FFAA : 0xFFAA88, false);
            String reasons = String.join(", ", candidate.reasons()).replace('_', ' ');
            graphics.drawString(font, font.plainSubstrByWidth(reasons, 190),
                    left + 5, y + 36, 0xA8B8C8, false);
            y += 52;
        }
        if (result.candidates().isEmpty()) {
            graphics.drawCenteredString(font, result.statusCode(), width / 2,
                    height / 2 - 20, 0xFF7777);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
