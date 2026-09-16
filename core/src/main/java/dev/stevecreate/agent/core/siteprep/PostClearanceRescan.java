package dev.stevecreate.agent.core.siteprep;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** SP-08 exact recheck. It emits PreparedConstructionSite only from a fresh clean scan. */
public final class PostClearanceRescan {
    public PreparedConstructionSite prepare(
            ConfirmedSiteSelection selection,
            SiteSurveySnapshot initial,
            SiteSurveySnapshot clean,
            TerrainPreparationPlan plan,
            SalvageLedger salvage,
            List<TerrainMutationEvidence> mutations,
            Instant preparedAt,
            Instant expiresAt) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(clean, "clean");
        Objects.requireNonNull(plan, "plan");
        if (!initial.worldIdentity().equals(clean.worldIdentity())
                || !initial.dimension().equals(clean.dimension())
                || !initial.planHash().equals(clean.planHash())
                || !initial.selectionHash().equals(clean.selectionHash())
                || !selection.selectionHash().equals(clean.selectionHash())) {
            throw new IllegalArgumentException("POST_CLEARANCE_SITE_CHANGED");
        }
        TerrainGradingSpecification grading = plan.gradingSpecification().orElse(null);
        Set<dev.stevecreate.agent.core.model.BlockPos3i> expectedFill = grading == null
                ? Set.of() : Set.copyOf(grading.expectedFillPositions());
        // Only what the first survey found can still be in the way.
        //
        // Clearing's job is to remove the obstacles the survey identified; this rescan's
        // job is to confirm they are gone. It was instead re-deciding what counts as an
        // obstacle, with a wider rule than the survey used — every finding that was not
        // PROTECTED — and the two answers disagreed on the ground the site stands on.
        //
        // The disagreement only appears without a grading specification, because the
        // post-clearance survey filters out everything at or below the graded surface and
        // there is no surface to filter by. A player whose site is already flat gets no
        // grading spec, so their own floor came back as an obstruction: the survey
        // reported zero obstacles, clearing changed nothing, and this refused for a stone
        // block the player had laid deliberately. Three attempts in a row, each naming a
        // different block of the same floor.
        boolean blocking = obstructions(initial, clean, grading, expectedFill)
                .findFirst().isPresent();
        if (grading != null && clean.findings().stream()
                .filter(value -> expectedFill.contains(value.position())
                        && value.blockId().equals(grading.fillBlockId())
                        && value.blockStateFingerprint().equals(grading.fillStateFingerprint()))
                .map(ObstacleFinding::position).distinct().count() != expectedFill.size()) {
            blocking = true;
        }
        if (blocking) {
            // Names what is in the way. This said only that a rescan was required, which
            // is the one thing the player already knew — the site is theirs, they can go
            // and look at the block, and without a position they have to find it by eye
            // across the whole footprint.
            String obstruction = obstructions(initial, clean, grading, expectedFill)
                    .findFirst()
                    .map(value -> value.position().x() + "," + value.position().y() + ","
                            + value.position().z() + ":" + value.blockId())
                    .orElse("the graded floor is incomplete");
            throw new IllegalArgumentException(
                    "POST_CLEARANCE_RESCAN_REQUIRED:" + obstruction);
        }
        List<ObstacleFinding> protectedFindings = clean.findings().stream()
                .filter(value -> value.classification()
                        == ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL)
                .toList();
        String identity = "prepared-site:" + SitePreparationHashes.sha256(
                clean.worldIdentity() + "\n" + clean.dimension() + "\n" + selection.selectionHash()
                        + "\n" + clean.planHash() + "\n" + clean.siteSnapshotHash() + "\n"
                        + plan.taskGraph().graphIdentity() + "\n" + preparedAt);
        return new PreparedConstructionSite(identity, clean.worldIdentity(), clean.dimension(),
                selection.anchor(), selection.facing(), clean.planHash(), clean.siteSnapshotHash(),
                plan.taskGraph().graphIdentity(), salvage, protectedFindings, mutations,
                preparedAt, expiresAt);
    }

    /**
     * What the survey found and clearing did not remove.
     *
     * <p>Clearing's job is to remove the obstacles the survey identified; this rescan's
     * job is to confirm they are gone. It was instead re-deciding what counts as an
     * obstacle, with a wider rule than the survey used — every finding that was not
     * PROTECTED — and the two answers disagreed about the ground the site stands on.
     *
     * <p>The disagreement only shows without a grading specification, because the
     * post-clearance survey filters out everything at or below the graded surface and
     * there is no surface to filter by. A site that is already flat gets no grading spec,
     * so the player's own floor came back as an obstruction: the survey reported zero
     * obstacles, clearing changed nothing, and this refused for a stone block the player
     * had laid on purpose — three times running, naming a different block of the same
     * floor each time.
     *
     * <p>With a grading specification the rule is unchanged: the floor must be exactly
     * what was specified, everywhere it was specified.</p>
     */
    static java.util.stream.Stream<ObstacleFinding> obstructions(
            SiteSurveySnapshot initial,
            SiteSurveySnapshot clean,
            TerrainGradingSpecification grading,
            Set<dev.stevecreate.agent.core.model.BlockPos3i> expectedFill) {
        Set<dev.stevecreate.agent.core.model.BlockPos3i> surveyed = initial.findings().stream()
                .map(ObstacleFinding::position)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return clean.findings().stream().filter(value -> grading == null
                ? surveyed.contains(value.position())
                        && value.classification()
                                != ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL
                : !expectedFill.contains(value.position())
                        || !value.blockId().equals(grading.fillBlockId())
                        || !value.blockStateFingerprint().equals(
                                grading.fillStateFingerprint()));
    }
}
