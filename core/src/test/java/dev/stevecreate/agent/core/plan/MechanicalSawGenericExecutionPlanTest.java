package dev.stevecreate.agent.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import org.junit.jupiter.api.Test;

class MechanicalSawGenericExecutionPlanTest {
    @Test
    void definesFourBoundedStepsAndItemOnlyPhysicalEvidence() {
        MechanicalSawPlan plan = MechanicalSawPlan.at(new BlockPos3i(5, 6, 7));
        var generic = MechanicalSawGenericExecutionPlan.from(plan);
        var rules = MechanicalSawGenericExecutionPlan.verificationRules(plan);

        assertEquals(4, generic.steps().size());
        assertEquals(
                java.util.List.of(
                        GenericExecutionPhase.BUILD,
                        GenericExecutionPhase.POWER,
                        GenericExecutionPhase.FEED_INPUT,
                        GenericExecutionPhase.PROCESS),
                generic.steps().stream().map(value -> value.phase()).toList());
        assertEquals(4, rules.size());
        assertEquals(24, generic.machineGraph().nodes().size());
        assertEquals(8, generic.machineGraph().edges().size());
        assertTrue(generic.processSpec().extensionData().containsValue("forbidden"));
        assertFalse(generic.processSpec().requiredMachineCapabilities().isEmpty());
    }
}
