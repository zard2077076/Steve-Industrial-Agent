package dev.stevecreate.agent.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import org.junit.jupiter.api.Test;

class CreateTopologyGraphTest {
    @Test
    void representsTheC03PowerPathMillstoneAndItemBoundaries() {
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(new BlockPos3i(0, 0, 0));
        UnifiedMachineGraph graph = WaterWheelMillstoneGenericExecutionPlan.from(plan).machineGraph();
        MachineNode waterWheel = graph.node(
                WaterWheelMillstoneGenericExecutionPlan.WATER_WHEEL_NODE_ID);
        MachineNode millstone = graph.node(
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID);

        assertThat(graph.nodes()).hasSize(6);
        assertThat(graph.ports()).hasSize(10);
        assertThat(graph.edges().values())
                .filteredOn(edge -> edge.resourceType() == GenericResourceType.ROTATIONAL_POWER)
                .hasSize(3);
        assertThat(graph.edges().values())
                .filteredOn(edge -> edge.resourceType() == GenericResourceType.ITEM)
                .hasSize(2);
        assertThat(graph.node(millstone.id()).configuration())
                .containsEntry("recipe", "create:milling/cobblestone");
        assertThat(graph.node(waterWheel.id()).implementationId())
                .isEqualTo(id("create:water_wheel"));
    }

    @Test
    void representsTheC04PowerBeltsPressFunnelChestAndItemFlow() {
        BeltPressPlan plan = BeltPressPlan.at(new BlockPos3i(0, 0, 0));
        UnifiedMachineGraph graph = BeltPressGenericExecutionPlan.from(plan).machineGraph();
        MachineNode press = graph.node(BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID);
        MachineNode chest = graph.node(BeltPressGenericExecutionPlan.OUTPUT_CHEST_NODE_ID);

        assertThat(graph.nodes()).hasSize(27);
        assertThat(graph.ports()).hasSize(28);
        assertThat(graph.edges()).hasSize(14);
        assertThat(graph.nodes().values())
                .filteredOn(node -> node.implementationId().equals(id("create:belt")))
                .hasSize(3);
        assertThat(graph.edges().values())
                .filteredOn(edge -> edge.resourceType() == GenericResourceType.ROTATIONAL_POWER)
                .hasSize(8);
        assertThat(graph.edges().values())
                .filteredOn(edge -> edge.resourceType() == GenericResourceType.ITEM)
                .hasSize(6);
        assertThat(graph.node(press.id()).configuration())
                .containsEntry("recipe", "create:pressing/iron_ingot")
                .containsEntry("cycle_ticks", "240");
        assertThat(graph.node(chest.id()).modNamespace()).isEqualTo("minecraft");
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
