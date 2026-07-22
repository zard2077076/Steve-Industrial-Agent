package dev.stevecreate.agent.core.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BasicPlacementFeasibilityTest {
    @Test
    void acceptsEveryC03AndC04QuarterTurnWhenAllFinalTargetsAreLoadedReplaceableAndUnprotected() {
        BlockPos3i anchor = new BlockPos3i(100, 64, -40);

        for (QuarterTurn turn : QuarterTurn.values()) {
            WaterWheelMillstonePlan c03 = WaterWheelMillstonePlan.at(anchor, turn);
            BeltPressPlan c04 = BeltPressPlan.at(anchor, turn);

            PlacementFeasibilityReport c03Report = BasicPlacementFeasibility.evaluate(
                    c03.placementTargets(), clearObservations(c03.placementTargets()));
            PlacementFeasibilityReport c04Report = BasicPlacementFeasibility.evaluate(
                    c04.placementTargets(), clearObservations(c04.placementTargets()));

            assertThat(c03Report.feasible()).isTrue();
            assertThat(c03Report.targetCount()).isEqualTo(17);
            assertThat(c04Report.feasible()).isTrue();
            assertThat(c04Report.targetCount()).isEqualTo(8);
        }
    }

    @Test
    void reportsEveryConflictPositionAndAllFourTypedKindsInDeterministicOrder() {
        BlockPos3i unloaded = new BlockPos3i(0, 64, 0);
        BlockPos3i occupied = new BlockPos3i(1, 64, 0);
        BlockPos3i protectedPosition = new BlockPos3i(2, 64, 0);
        BlockPos3i internal = new BlockPos3i(3, 64, 0);
        List<PlacementTarget> targets = List.of(
                target("test:role/unloaded", unloaded),
                target("test:role/occupied", occupied),
                target("test:role/protected", protectedPosition),
                target("test:role/internal_a", internal),
                target("test:role/internal_b", internal));
        List<PlacementSiteObservation> observations = List.of(
                observation(unloaded, false, false, false),
                observation(occupied, true, false, false),
                observation(protectedPosition, true, true, true),
                observation(internal, true, true, false));

        PlacementFeasibilityReport report =
                BasicPlacementFeasibility.evaluate(targets, observations);

        assertThat(report.feasible()).isFalse();
        assertThat(report.targetCount()).isEqualTo(5);
        assertThat(report.conflictingPositions())
                .containsExactly(unloaded, occupied, protectedPosition, internal);
        assertThat(report.conflicts()).extracting(PlacementConflict::kind).containsExactly(
                PlacementConflictKind.UNLOADED,
                PlacementConflictKind.NOT_REPLACEABLE,
                PlacementConflictKind.PROTECTED,
                PlacementConflictKind.INTERNAL_ROLE_CONFLICT);
        assertThat(report.conflicts().get(3).roleIds()).containsExactly(
                id("test:role/internal_a"), id("test:role/internal_b"));
    }

    @Test
    void reportsEveryKnownConflictAtOnePositionWithoutInventingReplaceabilityForUnloadedState() {
        BlockPos3i position = new BlockPos3i(4, 70, 8);
        List<PlacementTarget> targets = List.of(
                target("test:role/a", position),
                target("test:role/b", position));

        PlacementFeasibilityReport report = BasicPlacementFeasibility.evaluate(
                targets,
                List.of(observation(position, false, false, true)));

        assertThat(report.conflicts()).extracting(PlacementConflict::kind).containsExactly(
                PlacementConflictKind.UNLOADED,
                PlacementConflictKind.PROTECTED,
                PlacementConflictKind.INTERNAL_ROLE_CONFLICT);
        assertThat(report.conflicts()).noneMatch(
                conflict -> conflict.kind() == PlacementConflictKind.NOT_REPLACEABLE);
    }

    @Test
    void requiresExactUniqueObservationCoverageAndConsistentLoadedState() {
        PlacementTarget target = target("test:role/one", new BlockPos3i(0, 0, 0));
        PlacementSiteObservation observation = observation(target.position(), true, true, false);

        assertThatThrownBy(() -> BasicPlacementFeasibility.evaluate(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BasicPlacementFeasibility.evaluate(List.of(target), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BasicPlacementFeasibility.evaluate(
                        List.of(target), List.of(observation, observation)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BasicPlacementFeasibility.evaluate(
                        List.of(target),
                        List.of(observation, observation(new BlockPos3i(1, 0, 0), true, true, false))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> observation(target.position(), false, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsTargetsAndDefensivelyCopiesReports() {
        List<PlacementTarget> tooMany = new ArrayList<>();
        for (int index = 0; index <= BasicPlacementFeasibility.MAX_TARGETS; index++) {
            tooMany.add(target("test:role/r" + index, new BlockPos3i(index, 0, 0)));
        }
        assertThatThrownBy(() -> BasicPlacementFeasibility.evaluate(tooMany, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        PlacementTarget oneTarget = target("test:role/one", new BlockPos3i(0, 0, 0));
        List<PlacementSiteObservation> tooManyObservations = new ArrayList<>();
        for (int index = 0; index <= BasicPlacementFeasibility.MAX_TARGETS; index++) {
            tooManyObservations.add(observation(new BlockPos3i(index, 0, 0), true, true, false));
        }
        assertThatThrownBy(() -> BasicPlacementFeasibility.evaluate(
                        List.of(oneTarget), tooManyObservations))
                .isInstanceOf(IllegalArgumentException.class);

        List<PlacementConflict> mutable = new ArrayList<>();
        mutable.add(new PlacementConflict(
                new BlockPos3i(0, 0, 0),
                PlacementConflictKind.PROTECTED,
                List.of(id("test:role/one"))));
        PlacementFeasibilityReport report = new PlacementFeasibilityReport(1, mutable);
        mutable.clear();

        assertThat(report.conflicts()).hasSize(1);
        assertThatThrownBy(() -> report.conflicts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<PlacementSiteObservation> clearObservations(List<PlacementTarget> targets) {
        return targets.stream()
                .map(target -> observation(target.position(), true, true, false))
                .toList();
    }

    private static PlacementTarget target(String roleId, BlockPos3i position) {
        return new PlacementTarget(id(roleId), position);
    }

    private static PlacementSiteObservation observation(
            BlockPos3i position,
            boolean loaded,
            boolean replaceable,
            boolean protectedPosition) {
        return new PlacementSiteObservation(position, loaded, replaceable, protectedPosition);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
