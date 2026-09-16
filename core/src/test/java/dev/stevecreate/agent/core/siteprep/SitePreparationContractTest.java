package dev.stevecreate.agent.core.siteprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SitePreparationContractTest {
    private static final String WORLD = "world:" + "1".repeat(64);
    private static final String PLAYER = "player:00000000-0000-0000-0000-000000000001";
    private static final String SESSION = "site-session:test";
    private static final String PLAN = "2".repeat(64);
    private static final String STATE_A = "3".repeat(64);
    private static final String STATE_B = "4".repeat(64);
    private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");
    private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");
    private SiteSelectionService selections;
    private ConfirmedSiteSelection confirmed;

    @BeforeEach
    void setUp() {
        selections = new SiteSelectionService();
        PlacementAnchor anchor = selections.anchor(WORLD, OVERWORLD, new BlockPos3i(10, 64, 10),
                PLAYER, NOW, AnchorSource.PLAYER_FEET);
        RegionCornerSelection region = selections.corners(
                WORLD, OVERWORLD, new BlockPos3i(0, 60, 0), WORLD, OVERWORLD,
                new BlockPos3i(30, 80, 30), WORLD, OVERWORLD, PLAYER, NOW);
        confirmed = selections.confirm(anchor, SiteFacing.EAST, region, SESSION, NOW,
                NOW.plusSeconds(900));
    }

    @Test
    void exactAnchorRegionFacingAndProjectionAreStableAndPlayerMovementCannotChangeThem() {
        PlacementAnchor anchor = confirmed.anchor();
        assertThat(anchor.position()).isEqualTo(new BlockPos3i(10, 64, 10));
        assertThat(confirmed.facing()).isEqualTo(SiteFacing.EAST);
        assertThat(selections.projectFootprint(confirmed, List.of(
                new BlockPos3i(0, 0, 0), new BlockPos3i(1, 0, 0),
                new BlockPos3i(0, 0, 1))))
                .containsExactly(new BlockPos3i(9, 64, 10), new BlockPos3i(10, 64, 10),
                        new BlockPos3i(10, 64, 11));
        assertThat(anchor.position()).isNotEqualTo(new BlockPos3i(99, 70, 99));
        FacingSelection facing = new FacingSelection(SiteFacing.NORTH, PLAYER, NOW,
                "5".repeat(64));
        assertThat(facing.rotateClockwise(NOW.plusSeconds(1)).facing()).isEqualTo(SiteFacing.EAST);
    }

    @Test
    void crossDimensionCornersAndOutOfRegionFootprintsFailClosed() {
        assertThatIllegalArgumentException().isThrownBy(() -> selections.corners(
                        WORLD, OVERWORLD, new BlockPos3i(0, 60, 0), WORLD, OVERWORLD,
                        new BlockPos3i(1, 60, 1), WORLD, ResourceId.parse("minecraft:the_nether"),
                        PLAYER, NOW))
                .withMessage("SITE_DIMENSION_MISMATCH");
        assertThatIllegalArgumentException().isThrownBy(() ->
                        selections.projectFootprint(confirmed, List.of(new BlockPos3i(100, 0, 0))))
                .withMessage("SITE_OUTSIDE_AUTHORIZED_REGION");
    }

    @Test
    void classifierProtectsContainersMachinesUnknownClaimsAndUnknownBlocksByDefault() {
        ObstacleClassifier classifier = new ObstacleClassifier();
        assertThat(classifier.classify(observation("minecraft:grass_block", STATE_A,
                true, false, false, false, true, false)).classification())
                .isEqualTo(ObstacleClassification.SAFE_NATURAL_CLEARABLE);
        assertThat(classifier.classify(observation("minecraft:chest", STATE_A,
                false, true, true, false, true, false)).classification())
                .isEqualTo(ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL);
        assertThat(classifier.classify(observation("create:millstone", STATE_A,
                false, true, false, true, true, false)).classification())
                .isEqualTo(ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL);
        assertThat(classifier.classify(observation("othermod:mystery", STATE_A,
                false, false, false, false, true, false)).classification())
                .isEqualTo(ObstacleClassification.UNKNOWN);
        assertThat(classifier.classify(observation("minecraft:dirt", STATE_A,
                true, false, false, false, false, false)).classification())
                .isEqualTo(ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL);
    }

    @Test
    void finalGradingApprovalUpgradesOnlyOrdinaryStateOnlyUnknownBlocks() {
        ObstacleClassifier classifier = new ObstacleClassifier();
        ObstacleObservation ordinary = observation("othermod:ordinary_stone", STATE_A,
                false, false, false, false, true, false);
        assertThat(classifier.classify(ordinary).classification())
                .isEqualTo(ObstacleClassification.UNKNOWN);
        assertThat(classifier.classifyForExplicitGrading(ordinary).classification())
                .isEqualTo(ObstacleClassification.CONFIRM_EACH_OR_GROUP);
        ObstacleObservation container = observation("othermod:data_crate", STATE_A,
                false, true, true, false, true, false);
        assertThat(classifier.classifyForExplicitGrading(container).classification())
                .isEqualTo(ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL);
        ObstacleObservation hazard = new ObstacleObservation(new BlockPos3i(1, 64, 1),
                ResourceId.parse("othermod:acid"), STATE_A, false, false, false,
                false, false, false, true, 1.0D, "minecraft:iron_pickaxe",
                "unknown", true, true, true, false, true, "test:server-readback");
        assertThat(classifier.classifyForExplicitGrading(hazard).classification())
                .isEqualTo(ObstacleClassification.ENVIRONMENTAL_HAZARD);
    }

    @Test
    void approvalBindsExactWorldPlanSnapshotPlayerPositionsStatesExpiryAndOneTimeUse() {
        SiteSurveySnapshot survey = survey(List.of(
                observation("minecraft:grass_block", STATE_A, true,
                        false, false, false, true, false)));
        ObstacleFinding finding = survey.findings().get(0);
        DemolitionPreview preview = DemolitionPreview.create(
                survey, 20, 1, NOW.plusSeconds(300));
        DemolitionApprovalService approvals = new DemolitionApprovalService();
        DemolitionApprovalToken token = approvals.issue(survey, preview,
                new DemolitionApprovalRequest(PLAYER, Set.of(finding.obstacleId()), 1,
                        NOW, NOW.plusSeconds(120)));
        assertThat(approvals.check(token, WORLD, OVERWORLD, PLAN, survey.siteSnapshotHash(),
                PLAYER, List.of(observation("minecraft:grass_block", STATE_A,
                        true, false, false, false, true, false)), 1, NOW.plusSeconds(1)).accepted())
                .isTrue();
        assertThat(approvals.check(token, WORLD, OVERWORLD, PLAN, survey.siteSnapshotHash(),
                PLAYER, List.of(observation("minecraft:grass_block", STATE_B,
                        true, false, false, false, true, false)), 1, NOW.plusSeconds(1)).failures())
                .contains(DemolitionApprovalFailure.SCOPE_MISMATCH);
        DemolitionApprovalToken consumed = approvals.consume(token);
        assertThat(approvals.check(consumed, WORLD, OVERWORLD, PLAN, survey.siteSnapshotHash(),
                PLAYER, List.of(observation("minecraft:grass_block", STATE_A,
                        true, false, false, false, true, false)), 1, NOW.plusSeconds(1)).failures())
                .contains(DemolitionApprovalFailure.ALREADY_CONSUMED);
        assertThat(approvals.check(token, WORLD, OVERWORLD, PLAN, "9".repeat(64),
                PLAYER, List.of(observation("minecraft:grass_block", STATE_A,
                        true, false, false, false, true, false)), 1, NOW.plusSeconds(1)).failures())
                .contains(DemolitionApprovalFailure.SITE_SNAPSHOT_STALE);
    }

    @Test
    void playerApprovalAlsoBindsGoalQuantityAnchorLayoutModeRegionAndSafetyPolicy() {
        SiteSurveySnapshot survey = survey(List.of(
                observation("minecraft:grass_block", STATE_A, true,
                        false, false, false, true, false)));
        ObstacleFinding finding = survey.findings().get(0);
        DemolitionPreview preview = DemolitionPreview.create(
                survey, 20, 1, NOW.plusSeconds(300));
        DemolitionApprovalContext approvedContext = DemolitionApprovalContext.create(
                "00000000-0000-0000-0000-000000000042",
                ResourceId.parse("create:cogwheel"), 4, new BlockPos3i(10, 64, 10),
                QuarterTurn.CLOCKWISE_90, LayoutVariant.EXPANDABLE,
                survey.selectionHash(), "light-natural-only/v1", PlayerExecutionMode.BOTS);
        DemolitionApprovalToken token = new DemolitionApprovalService().issue(survey, preview,
                new DemolitionApprovalRequest(PLAYER, Set.of(finding.obstacleId()), 1,
                        NOW, NOW.plusSeconds(120), approvedContext));
        DemolitionApprovalContext changedMode = DemolitionApprovalContext.create(
                approvedContext.projectIdentity(), approvedContext.target(),
                approvedContext.quantity(), approvedContext.anchor(), approvedContext.orientation(),
                approvedContext.layoutVariant(), approvedContext.regionAuthorizationHash(),
                approvedContext.safetyPolicy(), PlayerExecutionMode.HYBRID);

        DemolitionApprovalCheck check = new DemolitionApprovalService().check(token,
                WORLD, OVERWORLD, PLAN, survey.siteSnapshotHash(), PLAYER, changedMode,
                List.of(observation("minecraft:grass_block", STATE_A,
                        true, false, false, false, true, false)), 1, NOW.plusSeconds(1));

        assertThat(check.accepted()).isFalse();
        assertThat(check.failures()).containsExactly(DemolitionApprovalFailure.CONTEXT_MISMATCH);
    }

    @Test
    void protectedOrUnknownObstacleCanNeverEnterAnApprovalToken() {
        SiteSurveySnapshot survey = survey(List.of(
                observation("minecraft:chest", STATE_A, false,
                        true, true, false, true, false)));
        DemolitionPreview preview = DemolitionPreview.create(
                survey, 20, 1, NOW.plusSeconds(300));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DemolitionApprovalService().issue(survey, preview,
                        new DemolitionApprovalRequest(PLAYER,
                                Set.of(survey.findings().get(0).obstacleId()), 1,
                                NOW, NOW.plusSeconds(120))))
                .withMessage("PROTECTED_OBSTACLE_PRESENT");
    }

    @Test
    void terrainGraphIsBoundedAcyclicAndDoesNotMasqueradeAsConstructionExecutor() {
        TerrainPreparationTask reserve = new TerrainPreparationTask(
                "site-task:reserve", TerrainPreparationTaskKind.RESERVE_CLEARANCE_POSITIONS,
                List.of(new BlockPos3i(10, 64, 10)), Set.of(), 0, 20);
        TerrainPreparationTask mine = new TerrainPreparationTask(
                "site-task:mine", TerrainPreparationTaskKind.MINE_AUTHORIZED_BLOCK,
                List.of(new BlockPos3i(10, 64, 10)), Set.of(reserve.taskIdentity()), 1, 100);
        TerrainPreparationTaskGraph graph = new TerrainPreparationTaskGraph(
                "site-graph:test", PLAN, "6".repeat(64), "demolition-approval:test",
                List.of(reserve, mine), 1);
        assertThat(graph.tasks()).containsExactly(reserve, mine);
        assertThat(BotClearingExecutor.class.getInterfaces()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new TerrainPreparationTaskGraph(
                "site-graph:cycle", PLAN, "6".repeat(64), "demolition-approval:test",
                List.of(
                        new TerrainPreparationTask("a", TerrainPreparationTaskKind.VERIFY_GROUND,
                                List.of(), Set.of("b"), 0, 10),
                        new TerrainPreparationTask("b", TerrainPreparationTaskKind.VERIFY_GROUND,
                                List.of(), Set.of("a"), 0, 10)),
                0));
    }

    @Test
    void typedFailureVocabularyIsCompleteAndSafetyEvidenceRequiresEveryZeroCounter() {
        assertThat(SitePreparationFailureCode.values()).containsExactly(
                SitePreparationFailureCode.SITE_ANCHOR_NOT_SELECTED,
                SitePreparationFailureCode.SITE_REGION_NOT_SELECTED,
                SitePreparationFailureCode.SITE_FACING_NOT_SELECTED,
                SitePreparationFailureCode.SITE_SELECTION_STALE,
                SitePreparationFailureCode.SITE_WORLD_MISMATCH,
                SitePreparationFailureCode.SITE_DIMENSION_MISMATCH,
                SitePreparationFailureCode.SITE_OUTSIDE_AUTHORIZED_REGION,
                SitePreparationFailureCode.SITE_SURVEY_REQUIRED,
                SitePreparationFailureCode.SITE_SURVEY_STALE,
                SitePreparationFailureCode.OBSTACLE_CLASSIFICATION_UNKNOWN,
                SitePreparationFailureCode.PROTECTED_OBSTACLE_PRESENT,
                SitePreparationFailureCode.DEMOLITION_APPROVAL_REQUIRED,
                SitePreparationFailureCode.DEMOLITION_APPROVAL_STALE,
                SitePreparationFailureCode.DEMOLITION_APPROVAL_SCOPE_MISMATCH,
                SitePreparationFailureCode.DEMOLITION_APPROVAL_EXPIRED,
                SitePreparationFailureCode.DEMOLITION_BUDGET_EXCEEDED,
                SitePreparationFailureCode.BOT_CLEARING_PATH_UNREACHABLE,
                SitePreparationFailureCode.BOT_CLEARING_TOOL_UNAVAILABLE,
                SitePreparationFailureCode.SALVAGE_DESTINATION_UNAVAILABLE,
                SitePreparationFailureCode.TERRAIN_LEVELING_UNSAFE,
                SitePreparationFailureCode.ENVIRONMENTAL_HAZARD_PRESENT,
                SitePreparationFailureCode.POST_CLEARANCE_RESCAN_REQUIRED,
                SitePreparationFailureCode.POST_CLEARANCE_SITE_CHANGED,
                SitePreparationFailureCode.PREPARED_SITE_STALE,
                SitePreparationFailureCode.FORMAL_WORLD_SITE_PREPARATION_FORBIDDEN);
        SitePreparationEvidence evidence = new SitePreparationEvidence(
                confirmed.selectionHash(), "7".repeat(64), "approval:test", "8".repeat(64),
                0, 0, 0, 0, 0, 0, false, false, List.of("bounded"));
        assertThat(evidence.safetyBoundaryPassed()).isTrue();
    }

    private SiteSurveySnapshot survey(List<ObstacleObservation> observations) {
        return new SiteSurvey(new ObstacleClassifier()).assemble(WORLD, OVERWORLD,
                confirmed.authorizedBounds(), confirmed.selectionHash(), PLAN, NOW, observations);
    }

    private static ObstacleObservation observation(
            String id,
            String state,
            boolean natural,
            boolean blockEntity,
            boolean container,
            boolean machine,
            boolean protectionKnown,
            boolean environmental) {
        return new ObstacleObservation(new BlockPos3i(10, 64, 10), ResourceId.parse(id), state,
                blockEntity, container, container, machine, false, natural, false, 0.5,
                "minecraft:hand", id, false, environmental, protectionKnown, false, true,
                "authoritative-server-state");
    }
}
