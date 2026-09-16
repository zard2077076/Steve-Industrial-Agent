package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import org.junit.jupiter.api.Test;

class FanProcessingGenericExecutionPlanTest {
    @Test
    void definesOneSharedBoundedRunnerWithLiveAirflowMediumAndDwellEvidence() {
        FanProcessingPlan plan = FanProcessingPlan.forProcess(
                PlanAnchor.at(new BlockPos3i(0, 64, 0)),
                new FanProcessingSpec(
                        ResourceId.parse("create:haunting/blackstone"),
                        FanProcessingMode.HAUNTING.recipeType(),
                        ResourceId.parse("minecraft:cobblestone"), 1,
                        ResourceId.parse("minecraft:blackstone"), 1,
                        400, 1_200));
        var generic = FanProcessingGenericExecutionPlan.from(plan);
        assertThat(generic.steps()).extracting(value -> value.phase()).containsExactly(
                GenericExecutionPhase.BUILD,
                GenericExecutionPhase.POWER,
                GenericExecutionPhase.FEED_INPUT,
                GenericExecutionPhase.PROCESS);
        assertThat(generic.machineGraph().nodes()).hasSize(26);
        assertThat(generic.machineGraph().edges()).hasSize(7);
        assertThat(FanProcessingGenericExecutionPlan.verificationRules(plan)
                .get(FanProcessingGenericExecutionPlan.PROCESS_STEP_ID)
                .requiredEvidence()).hasSize(7);
        assertThat(plan.process().genericSpec().extensionData().values())
                .contains("haunting", "hybrid_direct_or_unsupported", "3");
    }
}
