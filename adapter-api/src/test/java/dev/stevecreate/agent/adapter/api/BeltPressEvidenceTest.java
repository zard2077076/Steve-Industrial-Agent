package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BeltPressEvidenceTest {
    @Test
    void requiresEveryKineticRoleAndCopiesPhysicalEvidenceCollections() {
        BeltPressPlan plan = BeltPressPlan.at(new BlockPos3i(0, 80, 0));
        EnumMap<BeltPressRole, Double> speeds = validSpeeds();
        BeltPressEvidence evidence = evidence(plan, speeds, true);

        speeds.clear();
        assertThat(evidence.observedSpeedRpm()).hasSize(6);
        assertThatThrownBy(() -> evidence.verifiedPlacements().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.observedSpeedRpm().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCompletionWithoutLivePressPowerOrObservedPressCycle() {
        BeltPressPlan plan = BeltPressPlan.at(new BlockPos3i(0, 80, 0));
        EnumMap<BeltPressRole, Double> speeds = validSpeeds();
        speeds.put(BeltPressRole.MECHANICAL_PRESS, 0.0);

        assertThatThrownBy(() -> evidence(plan, speeds, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MECHANICAL_PRESS");
        assertThatThrownBy(() -> evidence(plan, validSpeeds(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("press cycle");
    }

    @Test
    void rejectsSpeedEvidenceForNonKineticRoles() {
        BeltPressPlan plan = BeltPressPlan.at(new BlockPos3i(0, 80, 0));
        EnumMap<BeltPressRole, Double> speeds = validSpeeds();
        speeds.put(BeltPressRole.OUTPUT_CHEST, 16.0);

        assertThatThrownBy(() -> evidence(plan, speeds, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly the C-04 kinetic roles");
    }

    private static BeltPressEvidence evidence(
            BeltPressPlan plan,
            Map<BeltPressRole, Double> speeds,
            boolean pressCycleObserved) {
        return new BeltPressEvidence(
                100,
                500,
                new RuntimeFingerprint("1.20.1", "forge", "47.4.10", Map.of("create", "6.0.6-150"), "test", 1),
                ResourceId.parse("minecraft:overworld"),
                plan.origin(),
                plan.finalPlacements(),
                speeds,
                ResourceId.parse("create:pressing/iron_ingot"),
                ResourceId.parse("create:pressing"),
                0,
                240,
                ResourceId.parse("minecraft:iron_ingot"),
                1,
                ResourceId.parse("create:iron_sheet"),
                1,
                true,
                pressCycleObserved,
                true);
    }

    private static EnumMap<BeltPressRole, Double> validSpeeds() {
        EnumMap<BeltPressRole, Double> speeds = new EnumMap<>(BeltPressRole.class);
        for (BeltPressRole role : BeltPressRole.values()) {
            if (role.isKinetic()) {
                speeds.put(role, 16.0);
            }
        }
        return speeds;
    }
}
