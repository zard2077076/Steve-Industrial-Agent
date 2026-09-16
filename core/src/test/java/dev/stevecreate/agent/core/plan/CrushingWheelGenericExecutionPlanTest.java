package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CrushingWheelGenericExecutionPlanTest {
    @Test
    void buildsARealTwoWheelPowerAndHopperChestItemGraph() {
        CrushingWheelPlan physical = CrushingWheelPlan.at(new BlockPos3i(40, 70, -20));
        var plan = CrushingWheelGenericExecutionPlan.from(physical);
        var graph = plan.machineGraph();

        assertThat(graph.nodes()).hasSize(19);
        assertThat(graph.edges()).hasSize(5);
        assertThat(graph.edges().values())
                .extracting(value -> value.resourceType())
                .containsExactlyInAnyOrder(
                        GenericResourceType.ROTATIONAL_POWER,
                        GenericResourceType.ROTATIONAL_POWER,
                        GenericResourceType.ITEM,
                        GenericResourceType.ITEM,
                        GenericResourceType.ITEM);
        assertThat(graph.node(CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID)
                .relativePosition()).isEqualTo(new BlockPos3i(1, 3, 0));
        assertThat(graph.node(CrushingWheelGenericExecutionPlan.OUTPUT_CHEST_NODE_ID)
                .relativePosition()).isEqualTo(new BlockPos3i(1, 0, 0));
    }

    @Test
    void definesFourBoundedStepsAndEveryPhysicalCompletionRequirement() {
        CrushingWheelPlan physical = CrushingWheelPlan.at(new BlockPos3i(0, 64, 0));
        var plan = CrushingWheelGenericExecutionPlan.from(physical);
        var rules = CrushingWheelGenericExecutionPlan.verificationRules(physical);

        assertThat(plan.steps())
                .extracting(value -> value.phase())
                .containsExactly(
                        GenericExecutionPhase.BUILD,
                        GenericExecutionPhase.POWER,
                        GenericExecutionPhase.FEED_INPUT,
                        GenericExecutionPhase.PROCESS);
        assertThat(rules.keySet()).containsExactlyInAnyOrder(
                CrushingWheelGenericExecutionPlan.BUILD_STEP_ID,
                CrushingWheelGenericExecutionPlan.POWER_STEP_ID,
                CrushingWheelGenericExecutionPlan.FEED_STEP_ID,
                CrushingWheelGenericExecutionPlan.PROCESS_STEP_ID);
        assertThat(plan.processSpec().requiredCompletionEvidence()).containsExactlyInAnyOrderElementsOf(Set.of(
                CrushingWheelGenericExecutionPlan.INPUT_CONSUMED_REQUIREMENT,
                CrushingWheelGenericExecutionPlan.PROCESS_COMPLETED_REQUIREMENT,
                CrushingWheelGenericExecutionPlan.OUTPUT_PRODUCED_REQUIREMENT,
                CrushingWheelGenericExecutionPlan.CONTROLLER_VALID_REQUIREMENT,
                CrushingWheelGenericExecutionPlan.WHEEL_DIRECTIONS_REQUIREMENT,
                CrushingWheelGenericExecutionPlan.PROBABILISTIC_OUTPUTS_REQUIREMENT,
                CrushingWheelGenericExecutionPlan.CHEST_OUTPUT_REQUIREMENT));
    }
}
