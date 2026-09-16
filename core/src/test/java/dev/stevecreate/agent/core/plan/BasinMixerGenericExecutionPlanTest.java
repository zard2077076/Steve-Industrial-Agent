package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import org.junit.jupiter.api.Test;

class BasinMixerGenericExecutionPlanTest {
    @Test
    void exposesBoundedBuildPowerFeedAndRealProcessSteps() {
        BasinMixerPlan physical =
                BasinMixerPlan.andesiteAlloy(new BlockPos3i(0, 64, 0));
        var generic = BasinMixerGenericExecutionPlan.from(physical);

        assertThat(generic.steps())
                .extracting(value -> value.phase())
                .containsExactly(
                        GenericExecutionPhase.BUILD,
                        GenericExecutionPhase.POWER,
                        GenericExecutionPhase.FEED_INPUT,
                        GenericExecutionPhase.PROCESS);
        assertThat(generic.steps())
                .allSatisfy(value -> assertThat(
                        value.action().handlerId()).isEqualTo(
                        BasinMixerGenericExecutionPlan.ACTION_HANDLER_ID));
    }

    @Test
    void graphCarriesItemsKineticPowerAndTypedHeat() {
        var graph = BasinMixerGenericExecutionPlan.from(
                BasinMixerPlan.brass(new BlockPos3i(0, 64, 0)))
                .machineGraph();

        assertThat(graph.edges().values())
                .extracting(value -> value.resourceType())
                .contains(
                        GenericResourceType.ITEM,
                        GenericResourceType.ROTATIONAL_POWER,
                        GenericResourceType.HEAT);
        assertThat(graph.nodes().values())
                .extracting(value -> value.configuration().values())
                .anySatisfy(values -> assertThat(values)
                        .contains("heated"));
    }

    @Test
    void processRuleRequiresMixerHeatAndStoredOutput() {
        BasinMixerPlan plan =
                BasinMixerPlan.andesiteAlloy(new BlockPos3i(0, 64, 0));
        var rule = BasinMixerGenericExecutionPlan
                .verificationRules(plan)
                .get(BasinMixerGenericExecutionPlan.PROCESS_STEP_ID);

        assertThat(rule.requiredEvidence())
                .extracting(value -> value.kind())
                .contains(
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        VerificationEvidenceKind.OUTPUT_STORED,
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE);
        assertThat(rule.requiredEvidence())
                .extracting(value -> value.requirementId())
                .contains(
                        BasinMixerGenericExecutionPlan
                                .BASIN_CONTENTS_OBSERVED,
                        BasinMixerGenericExecutionPlan
                                .MIXER_CYCLE_OBSERVED,
                        BasinMixerGenericExecutionPlan.HEAT_OBSERVED,
                        BasinMixerGenericExecutionPlan
                                .CHEST_OUTPUT_OBSERVED);
    }
}
