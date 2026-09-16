package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BasinPressGenericExecutionPlanTest {
    @Test
    void exposesBoundedBuildPowerFeedAndRealProcessSteps() {
        BasinPressPlan physical =
                BasinPressPlan.blazeCakeBase(new BlockPos3i(0, 64, 0));
        var generic = BasinPressGenericExecutionPlan.from(physical);

        assertThat(generic.steps())
                .extracting(value -> value.phase())
                .containsExactly(
                        GenericExecutionPhase.BUILD,
                        GenericExecutionPhase.POWER,
                        GenericExecutionPhase.FEED_INPUT,
                        GenericExecutionPhase.PROCESS);
        assertThat(generic.steps())
                .allMatch(value -> value.action().handlerId().equals(
                        BasinPressGenericExecutionPlan.ACTION_HANDLER_ID));
        assertThat(generic.processSpec().inputs()).hasSize(3);
    }

    @Test
    void graphConnectsItemsAndKineticPowerWithoutBeltPressing() {
        var graph = BasinPressGenericExecutionPlan.from(
                BasinPressPlan.blazeCakeBase(new BlockPos3i(0, 64, 0)))
                .machineGraph();

        assertThat(graph.edges().values())
                .extracting(value -> value.resourceType())
                .contains(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER);
        assertThat(graph.nodes().values())
                .extracting(value -> value.implementationId().toString())
                .contains("create:basin", "create:mechanical_press")
                .doesNotContain("create:belt");
    }

    @Test
    void graphAddsExactFluidBoundaryOnlyWhenDeclared() {
        CompactingProcessSpec spec = new CompactingProcessSpec(
                id("create:compacting/granite_from_flint"),
                List.of(
                        new ProcessResource(id("minecraft:flint"), GenericResourceType.ITEM, 2),
                        new ProcessResource(id("minecraft:red_sand"), GenericResourceType.ITEM, 1),
                        new ProcessResource(id("minecraft:lava"), GenericResourceType.FLUID, 100)),
                id("minecraft:granite"), 1,
                BasinHeatMode.NONE, 40, 200);
        var graph = BasinPressGenericExecutionPlan.from(BasinPressPlan.at(
                new BlockPos3i(0, 64, 0),
                dev.stevecreate.agent.core.model.QuarterTurn.ZERO,
                spec)).machineGraph();

        assertThat(graph.edges().values())
                .extracting(value -> value.resourceType())
                .contains(GenericResourceType.FLUID);
        assertThat(graph.nodes().values())
                .extracting(value -> value.implementationId().toString())
                .contains("steve_industrial:fluid_source");
    }

    @Test
    void processRuleRequiresBasinPressCycleAndStoredOutput() {
        BasinPressPlan plan =
                BasinPressPlan.blazeCakeBase(new BlockPos3i(0, 64, 0));
        var rule = BasinPressGenericExecutionPlan.verificationRules(plan)
                .get(BasinPressGenericExecutionPlan.PROCESS_STEP_ID);

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
                .containsAll(Set.of(
                        BasinPressGenericExecutionPlan.BASIN_CONTENTS_OBSERVED,
                        BasinPressGenericExecutionPlan.PRESS_CYCLE_OBSERVED,
                        BasinPressGenericExecutionPlan.CHEST_OUTPUT_OBSERVED));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
