package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Asking for approval twice must not end the project.
 *
 * <p>Asking once advances the project to {@link WorkflowStage#AWAITING_APPROVAL}, and the
 * check demanded the stage still be {@link WorkflowStage#SITE_SURVEY}. So the second ask
 * always failed with SITE_SURVEY_NOT_READY — and at that point the terminal offers
 * exactly one button. A player who reopened their terminal, or clicked twice, had a
 * project that could not go forward and had nothing to go back to.
 */
class PlayerApprovalReentryTest {
    private static final UUID PROJECT = UUID.nameUUIDFromBytes("project".getBytes());
    private static final long NONCE = 7L;
    private static final String PLAN_HASH = "a".repeat(64);
    private static final String SITE_HASH = "b".repeat(64);

    /** The stage the first ask leaves behind is the one the second ask arrives in. */
    @Test
    void acceptsAProjectAlreadyAwaitingApproval() {
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.AWAITING_APPROVAL, new BlockPos3i(1, 2, 3), PLAN_HASH, SITE_HASH),
                PROJECT, NONCE))
                .isNull();
    }

    /** The ordinary first ask, unchanged. */
    @Test
    void acceptsAProjectFreshFromItsSurvey() {
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.SITE_SURVEY, new BlockPos3i(1, 2, 3), PLAN_HASH, SITE_HASH),
                PROJECT, NONCE))
                .isNull();
    }

    /**
     * The three conditions that actually mean "not surveyed yet", still refusing.
     *
     * <p>Without these the relaxation would have thrown away the check rather than fixed
     * it: a project with no anchor has nothing to survey, and saying so is the point.</p>
     */
    @Test
    void stillRefusesAProjectWithNothingToSurvey() {
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.SITE_SURVEY, null, PLAN_HASH, SITE_HASH), PROJECT, NONCE))
                .isEqualTo("SITE_SURVEY_NOT_READY");
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.SITE_SURVEY, new BlockPos3i(1, 2, 3), "", SITE_HASH),
                PROJECT, NONCE))
                .isEqualTo("SITE_SURVEY_NOT_READY");
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.SITE_SURVEY, new BlockPos3i(1, 2, 3), PLAN_HASH, ""),
                PROJECT, NONCE))
                .isEqualTo("SITE_SURVEY_NOT_READY");
    }

    /** Stages that genuinely have no business here are still turned away. */
    @Test
    void stillRefusesAStageThatHasNotSurveyedYet() {
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.GOAL_SELECTION, new BlockPos3i(1, 2, 3), PLAN_HASH, SITE_HASH),
                PROJECT, NONCE))
                .isEqualTo("SITE_SURVEY_NOT_READY");
        assertThat(PlayerApprovalService.validate(
                entry(WorkflowStage.CONSTRUCTION, new BlockPos3i(1, 2, 3), PLAN_HASH, SITE_HASH),
                PROJECT, NONCE))
                .isEqualTo("SITE_SURVEY_NOT_READY");
    }

    /** Identity and freshness checks are untouched. */
    @Test
    void stillRefusesTheWrongProjectAndAStaleRequest() {
        var ready = entry(WorkflowStage.AWAITING_APPROVAL, new BlockPos3i(1, 2, 3), PLAN_HASH, SITE_HASH);
        assertThat(PlayerApprovalService.validate(ready, UUID.randomUUID(), NONCE))
                .isEqualTo("PROJECT_NOT_FOUND");
        assertThat(PlayerApprovalService.validate(ready, PROJECT, NONCE + 1))
                .isEqualTo("STALE_PROJECT_REQUEST");
        assertThat(PlayerApprovalService.validate(null, PROJECT, NONCE))
                .isEqualTo("PROJECT_NOT_FOUND");
    }

    private static PlayerWorkflowSavedData.ProjectEntry entry(
            WorkflowStage stage, BlockPos3i anchor, String planHash, String siteHash) {
        return new PlayerWorkflowSavedData.ProjectEntry(
                PROJECT, UUID.nameUUIDFromBytes("player".getBytes()),
                ResourceId.parse("create:cogwheel"), 1,
                ResourceId.parse("minecraft:overworld"), stage,
                ProductionMode.ONCE, PlayerExecutionMode.SMART_RECOMMENDED,
                LayoutVariant.COMPACT, QuarterTurn.ZERO, anchor,
                NONCE, 1L, 2L, "OK", planHash, siteHash);
    }
}
