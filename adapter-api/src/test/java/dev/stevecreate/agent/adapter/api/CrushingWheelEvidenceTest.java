package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelRole;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrushingWheelEvidenceTest {
    @Test
    void requiresEqualMagnitudeOpposedWheelsAndPreservesProbabilityEvidence() {
        CrushingWheelEvidence evidence = evidence(speeds(16, -16));

        assertThat(evidence.observedSignedSpeedRpm()
                .get(CrushingWheelRole.LEFT_WHEEL)).isEqualTo(16.0D);
        assertThat(evidence.observedSignedSpeedRpm()
                .get(CrushingWheelRole.RIGHT_WHEEL)).isEqualTo(-16.0D);
        assertThat(evidence.observedByproductCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        id("minecraft:flint"), 2,
                        id("minecraft:clay_ball"), 0));
        assertThat(evidence.probabilityPerMillion())
                .containsEntry(id("minecraft:flint"), 100_000)
                .containsEntry(id("minecraft:clay_ball"), 50_000);
    }

    @Test
    void rejectsSameDirectionWheelsAndIncompletePhysicalCompletion() {
        assertThatThrownBy(() -> evidence(speeds(16, 16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opposed");
    }

    private static CrushingWheelEvidence evidence(
            Map<CrushingWheelRole, Double> speeds) {
        CrushingWheelPlan plan = CrushingWheelPlan.at(new BlockPos3i(10, 64, 10));
        return new CrushingWheelEvidence(
                100,
                400,
                new RuntimeFingerprint(
                        "1.20.1",
                        "forge",
                        "47.4.10",
                        Map.of("create", "6.0.6-150"),
                        "test",
                        1),
                id("minecraft:overworld"),
                plan.origin(),
                plan.placements(),
                speeds,
                totals(16_384),
                totals(128),
                id("create:crushing/gravel"),
                id("create:crushing"),
                250,
                id("minecraft:gravel"),
                16,
                id("minecraft:sand"),
                16,
                Map.of(id("minecraft:flint"), 2, id("minecraft:clay_ball"), 0),
                Map.of(id("minecraft:flint"), 100_000, id("minecraft:clay_ball"), 50_000),
                true,
                true,
                true);
    }

    private static Map<CrushingWheelRole, Double> speeds(
            double left,
            double right) {
        EnumMap<CrushingWheelRole, Double> values =
                new EnumMap<>(CrushingWheelRole.class);
        values.put(CrushingWheelRole.LEFT_DRIVE, left);
        values.put(CrushingWheelRole.RIGHT_DRIVE, right);
        values.put(CrushingWheelRole.LEFT_WHEEL, left);
        values.put(CrushingWheelRole.RIGHT_WHEEL, right);
        return values;
    }

    private static Map<CrushingWheelRole, Double> totals(double value) {
        EnumMap<CrushingWheelRole, Double> values =
                new EnumMap<>(CrushingWheelRole.class);
        values.put(CrushingWheelRole.LEFT_DRIVE, value);
        values.put(CrushingWheelRole.RIGHT_DRIVE, value);
        values.put(CrushingWheelRole.LEFT_WHEEL, value);
        values.put(CrushingWheelRole.RIGHT_WHEEL, value);
        return values;
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
