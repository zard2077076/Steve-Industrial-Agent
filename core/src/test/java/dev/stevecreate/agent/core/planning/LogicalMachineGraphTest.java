package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LogicalMachineGraphTest {
    @Test
    void representsRawProcessTargetFlowAndExplicitPowerWithoutPhysicalFields() {
        LogicalGraphNode raw = boundary("planning:raw_0001", LogicalNodeKind.RAW_RESOURCE_SOURCE);
        LogicalGraphNode process = new LogicalGraphNode(
                id("planning:process_0001"),
                LogicalNodeKind.PROCESS,
                Optional.of(id("planning:step_0001")),
                Set.of(id("industrial:milling")),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 8, true)));
        LogicalGraphNode target = boundary("planning:target", LogicalNodeKind.TARGET_SINK);
        LogicalPortRequirement supply = port(
                "planning:raw_0001_out", raw.id(), PortMode.OUTPUT,
                LogicalPortRole.EXTERNAL_SUPPLY, 2);
        LogicalPortRequirement input = port(
                "planning:process_0001_in", process.id(), PortMode.INPUT,
                LogicalPortRole.PROCESS_INPUT, 2);
        LogicalPortRequirement output = port(
                "planning:process_0001_out", process.id(), PortMode.OUTPUT,
                LogicalPortRole.PROCESS_OUTPUT, 2);
        LogicalPortRequirement demand = port(
                "planning:target_in", target.id(), PortMode.INPUT,
                LogicalPortRole.TARGET_DEMAND, 2);
        LogicalResourceEdge rawEdge = edge(
                "planning:edge_0001", supply.id(), input.id(), LogicalEdgeKind.RAW_INPUT, 2);
        LogicalResourceEdge targetEdge = edge(
                "planning:edge_0002", output.id(), demand.id(), LogicalEdgeKind.TARGET_OUTPUT, 2);

        LogicalMachineGraph graph = new LogicalMachineGraph(
                id("planning:logical_candidate"),
                List.of(raw, process, target),
                List.of(supply, input, output, demand),
                List.of(rawEdge, targetEdge));

        assertThat(graph.nodes()).containsOnlyKeys(raw.id(), process.id(), target.id());
        assertThat(graph.node(process.id()).requiredResources()).containsExactly(
                new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 8, true));
        assertThat(graph.edges()).containsOnlyKeys(rawEdge.id(), targetEdge.id());
        assertThat(LogicalGraphNode.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("relativePosition", "position", "orientation", "implementationId");
        assertThat(LogicalPortRequirement.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("side", "capacity", "adapterPortId");
    }

    @Test
    void enforcesProcessAndBoundaryNodeContracts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalGraphNode(
                id("planning:process"), LogicalNodeKind.PROCESS, Optional.empty(),
                Set.of(id("industrial:milling")), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalGraphNode(
                id("planning:process"), LogicalNodeKind.PROCESS,
                Optional.of(id("planning:step")), Set.of(), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalGraphNode(
                id("planning:raw"), LogicalNodeKind.RAW_RESOURCE_SOURCE, Optional.empty(),
                Set.of(id("industrial:fake")), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalGraphNode(
                id("planning:process"), LogicalNodeKind.PROCESS,
                Optional.of(id("planning:step")), Set.of(id("industrial:milling")),
                Set.of(
                        new CapabilityResourceRequirement(
                                GenericResourceType.ROTATIONAL_POWER, 1, true),
                        new CapabilityResourceRequirement(
                                GenericResourceType.ROTATIONAL_POWER, 2, false))));
    }

    @Test
    void rejectsUnknownOwnersResourceMismatchAndWrongPortModes() {
        LogicalGraphNode process = process("planning:process_0001", "planning:step_0001");
        LogicalGraphNode target = boundary("planning:target", LogicalNodeKind.TARGET_SINK);
        LogicalPortRequirement orphan = port(
                "planning:orphan", id("planning:missing"), PortMode.INPUT,
                LogicalPortRole.PROCESS_INPUT, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalMachineGraph(
                id("planning:orphan_graph"), List.of(process), List.of(orphan), List.of()));

        LogicalPortRequirement output = port(
                "planning:output", process.id(), PortMode.OUTPUT,
                LogicalPortRole.PROCESS_OUTPUT, 1);
        LogicalPortRequirement demand = new LogicalPortRequirement(
                id("planning:demand"), target.id(), id("fixture:item"), GenericResourceType.FLUID,
                PortMode.INPUT, LogicalPortRole.TARGET_DEMAND, 1);
        LogicalResourceEdge mismatch = edge(
                "planning:mismatch", output.id(), demand.id(), LogicalEdgeKind.TARGET_OUTPUT, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalMachineGraph(
                id("planning:mismatch_graph"), List.of(process, target),
                List.of(output, demand), List.of(mismatch)));

        LogicalPortRequirement differentItem = new LogicalPortRequirement(
                id("planning:different_item"), target.id(), id("fixture:other_item"),
                GenericResourceType.ITEM, PortMode.INPUT, LogicalPortRole.TARGET_DEMAND, 1);
        LogicalResourceEdge identityMismatch = edge(
                "planning:identity_mismatch", output.id(), differentItem.id(),
                LogicalEdgeKind.TARGET_OUTPUT, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalMachineGraph(
                id("planning:identity_mismatch_graph"), List.of(process, target),
                List.of(output, differentItem), List.of(identityMismatch)));

        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalPortRequirement(
                id("planning:wrong_mode"), process.id(), id("fixture:item"), GenericResourceType.ITEM,
                PortMode.OUTPUT, LogicalPortRole.PROCESS_INPUT, 1));
    }

    @Test
    void enforcesEdgeKindTopologyWithoutFabricatingPhysicalConnections() {
        LogicalGraphNode raw = boundary("planning:raw", LogicalNodeKind.RAW_RESOURCE_SOURCE);
        LogicalGraphNode first = process("planning:first", "planning:step_0001");
        LogicalGraphNode second = process("planning:second", "planning:step_0002");
        LogicalPortRequirement rawOut = port(
                "planning:raw_out", raw.id(), PortMode.OUTPUT,
                LogicalPortRole.EXTERNAL_SUPPLY, 1);
        LogicalPortRequirement firstOut = port(
                "planning:first_out", first.id(), PortMode.OUTPUT,
                LogicalPortRole.PROCESS_OUTPUT, 1);
        LogicalPortRequirement secondIn = port(
                "planning:second_in", second.id(), PortMode.INPUT,
                LogicalPortRole.PROCESS_INPUT, 1);

        LogicalResourceEdge wrongRaw = edge(
                "planning:wrong_raw", firstOut.id(), secondIn.id(), LogicalEdgeKind.RAW_INPUT, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalMachineGraph(
                id("planning:wrong_raw_graph"), List.of(raw, first, second),
                List.of(rawOut, firstOut, secondIn), List.of(wrongRaw)));

        LogicalResourceEdge intermediate = edge(
                "planning:intermediate", firstOut.id(), secondIn.id(),
                LogicalEdgeKind.INTERMEDIATE, 1);
        LogicalMachineGraph graph = new LogicalMachineGraph(
                id("planning:valid_intermediate"), List.of(first, second),
                List.of(firstOut, secondIn), List.of(intermediate));
        assertThat(graph.edge(intermediate.id()).amount()).isEqualTo(1);
    }

    @Test
    void defensivelyCopiesAndBoundsLogicalCollections() {
        LogicalGraphNode process = process("planning:process", "planning:step");
        List<LogicalGraphNode> nodes = new ArrayList<>(List.of(process));
        LogicalMachineGraph graph = new LogicalMachineGraph(
                id("planning:graph"), nodes, List.of(), List.of());
        nodes.clear();

        assertThat(graph.nodes()).containsOnlyKeys(process.id());
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> graph.nodes().clear());
        assertThatIllegalArgumentException().isThrownBy(() -> new LogicalPortRequirement(
                id("planning:too_much"), process.id(), id("fixture:item"), GenericResourceType.ITEM,
                PortMode.INPUT, LogicalPortRole.PROCESS_INPUT,
                dev.stevecreate.agent.core.process.ProcessResource.MAX_AMOUNT + 1L));
    }

    private static LogicalGraphNode process(String nodeId, String stepId) {
        return new LogicalGraphNode(
                id(nodeId), LogicalNodeKind.PROCESS, Optional.of(id(stepId)),
                Set.of(id("industrial:processing")), Set.of());
    }

    private static LogicalGraphNode boundary(String nodeId, LogicalNodeKind kind) {
        return new LogicalGraphNode(id(nodeId), kind, Optional.empty(), Set.of(), Set.of());
    }

    private static LogicalPortRequirement port(
            String portId,
            ResourceId nodeId,
            PortMode mode,
            LogicalPortRole role,
            long amount) {
        return new LogicalPortRequirement(
                id(portId), nodeId, id("fixture:item"), GenericResourceType.ITEM,
                mode, role, amount);
    }

    private static LogicalResourceEdge edge(
            String edgeId,
            ResourceId source,
            ResourceId target,
            LogicalEdgeKind kind,
            long amount) {
        return new LogicalResourceEdge(
                id(edgeId), source, target, id("fixture:item"),
                GenericResourceType.ITEM, amount, kind);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
