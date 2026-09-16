package dev.stevecreate.agent.core.siteprep;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rescan confirms what clearing was asked to remove; it does not go looking for more.
 *
 * <p>It used to re-decide what counts as an obstacle, with a wider rule than the survey
 * had used — anything not PROTECTED — and the two disagreed about the ground the site
 * stands on. Only visible without a grading specification, because the post-clearance
 * survey filters out everything at or below the graded surface and there is no surface to
 * filter by. A site that is already flat gets no grading spec, so the player's own floor
 * came back as an obstruction: the survey reported zero obstacles, clearing changed
 * nothing, and preparation refused for a stone block the player had laid deliberately.
 */
class PostClearanceObstructionTest {
    private static final BlockPos3i FLOOR = new BlockPos3i(-194, 68, 25);
    private static final BlockPos3i TREE = new BlockPos3i(-194, 70, 29);

    /** The floor the player laid, which the survey never called an obstacle. */
    @Test
    void ignoresGroundTheSurveyNeverReported() {
        SiteSurveySnapshot initial = survey(List.of());
        SiteSurveySnapshot clean = survey(List.of(
                finding(FLOOR, "minecraft:stone", ObstacleClassification.SAFE_NATURAL_CLEARABLE)));

        assertThat(PostClearanceRescan.obstructions(initial, clean, null, Set.of()))
                .as("the survey found nothing, so clearing owed nothing")
                .isEmpty();
    }

    /**
     * The assertion with teeth: something clearing was asked to remove and did not.
     *
     * <p>Without this the change would have thrown the check away rather than corrected
     * it, and a site could be declared prepared with the obstacle still standing.</p>
     */
    @Test
    void stillRefusesAnApprovedObstacleThatSurvivedClearing() {
        SiteSurveySnapshot initial = survey(List.of(
                finding(TREE, "minecraft:oak_leaves",
                        ObstacleClassification.SAFE_NATURAL_CLEARABLE)));
        SiteSurveySnapshot clean = survey(List.of(
                finding(TREE, "minecraft:oak_leaves",
                        ObstacleClassification.SAFE_NATURAL_CLEARABLE)));

        assertThat(PostClearanceRescan.obstructions(initial, clean, null, Set.of()))
                .extracting(ObstacleFinding::position)
                .containsExactly(TREE);
    }

    /** Cleared means gone, and gone means prepared. */
    @Test
    void acceptsASiteWhereEverythingSurveyedWasRemoved() {
        SiteSurveySnapshot initial = survey(List.of(
                finding(TREE, "minecraft:oak_leaves",
                        ObstacleClassification.SAFE_NATURAL_CLEARABLE)));

        assertThat(PostClearanceRescan.obstructions(initial, survey(List.of()), null, Set.of()))
                .isEmpty();
    }

    /**
     * Protected blocks are exempt, as they always were.
     *
     * <p>Clearing cannot remove them, so requiring them gone would be a demand nothing
     * could satisfy.</p>
     */
    @Test
    void ignoresAProtectedBlockEvenWhenTheSurveyFoundIt() {
        SiteSurveySnapshot initial = survey(List.of(
                finding(TREE, "minecraft:bedrock",
                        ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL)));

        assertThat(PostClearanceRescan.obstructions(initial, initial, null, Set.of())).isEmpty();
    }

    private static SiteSurveySnapshot survey(List<ObstacleFinding> findings) {
        return new SiteSurveySnapshot("world:test", ResourceId.parse("minecraft:overworld"),
                new DeploymentBoundingBox(new BlockPos3i(-206, 60, 13),
                        new BlockPos3i(-182, 80, 37)),
                "a".repeat(64), "b".repeat(64), Instant.ofEpochSecond(1_000),
                findings, "c".repeat(64));
    }

    private static ObstacleFinding finding(
            BlockPos3i position, String blockId, ObstacleClassification classification) {
        return new ObstacleFinding("obstacle:" + position.x() + "_" + position.y()
                + "_" + position.z(), position, ResourceId.parse(blockId), "d".repeat(64),
                false, false, false, false, true, 1.5, "none", "self", false,
                classification, 100, "survey", "test fixture");
    }
}
