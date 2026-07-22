package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneRole;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WaterWheelMillstoneEvidenceTest {
    @Test
    void requiresRealPositiveSpeedAtEveryKineticRoleAndCopiesCollections() {
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(new BlockPos3i(0, 80, 0));
        EnumMap<WaterWheelMillstoneRole, Double> speeds = validSpeeds();
        WaterWheelMillstoneEvidence evidence = evidence(plan, speeds);

        speeds.clear();
        assertThat(evidence.observedSpeedRpm()).hasSize(4);
        assertThatThrownBy(() -> evidence.verifiedPlacements().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.observedSpeedRpm().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsACompletionFlagWithoutPhysicalPowerEvidence() {
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(new BlockPos3i(0, 80, 0));
        EnumMap<WaterWheelMillstoneRole, Double> speeds = validSpeeds();
        speeds.put(WaterWheelMillstoneRole.MILLSTONE, 0.0);

        assertThatThrownBy(() -> evidence(plan, speeds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MILLSTONE");
    }

    private static WaterWheelMillstoneEvidence evidence(
            WaterWheelMillstonePlan plan,
            Map<WaterWheelMillstoneRole, Double> speeds) {
        return new WaterWheelMillstoneEvidence(
                100,
                400,
                new RuntimeFingerprint("1.20.1", "forge", "47.4.10", Map.of("create", "6.0.6-150"), "test", 1),
                ResourceId.parse("minecraft:overworld"),
                plan.origin(),
                plan.placements(),
                speeds,
                ResourceId.parse("create:milling/cobblestone"),
                ResourceId.parse("create:milling"),
                250,
                ResourceId.parse("minecraft:cobblestone"),
                1,
                ResourceId.parse("minecraft:gravel"),
                1);
    }

    private static EnumMap<WaterWheelMillstoneRole, Double> validSpeeds() {
        EnumMap<WaterWheelMillstoneRole, Double> speeds = new EnumMap<>(WaterWheelMillstoneRole.class);
        speeds.put(WaterWheelMillstoneRole.WATER_WHEEL, 8.0);
        speeds.put(WaterWheelMillstoneRole.GEARBOX, 8.0);
        speeds.put(WaterWheelMillstoneRole.VERTICAL_SHAFT, 8.0);
        speeds.put(WaterWheelMillstoneRole.MILLSTONE, 8.0);
        return speeds;
    }
}
