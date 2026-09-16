package dev.stevecreate.agent.adapter.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.MechanicalSawPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawRole;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MechanicalSawEvidenceTest {
    @Test
    void requiresRealItemProcessingAndWorldCuttingRefusal() {
        MechanicalSawPlan plan = MechanicalSawPlan.at(new BlockPos3i(0, 0, 0));
        MechanicalSawEvidence evidence = new MechanicalSawEvidence(
                10, 60, runtime(), ResourceId.parse("minecraft:overworld"),
                plan.origin(), plan.placements(),
                kineticSpeeds(),
                plan.process().recipeId(), plan.process().recipeType(), 50,
                plan.process().inputItem(), 1,
                plan.process().expectedOutputItem(), 1,
                true, true, true, true);
        assertEquals(1, evidence.observedOutputCount());

        assertThrows(IllegalArgumentException.class, () -> new MechanicalSawEvidence(
                10, 60, runtime(), ResourceId.parse("minecraft:overworld"),
                plan.origin(), plan.placements(),
                kineticSpeeds(),
                plan.process().recipeId(), plan.process().recipeType(), 50,
                plan.process().inputItem(), 1,
                plan.process().expectedOutputItem(), 1,
                true, true, true, false));
    }

    private static Map<MechanicalSawRole, Double> kineticSpeeds() {
        EnumMap<MechanicalSawRole, Double> values = new EnumMap<>(MechanicalSawRole.class);
        for (MechanicalSawRole role : MechanicalSawRole.values()) {
            if (role.isKinetic()) values.put(role, 8.0D);
        }
        return Map.copyOf(values);
    }

    private static RuntimeFingerprint runtime() {
        return new RuntimeFingerprint(
                "1.20.1", "forge", "47.4.10", Map.of("create", "6.0.6-150"),
                "steve_industrial:create_runtime_1_20_1_6_0_6", 1);
    }
}
