package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable bounded production topology before implementation binding and physical layout.
 */
public final class LogicalMachineGraph {
    public static final int MAX_NODES = 256;
    public static final int MAX_PORTS = 1_024;
    public static final int MAX_EDGES = 1_024;

    private final ResourceId id;
    private final Map<ResourceId, LogicalGraphNode> nodes;
    private final Map<ResourceId, LogicalPortRequirement> ports;
    private final Map<ResourceId, LogicalResourceEdge> edges;

    public LogicalMachineGraph(
            ResourceId id,
            List<LogicalGraphNode> nodes,
            List<LogicalPortRequirement> ports,
            List<LogicalResourceEdge> edges) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(edges, "edges");
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "Logical graph node count must be between 1 and " + MAX_NODES);
        }
        if (ports.size() > MAX_PORTS) {
            throw new IllegalArgumentException("Logical graph port count exceeds " + MAX_PORTS);
        }
        if (edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("Logical graph edge count exceeds " + MAX_EDGES);
        }

        this.nodes = indexNodes(nodes);
        this.ports = indexPorts(ports, this.nodes);
        this.edges = indexEdges(edges, this.nodes, this.ports);
    }

    public ResourceId id() {
        return id;
    }

    public Map<ResourceId, LogicalGraphNode> nodes() {
        return nodes;
    }

    public Map<ResourceId, LogicalPortRequirement> ports() {
        return ports;
    }

    public Map<ResourceId, LogicalResourceEdge> edges() {
        return edges;
    }

    public LogicalGraphNode node(ResourceId nodeId) {
        LogicalGraphNode node = nodes.get(Objects.requireNonNull(nodeId, "nodeId"));
        if (node == null) {
            throw new IllegalArgumentException("Unknown logical node: " + nodeId);
        }
        return node;
    }

    public LogicalPortRequirement port(ResourceId portId) {
        LogicalPortRequirement port = ports.get(Objects.requireNonNull(portId, "portId"));
        if (port == null) {
            throw new IllegalArgumentException("Unknown logical port: " + portId);
        }
        return port;
    }

    public LogicalResourceEdge edge(ResourceId edgeId) {
        LogicalResourceEdge edge = edges.get(Objects.requireNonNull(edgeId, "edgeId"));
        if (edge == null) {
            throw new IllegalArgumentException("Unknown logical edge: " + edgeId);
        }
        return edge;
    }

    private static Map<ResourceId, LogicalGraphNode> indexNodes(List<LogicalGraphNode> values) {
        Map<ResourceId, LogicalGraphNode> indexed = new LinkedHashMap<>();
        for (LogicalGraphNode node : values) {
            Objects.requireNonNull(node, "node");
            if (indexed.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate logical node id: " + node.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<ResourceId, LogicalPortRequirement> indexPorts(
            List<LogicalPortRequirement> values,
            Map<ResourceId, LogicalGraphNode> nodes) {
        Map<ResourceId, LogicalPortRequirement> indexed = new LinkedHashMap<>();
        for (LogicalPortRequirement port : values) {
            Objects.requireNonNull(port, "port");
            LogicalGraphNode owner = nodes.get(port.nodeId());
            if (owner == null) {
                throw new IllegalArgumentException(
                        "Logical port " + port.id() + " references unknown node " + port.nodeId());
            }
            validatePortOwner(port, owner);
            if (indexed.putIfAbsent(port.id(), port) != null) {
                throw new IllegalArgumentException("Duplicate logical port id: " + port.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static void validatePortOwner(
            LogicalPortRequirement port,
            LogicalGraphNode owner) {
        boolean valid = switch (port.role()) {
            case PROCESS_INPUT, PROCESS_OUTPUT -> owner.kind() == LogicalNodeKind.PROCESS;
            case EXTERNAL_SUPPLY -> owner.kind() == LogicalNodeKind.RAW_RESOURCE_SOURCE
                    || owner.kind() == LogicalNodeKind.OWNED_RESOURCE_SOURCE;
            case TARGET_DEMAND -> owner.kind() == LogicalNodeKind.TARGET_SINK;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Logical port role " + port.role() + " is invalid for node " + owner.id());
        }
    }

    private static Map<ResourceId, LogicalResourceEdge> indexEdges(
            List<LogicalResourceEdge> values,
            Map<ResourceId, LogicalGraphNode> nodes,
            Map<ResourceId, LogicalPortRequirement> ports) {
        Map<ResourceId, LogicalResourceEdge> indexed = new LinkedHashMap<>();
        for (LogicalResourceEdge edge : values) {
            Objects.requireNonNull(edge, "edge");
            LogicalPortRequirement source = ports.get(edge.sourcePortId());
            LogicalPortRequirement target = ports.get(edge.targetPortId());
            if (source == null || target == null) {
                throw new IllegalArgumentException(
                        "Logical edge " + edge.id() + " references unknown endpoint port(s)");
            }
            if (!source.resourceId().equals(edge.resourceId())
                    || !target.resourceId().equals(edge.resourceId())
                    || source.resourceType() != edge.resourceType()
                    || target.resourceType() != edge.resourceType()) {
                throw new IllegalArgumentException(
                        "Logical edge resource does not match both endpoint ports: " + edge.id());
            }
            if (!source.mode().providesOutput() || !target.mode().acceptsInput()) {
                throw new IllegalArgumentException(
                        "Logical edge must connect an output requirement to an input requirement: "
                                + edge.id());
            }
            if (edge.amount() > source.amount() || edge.amount() > target.amount()) {
                throw new IllegalArgumentException(
                        "Logical edge amount exceeds an endpoint requirement: " + edge.id());
            }
            LogicalGraphNode sourceNode = nodes.get(source.nodeId());
            LogicalGraphNode targetNode = nodes.get(target.nodeId());
            if (sourceNode.id().equals(targetNode.id())
                    || !hasValidTopology(edge.kind(), sourceNode.kind(), targetNode.kind())) {
                throw new IllegalArgumentException(
                        "Logical edge kind has invalid endpoint topology: " + edge.id());
            }
            if (indexed.putIfAbsent(edge.id(), edge) != null) {
                throw new IllegalArgumentException("Duplicate logical edge id: " + edge.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static boolean hasValidTopology(
            LogicalEdgeKind kind,
            LogicalNodeKind source,
            LogicalNodeKind target) {
        return switch (kind) {
            case RAW_INPUT -> source == LogicalNodeKind.RAW_RESOURCE_SOURCE
                    && target == LogicalNodeKind.PROCESS;
            case OWNED_INPUT -> source == LogicalNodeKind.OWNED_RESOURCE_SOURCE
                    && target == LogicalNodeKind.PROCESS;
            case INTERMEDIATE -> source == LogicalNodeKind.PROCESS
                    && target == LogicalNodeKind.PROCESS;
            case TARGET_OUTPUT -> (source == LogicalNodeKind.PROCESS
                    || source == LogicalNodeKind.OWNED_RESOURCE_SOURCE)
                    && target == LogicalNodeKind.TARGET_SINK;
        };
    }
}
