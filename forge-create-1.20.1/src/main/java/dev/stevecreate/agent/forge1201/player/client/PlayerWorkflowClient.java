package dev.stevecreate.agent.forge1201.player.client;

import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.OpenTerminalS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectControlResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationProgressS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ConfirmationSummaryS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ApprovalResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.SalvageResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeCompletionS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PlayerWorkflowClient {
    private static ClearingStatusS2C latestClearingStatus;
    private static ProjectWire pendingManagement;
    private static String pendingSurveyProject;
    private PlayerWorkflowClient() {}

    public static void openTerminal(OpenTerminalS2C message) {
        Minecraft.getInstance().setScreen(new EngineerTerminalScreen(message));
    }

    /**
     * Search results, handed to the picker if it is still the screen that asked.
     *
     * <p>Ignored otherwise: a reply outrunning a closed screen should do nothing rather
     * than reopen it.</p>
     */
    public static void goalResults(PlayerWorkflowPackets.GoalListS2C message) {
        if (Minecraft.getInstance().screen instanceof GoalPickerScreen picker) {
            picker.showSearchResults(message);
        }
    }

    public static void projectResult(ProjectResultS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!message.success()) {
            if (minecraft.screen instanceof GoalPickerScreen picker) {
                picker.showServerFailure(message.statusCode());
            }
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message.statusCode()), false);
            }
            return;
        }
        minecraft.setScreen(null);
        latestClearingStatus = null;
        pendingManagement = null;
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.steve_create_agent.project_created"), false);
        }
        PlacementController.activate(message.project());
    }

    public static void previewSnapshot(PreviewSnapshotS2C message) {
        if (pendingSurveyProject != null && pendingSurveyProject.equals(message.projectId())) {
            pendingSurveyProject = null;
            if (message.success()) openSurvey(message);
            else PlacementController.accept(message);
            return;
        }
        PlacementController.accept(message);
    }

    public static void continueProject(ProjectWire project) {
        if (project == null) return;
        switch (project.stage()) {
            case "PLACEMENT_PREVIEW" -> {
                Minecraft.getInstance().setScreen(null);
                PlacementController.activate(project);
            }
            case "SITE_SURVEY" -> {
                if (!project.hasAnchor()) return;
                pendingSurveyProject = project.projectId();
                PlayerWorkflowNetwork.requestPreview(
                        new dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewRequestC2S(
                                project.projectId(), project.projectNonce(), project.anchorX(),
                                project.anchorY(), project.anchorZ(), project.orientation(),
                                project.layoutVariant(), false));
            }
            case "AWAITING_APPROVAL" -> PlayerWorkflowNetwork.requestConfirmation(
                    new dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RequestConfirmationC2S(
                            project.projectId(), project.projectNonce()));
            case "MATERIAL_SOURCE_SELECTION", "MATERIAL_RESERVED", "COMPLETED" ->
                    PlayerWorkflowNetwork.controlMaterials(
                            new dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialControlC2S(
                                    project.projectId(), project.projectNonce(),
                                    dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialAction.SNAPSHOT,
                                    false));
            case "CLEARING", "POST_CLEAR_RESCAN", "CONSTRUCTION", "PAUSED" ->
                    manageProject(project);
            default -> { }
        }
    }

    public static void openSurvey(PreviewSnapshotS2C preview) {
        Minecraft.getInstance().setScreen(new SiteSurveySummaryScreen(preview));
        PlacementController.clearLocal();
    }

    public static void controlResult(ProjectControlResultS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (message.success()) {
            minecraft.setScreen(null);
        } else if (minecraft.screen instanceof ConstructionProgressScreen
                && latestClearingStatus != null) {
            // The progress screen disables controls while a server action is in flight.
            // A refusal is retryable player state, so reconstruct it from the last
            // authoritative snapshot instead of leaving every button disabled forever.
            minecraft.setScreen(new ConstructionProgressScreen(latestClearingStatus));
        }
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(
                    message.success() ? "Project cancelled" : message.statusCode()), false);
        }
    }

    public static void relocationProgress(RelocationProgressS2C message) {
        if (Minecraft.getInstance().screen instanceof SiteSurveySummaryScreen survey) {
            survey.updateProgress(message.completed(), message.total());
        }
    }

    public static void relocationResult(RelocationResultS2C message) {
        Minecraft.getInstance().setScreen(new RelocationAdvisorScreen(message));
    }

    public static void confirmationSummary(ConfirmationSummaryS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!message.success()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message.statusCode()), false);
            }
            return;
        }
        minecraft.setScreen(new DemolitionApprovalScreen(message));
    }

    public static void approvalResult(ApprovalResultS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DemolitionApprovalScreen screen) {
            screen.acceptApproval(message);
        } else if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message.statusCode()), false);
        }
    }

    public static void salvageResult(SalvageResultS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!message.success()) {
            // Hand the clicks back. Showing the code and returning left the controller
            // pending, and pending is what makes it swallow every right-click — with no
            // screen to escape from, one refused container ended the session.
            SalvageSelectionController.retryable();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message.statusCode()), false);
            }
            return;
        }
        SalvageSelectionController.clear();
        minecraft.setScreen(new ClearingReadyScreen(message.project()));
    }

    public static void clearingStatus(ClearingStatusS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!message.success()) {
            pendingManagement = null;
            if (minecraft.screen instanceof ClearingReadyScreen screen) {
                screen.showFailure(message.statusCode());
            }
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message.statusCode()), false);
            }
            return;
        }
        latestClearingStatus = message;
        if (message.project() != null
                && "MATERIAL_SOURCE_SELECTION".equals(message.project().stage())) {
            pendingManagement = null;
            PlayerWorkflowNetwork.controlMaterials(
                    new dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialControlC2S(
                            message.project().projectId(), message.project().projectNonce(),
                            dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialAction.SNAPSHOT,
                            false));
            return;
        }
        if (minecraft.screen instanceof ClearingReadyScreen) minecraft.setScreen(null);
        if (minecraft.screen instanceof ConstructionProgressScreen progress) {
            progress.update(message);
        } else if (pendingManagement != null && message.project() != null
                && pendingManagement.projectId().equals(message.project().projectId())) {
            pendingManagement = null;
            minecraft.setScreen(new ConstructionProgressScreen(message));
        }
    }

    public static void manageProject(ProjectWire project) {
        if (project == null) return;
        if (latestClearingStatus != null && latestClearingStatus.project() != null
                && project.projectId().equals(latestClearingStatus.project().projectId())
                && project.projectNonce() == latestClearingStatus.project().projectNonce()
                && project.stage().equals(latestClearingStatus.project().stage())) {
            Minecraft.getInstance().setScreen(new ConstructionProgressScreen(latestClearingStatus));
            return;
        }
        pendingManagement = project;
        PlayerWorkflowNetwork.controlClearing(
                new dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingControlC2S(
                        project.projectId(), project.projectNonce(),
                        dev.stevecreate.agent.forge1201.command.PlayerClearingService.Action.STATUS));
    }

    public static ClearingStatusS2C latestClearingStatus() {
        return latestClearingStatus;
    }

    public static void materialSnapshot(MaterialSnapshotS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!message.success()) {
            MaterialSourceSelectionController.reject();
            Component failure = Component.literal(message.statusCode());
            if (message.detail() != null && !message.detail().isBlank()
                    && !message.detail().equals(message.statusCode())) {
                failure = failure.copy().append(Component.literal(": " + message.detail()));
            }
            if (minecraft.screen instanceof MaterialSourceScreen screen) {
                screen.rejectAction();
                if (minecraft.player != null) minecraft.player.displayClientMessage(
                        failure, false);
            } else if (minecraft.player != null) {
                minecraft.player.displayClientMessage(failure, false);
            }
            return;
        }
        MaterialSourceSelectionController.clear();
        if ("CANCELLED".equals(message.project().stage())) {
            minecraft.setScreen(null);
            return;
        }
        if (message.report() != null || "COMPLETED".equals(message.project().stage())) {
            minecraft.setScreen(new CompletionReportScreen(message));
            return;
        }
        if ("CONSTRUCTION".equals(message.project().stage())) {
            minecraft.setScreen(null);
            latestClearingStatus = null;
            manageProject(message.project());
            return;
        }
        if (minecraft.screen instanceof MaterialSourceScreen screen) screen.update(message);
        else minecraft.setScreen(new MaterialSourceScreen(message));
    }

    public static void metalPressStatus(MetalPressStatusS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MetalPressOrderScreen screen) screen.update(message);
        else minecraft.setScreen(new MetalPressOrderScreen(message));
    }

    public static void compositeStatus(CompositeStatusS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CompositeOrderScreen screen) screen.update(message);
        else minecraft.setScreen(new CompositeOrderScreen(message));
    }

    public static void compositeCompletion(CompositeCompletionS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CompositeCompletionScreen screen) screen.update(message);
        else minecraft.setScreen(new CompositeCompletionScreen(message));
    }
}
