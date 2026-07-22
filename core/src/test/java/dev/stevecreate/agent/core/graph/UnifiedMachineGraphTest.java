package dev.stevecreate.agent.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnifiedMachineGraphTest {
    @Test
    void constructsAnImmutableTypedGraphFromDefensiveCopies() {
        Set<ResourceId> capabilities = new HashSet<>(Set.of(id("test:item_source")));
        Map<String, String> configuration = new HashMap<>(Map.of("variant", "fixture"));
        MachineNode source = node("source", capabilities, configuration);
        MachineNode target = node("target", Set.of(id("test:item_sink")), Map.of());
        MachinePort output = port("source_out", source.id(), GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort input = port("target_in", target.id(), GenericResourceType.ITEM, PortMode.INPUT);
        MachineEdge edge = edge("items", output.id(), input.id(), GenericResourceType.ITEM);

        List<MachineNode> nodeList = new ArrayList<>(List.of(source, target));
        List<MachinePort> portList = new ArrayList<>(List.of(output, input));
        List<MachineEdge> edgeList = new ArrayList<>(List.of(edge));
        UnifiedMachineGraph graph = new UnifiedMachineGraph(
                id("test:graph"), nodeList, portList, edgeList);

        capabilities.clear();
        configuration.clear();
        nodeList.clear();
        portList.clear();
        edgeList.clear();

        assertThat(graph.id()).isEqualTo(id("test:graph"));
        assertThat(graph.nodes()).containsOnlyKeys(source.id(), target.id());
        assertThat(graph.node(source.id()).requiredCapabilities()).containsExactly(id("test:item_source"));
        assertThat(graph.node(source.id()).configuration()).containsEntry("variant", "fixture");
        assertThat(graph.port(output.id())).isSameAs(output);
        assertThat(graph.edges()).containsOnlyKeys(edge.id());
        assertThatThrownBy(() -> graph.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateIdsAndPortsWhoseOwnersAreMissing() {
        MachineNode node = node("node", Set.of(), Map.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:duplicate_nodes"), List.of(node, node), List.of(), List.of()))
                .withMessage("Duplicate machine node id: test:node");

        MachinePort orphan = port(
                "orphan", id("test:missing"), GenericResourceType.ITEM, PortMode.INPUT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:orphan"), List.of(node), List.of(orphan), List.of()))
                .withMessageContaining("references unknown node test:missing");

        MachinePort owned = port("owned", node.id(), GenericResourceType.ITEM, PortMode.INPUT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:duplicate_ports"), List.of(node), List.of(owned, owned), List.of()))
                .withMessage("Duplicate machine port id: test:owned");
    }

    @Test
    void rejectsDanglingOrResourceMismatchedEdges() {
        MachineNode source = node("source", Set.of(), Map.of());
        MachineNode target = node("target", Set.of(), Map.of());
        MachinePort output = port("out", source.id(), GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort input = port("in", target.id(), GenericResourceType.ITEM, PortMode.INPUT);

        MachineEdge dangling = edge(
                "dangling", output.id(), id("test:missing"), GenericResourceType.ITEM);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:dangling"), List.of(source, target), List.of(output, input), List.of(dangling)))
                .withMessageContaining("references unknown port(s)");

        MachineEdge mismatch = edge(
                "mismatch", output.id(), input.id(), GenericResourceType.FLUID);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:mismatch"), List.of(source, target), List.of(output, input), List.of(mismatch)))
                .withMessage("Machine edge resource type does not match both endpoint ports: test:mismatch");
    }

    @Test
    void enforcesDirectedAndBidirectionalPortModes() {
        MachineNode first = node("first", Set.of(), Map.of());
        MachineNode second = node("second", Set.of(), Map.of());
        MachinePort firstInput = port("first_in", first.id(), GenericResourceType.HEAT, PortMode.INPUT);
        MachinePort secondInput = port("second_in", second.id(), GenericResourceType.HEAT, PortMode.INPUT);
        MachineEdge invalidDirected = edge(
                "invalid_directed", firstInput.id(), secondInput.id(), GenericResourceType.HEAT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:invalid_directed"),
                        List.of(first, second),
                        List.of(firstInput, secondInput),
                        List.of(invalidDirected)))
                .withMessage("Directed machine edge must connect output to input: test:invalid_directed");

        MachinePort firstBoth = port("first_both", first.id(), GenericResourceType.HEAT, PortMode.BIDIRECTIONAL);
        MachinePort secondOutput = port("second_out", second.id(), GenericResourceType.HEAT, PortMode.OUTPUT);
        MachineEdge invalidBidirectional = new MachineEdge(
                id("test:invalid_bidirectional"),
                firstBoth.id(),
                secondOutput.id(),
                GenericResourceType.HEAT,
                EdgeMode.BIDIRECTIONAL,
                OptionalLong.empty(),
                Map.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:invalid_bidirectional_graph"),
                        List.of(first, second),
                        List.of(firstBoth, secondOutput),
                        List.of(invalidBidirectional)))
                .withMessage("Bidirectional edge requires two bidirectional ports: test:invalid_bidirectional");
    }

    @Test
    void rejectsUnboundedCountsAndNonPositiveOptionalLimits() {
        MachineNode node = node("node", Set.of(), Map.of());
        List<MachineNode> tooManyNodes = new ArrayList<>();
        for (int index = 0; index <= UnifiedMachineGraph.MAX_NODES; index++) {
            tooManyNodes.add(node("node_" + index, Set.of(), Map.of()));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UnifiedMachineGraph(
                        id("test:too_many"), tooManyNodes, List.of(), List.of()))
                .withMessage("Graph node count must be between 1 and 256");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MachinePort(
                        id("test:bad_capacity"),
                        node.id(),
                        GenericResourceType.ITEM,
                        PortMode.INPUT,
                        Optional.empty(),
                        OptionalLong.of(0),
                        Map.of()))
                .withMessage("capacity must be positive when present");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MachineEdge(
                        id("test:bad_throughput"),
                        id("test:a"),
                        id("test:b"),
                        GenericResourceType.ITEM,
                        EdgeMode.DIRECTED,
                        OptionalLong.of(-1),
                        Map.of()))
                .withMessage("maximumThroughput must be positive when present");
    }

    private static MachineNode node(
            String path,
            Set<ResourceId> capabilities,
            Map<String, String> configuration) {
        return new MachineNode(
                id("test:" + path),
                id("test:role/" + path),
                id("test:block/" + path),
                new BlockPos3i(0, 0, 0),
                MachineOrientation.NONE,
                capabilities,
                configuration);
    }

    private static MachinePort port(
            String path,
            ResourceId nodeId,
            GenericResourceType resourceType,
            PortMode mode) {
        return new MachinePort(
                id("test:" + path),
                nodeId,
                resourceType,
                mode,
                Optional.empty(),
                OptionalLong.empty(),
                Map.of());
    }

    private static MachineEdge edge(
            String path,
            ResourceId sourcePortId,
            ResourceId targetPortId,
            GenericResourceType resourceType) {
        return new MachineEdge(
                id("test:" + path),
                sourcePortId,
                targetPortId,
                resourceType,
                EdgeMode.DIRECTED,
                OptionalLong.empty(),
                Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
