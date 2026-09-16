package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.player.WorkflowStage;
import org.junit.jupiter.api.Test;

class PlayerClearingServiceTest {
    @Test
    void restartLossFailsClosedInsteadOfClaimingTheExecutorResumed() {
        var idle = snapshot(false, false, false, "IDLE", "IDLE");

        assertThat(PlayerClearingService.transition(
                WorkflowStage.CLEARING, "BOT_CLEARING_RUNNING", idle))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.PAUSED, "RECOVERY_REAPPROVAL_REQUIRED"));
        assertThat(PlayerClearingService.transition(
                WorkflowStage.CONSTRUCTION, "CONSTRUCTION_MATERIAL_SOURCE_REQUIRED", idle))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.PAUSED, "CONSTRUCTION_RECOVERY_REPLAN_REQUIRED"));
    }

    @Test
    void evidenceDrivenTransitionsNeverInventConstructionProgress() {
        var clearing = snapshot(true, false, false, "CLEAR", "RUNNING");
        var paused = snapshot(true, true, false, "CLEAR", "SALVAGE_CAPACITY_OR_DESTINATION_CHANGED");
        var prepared = snapshot(false, false, true, "PREPARED", "POST_CLEARANCE_VERIFIED");

        assertThat(PlayerClearingService.transition(
                WorkflowStage.CLEARING, "BOT_CLEARING_RUNNING", clearing).stage())
                .isEqualTo(WorkflowStage.CLEARING);
        assertThat(PlayerClearingService.transition(
                WorkflowStage.CLEARING, "BOT_CLEARING_RUNNING", paused))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.PAUSED, "SALVAGE_CAPACITY_OR_DESTINATION_CHANGED"));
        assertThat(PlayerClearingService.transition(
                WorkflowStage.POST_CLEAR_RESCAN, "POST_CLEAR_RESCAN_RUNNING", prepared))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.MATERIAL_SOURCE_SELECTION,
                        "MATERIAL_SOURCE_SELECTION_REQUIRED"));
    }

    @Test
    void preparedSiteCannotEraseATransactionalMaterialPause() {
        var prepared = snapshot(false, false, true, "PREPARED", "POST_CLEARANCE_VERIFIED");

        assertThat(PlayerClearingService.transition(
                WorkflowStage.PAUSED, "CONSTRUCTION_FAILED:MATERIALS_RETURNED", prepared))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.PAUSED, "CONSTRUCTION_FAILED:MATERIALS_RETURNED"));
        assertThat(PlayerClearingService.transition(
                WorkflowStage.PAUSED, "RETURN_PENDING", prepared))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.PAUSED, "RETURN_PENDING"));
    }

    @Test
    void restoredSiteEvidenceLetsAProjectCarryOnInsteadOfStrandingIt() {
        // The site state used to live only in a static map, so after a restart every
        // snapshot said "not prepared" and a persisted project in material selection was
        // pushed into a pause whose only real exit was cancelling it. Restoring the exact
        // evidence puts the project back on the branch the player can act on.
        var lost = snapshot(false, false, false, "IDLE", "IDLE");
        var restored = snapshot(false, false, true, "PREPARED", "POST_CLEARANCE_VERIFIED");

        assertThat(PlayerClearingService.transition(
                WorkflowStage.MATERIAL_SOURCE_SELECTION, "MATERIAL_SOURCE_SELECTION_REQUIRED", lost))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.PAUSED, "CONSTRUCTION_RECOVERY_REPLAN_REQUIRED"));
        assertThat(PlayerClearingService.transition(
                WorkflowStage.MATERIAL_SOURCE_SELECTION, "MATERIAL_SOURCE_SELECTION_REQUIRED",
                restored))
                .isEqualTo(new PlayerClearingService.StageTransition(
                        WorkflowStage.MATERIAL_SOURCE_SELECTION,
                        "MATERIAL_SOURCE_SELECTION_REQUIRED"));
    }

    private static SitePreparationCommand.PlayerClearingSnapshot snapshot(
            boolean active, boolean paused, boolean prepared, String phase, String code) {
        return new SitePreparationCommand.PlayerClearingSnapshot(
                active, paused, prepared, phase, 0, 0, 0, 0, 0, 0, code, true);
    }
}
