package dev.stevecreate.agent.core.graph;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded, loader-neutral machine topology with strict typed edges. */
public final class UnifiedMachineGraph {
    public static final int MAX_NODES = 256;
    public static final int MAX_PORTS = 1_024;
    public static final int MAX_EDGES = 1_024;

    private final ResourceId id;
    private final Map<ResourceId, MachineNode> nodes;
    private final Map<ResourceId, MachinePort> ports;
    private final Map<ResourceId, MachineEdge> edges;

    public UnifiedMachineGraph(
            ResourceId id,
            List<MachineNode> nodes,
            List<MachinePort> ports,
            List<MachineEdge> edges) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(edges, "edges");
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("Graph node count must be between 1 and " + MAX_NODES);
        }
        if (ports.size() > MAX_PORTS) {
            throw new IllegalArgumentException("Graph port count exceeds " + MAX_PORTS);
        }
        if (edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("Graph edge count exceeds " + MAX_EDGES);
        }

        this.nodes = indexNodes(nodes);
        this.ports = indexPorts(ports, this.nodes);
        this.edges = indexEdges(edges, this.ports);
    }

    public ResourceId id() {
        return id;
    }

    public Map<ResourceId, MachineNode> nodes() {
        return nodes;
    }

    public Map<ResourceId, MachinePort> ports() {
        return ports;
    }

    public Map<ResourceId, MachineEdge> edges() {
        return edges;
    }

    public MachineNode node(ResourceId nodeId) {
        MachineNode node = nodes.get(Objects.requireNonNull(nodeId, "nodeId"));
        if (node == null) {
            throw new IllegalArgumentException("Unknown machine node: " + nodeId);
        }
        return node;
    }

    public MachinePort port(ResourceId portId) {
        MachinePort port = ports.get(Objects.requireNonNull(portId, "portId"));
        if (port == null) {
            throw new IllegalArgumentException("Unknown machine port: " + portId);
        }
        return port;
    }

    private static Map<ResourceId, MachineNode> indexNodes(List<MachineNode> values) {
        Map<ResourceId, MachineNode> indexed = new LinkedHashMap<>();
        for (MachineNode node : values) {
            Objects.requireNonNull(node, "node");
            if (indexed.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate machine node id: " + node.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<ResourceId, MachinePort> indexPorts(
            List<MachinePort> values,
            Map<ResourceId, MachineNode> nodes) {
        Map<ResourceId, MachinePort> indexed = new LinkedHashMap<>();
        for (MachinePort port : values) {
            Objects.requireNonNull(port, "port");
            if (!nodes.containsKey(port.nodeId())) {
                throw new IllegalArgumentException(
                        "Machine port " + port.id() + " references unknown node " + port.nodeId());
            }
            if (indexed.putIfAbsent(port.id(), port) != null) {
                throw new IllegalArgumentException("Duplicate machine port id: " + port.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<ResourceId, MachineEdge> indexEdges(
            List<MachineEdge> values,
            Map<ResourceId, MachinePort> ports) {
        Map<ResourceId, MachineEdge> indexed = new LinkedHashMap<>();
        for (MachineEdge edge : values) {
            Objects.requireNonNull(edge, "edge");
            MachinePort source = ports.get(edge.sourcePortId());
            MachinePort target = ports.get(edge.targetPortId());
            if (source == null || target == null) {
                throw new IllegalArgumentException("Machine edge " + edge.id()
                        + " references unknown port(s): " + edge.sourcePortId()
                        + " -> " + edge.targetPortId());
            }
            if (source.resourceType() != edge.resourceType()
                    || target.resourceType() != edge.resourceType()) {
                throw new IllegalArgumentException(
                        "Machine edge resource type does not match both endpoint ports: " + edge.id());
            }
            if (edge.mode() == EdgeMode.DIRECTED
                    && (!source.mode().providesOutput() || !target.mode().acceptsInput())) {
                throw new IllegalArgumentException(
                        "Directed machine edge must connect output to input: " + edge.id());
            }
            if (edge.mode() == EdgeMode.BIDIRECTIONAL
                    && (source.mode() != PortMode.BIDIRECTIONAL
                    || target.mode() != PortMode.BIDIRECTIONAL)) {
                throw new IllegalArgumentException(
                        "Bidirectional edge requires two bidirectional ports: " + edge.id());
            }
            if (indexed.putIfAbsent(edge.id(), edge) != null) {
                throw new IllegalArgumentException("Duplicate machine edge id: " + edge.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }
}
