package dev.stevecreate.agent.core.execution.composite;

import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable loader-neutral topology for an admitted multi-stage production composite. */
public record CompositeProductionGraph(
        ResourceId graphId,
        Shape shape,
        List<Node> nodes,
        List<MaterialEdge> edges) {
    public static final int MAX_NODES = 64;
    public static final int MAX_EDGES = 128;

    public CompositeProductionGraph {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(shape, "shape");
        nodes = boundedCopy(nodes, "nodes", 1, MAX_NODES);
        edges = boundedCopy(edges, "edges", 0, MAX_EDGES);
        Map<ResourceId, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (byId.put(node.nodeId(), node) != null) {
                throw new IllegalArgumentException("Duplicate composite node " + node.nodeId());
            }
        }
        Set<ResourceId> edgeIds = new LinkedHashSet<>();
        for (MaterialEdge edge : edges) {
            if (!edgeIds.add(edge.edgeId())) {
                throw new IllegalArgumentException("Duplicate composite edge " + edge.edgeId());
            }
            if (!byId.containsKey(edge.producerNodeId())
                    || !byId.containsKey(edge.consumerNodeId())) {
                throw new IllegalArgumentException("Composite edge references an unknown node");
            }
        }
        validateAcyclic(nodes, edges);
        validateShape(shape, nodes, edges);
    }

    public Node node(ResourceId nodeId) {
        return nodes.stream().filter(value -> value.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown composite node " + nodeId));
    }

    public List<MaterialEdge> incoming(ResourceId nodeId) {
        node(nodeId);
        return edges.stream().filter(value -> value.consumerNodeId().equals(nodeId))
                .sorted(Comparator.comparing(value -> value.edgeId().toString())).toList();
    }

    public List<MaterialEdge> outgoing(ResourceId nodeId) {
        node(nodeId);
        return edges.stream().filter(value -> value.producerNodeId().equals(nodeId))
                .sorted(Comparator.comparing(value -> value.edgeId().toString())).toList();
    }

    /**
     * The sub-graph over {@code retained}, for restarting a chain part-way along.
     *
     * <p>An interrupted run resumes by handing the executor the stages that are left,
     * and the executor requires a graph matching them. Rebuilding one here rather than
     * teaching the executor about partial runs keeps the resumed path identical to a
     * fresh one: it is not a special mode, it is a shorter chain, and every rule that
     * governs a chain governs it. The suffix of a linear chain is a linear chain, and if
     * it is not the constructor refuses it like any other malformed graph.</p>
     *
     * @param retained the nodes to keep, in any order; edges with both ends retained
     *     come with them
     */
    public CompositeProductionGraph retaining(List<ResourceId> retained) {
        Objects.requireNonNull(retained, "retained");
        Set<ResourceId> keep = new LinkedHashSet<>(retained);
        List<Node> keptNodes = nodes.stream().filter(node -> keep.contains(node.nodeId())).toList();
        if (keptNodes.size() != keep.size()) {
            throw new IllegalArgumentException("retained set names a node this graph lacks");
        }
        List<MaterialEdge> keptEdges = edges.stream()
                .filter(edge -> keep.contains(edge.producerNodeId())
                        && keep.contains(edge.consumerNodeId()))
                .toList();
        return new CompositeProductionGraph(graphId, shape, keptNodes, keptEdges);
    }

    public String fingerprint() {
        StringBuilder canonical = new StringBuilder(graphId.toString()).append('|').append(shape);
        nodes.stream().sorted(Comparator.comparing(value -> value.nodeId().toString()))
                .forEach(value -> canonical.append("|n:").append(value.nodeId())
                        .append(':').append(value.capabilityId()).append(':').append(value.lineId())
                        .append(':').append(value.sharedInfrastructureIds()));
        edges.stream().sorted(Comparator.comparing(value -> value.edgeId().toString()))
                .forEach(value -> canonical.append("|e:").append(value.edgeId())
                        .append(':').append(value.producerNodeId()).append('>')
                        .append(value.consumerNodeId()).append(':').append(value.resourceId())
                        .append(':').append(value.quantity()).append('/').append(value.capacity()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public enum Shape {
        LINEAR_CHAIN,
        CONCURRENT_LINES,
        BRANCH_MERGE
    }

    public record Node(
            ResourceId nodeId,
            ResourceId capabilityId,
            ResourceId lineId,
            Set<ResourceId> sharedInfrastructureIds) {
        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(capabilityId, "capabilityId");
            Objects.requireNonNull(lineId, "lineId");
            sharedInfrastructureIds = sortedIds(sharedInfrastructureIds, "sharedInfrastructureIds");
        }
    }

    public record MaterialEdge(
            ResourceId edgeId,
            ResourceId producerNodeId,
            ResourceId consumerNodeId,
            ResourceId resourceId,
            long quantity,
            long capacity) {
        public MaterialEdge {
            Objects.requireNonNull(edgeId, "edgeId");
            Objects.requireNonNull(producerNodeId, "producerNodeId");
            Objects.requireNonNull(consumerNodeId, "consumerNodeId");
            Objects.requireNonNull(resourceId, "resourceId");
            if (producerNodeId.equals(consumerNodeId)) {
                throw new IllegalArgumentException("Composite material edge cannot be self-referential");
            }
            if (quantity < 1 || capacity < quantity) {
                throw new IllegalArgumentException("Composite edge quantity must fit its positive buffer");
            }
        }
    }

    private static void validateShape(Shape shape, List<Node> nodes, List<MaterialEdge> edges) {
        Map<ResourceId, Integer> incoming = counts(nodes, edges, false);
        Map<ResourceId, Integer> outgoing = counts(nodes, edges, true);
        if (shape == Shape.LINEAR_CHAIN) {
            long roots = incoming.values().stream().filter(value -> value == 0).count();
            long sinks = outgoing.values().stream().filter(value -> value == 0).count();
            // Two, not three. A chain of two machines is a composite in every way that
            // matters here — it routes material between stages and needs the same
            // coordination — and the live catalog contains no three-stage chain at all,
            // so a lower bound of three made every derived product unbuildable.
            //
            // The distinct-capability rule that used to sit here is gone, and the survey
            // is why: with the length bound relaxed, all 113 remaining refusals were this
            // one rule, so it was refusing every derivable product in the live catalog.
            // Its premise does not survive inspection either — two crushing recipes in
            // sequence produce different items, cannot be merged into one batch, and need
            // exactly the inter-stage routing a composite provides. What makes a chain a
            // composite is that material moves between stages, which the edges already
            // require. Machine variety was never the thing being checked.
            // One is now allowed, and the reason is not that a lone machine is a
            // composite — it plainly is not. It is that everything a production order
            // needs around a machine (a material ledger, multi-source reservation, a
            // carried delivery, residency, unattended dispatch, restart recovery, site
            // failover) is built on this path and on no other. Refusing a single stage
            // here left eighty-five single-machine products unable to use any of it,
            // while a hundred and eleven wood-cutting chains had all of it.
            //
            // A one-node chain has no edges and needs none; the rules below already say
            // so with edges == nodes - 1. Nothing is relaxed to admit it.
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("Linear composite requires a node");
            }
            if (roots != 1 || sinks != 1
                    || incoming.values().stream().anyMatch(value -> value > 1)
                    || outgoing.values().stream().anyMatch(value -> value > 1)
                    || edges.size() != nodes.size() - 1) {
                throw new IllegalArgumentException(
                        "Linear composite must be a single unbranched chain");
            }
        } else if (shape == Shape.CONCURRENT_LINES) {
            Set<ResourceId> lines = nodes.stream().map(Node::lineId)
                    .collect(java.util.stream.Collectors.toSet());
            boolean crossLine = edges.stream().anyMatch(edge -> !nodeById(nodes, edge.producerNodeId())
                    .lineId().equals(nodeById(nodes, edge.consumerNodeId()).lineId()));
            boolean shared = nodes.stream().flatMap(value -> value.sharedInfrastructureIds().stream())
                    .anyMatch(infrastructure -> nodes.stream()
                            .map(Node::lineId).distinct().filter(line -> nodes.stream().anyMatch(node ->
                                    node.lineId().equals(line)
                                            && node.sharedInfrastructureIds().contains(infrastructure)))
                            .count() >= 2);
            if (lines.size() < 2 || crossLine || !shared) {
                throw new IllegalArgumentException(
                        "Concurrent composite requires independent lines with shared infrastructure");
            }
        } else {
            boolean branch = outgoing.values().stream().anyMatch(value -> value >= 2);
            boolean merge = incoming.values().stream().anyMatch(value -> value >= 2);
            if (!branch || !merge) {
                throw new IllegalArgumentException("Branch/merge composite must be truly non-linear");
            }
        }
    }

    private static Map<ResourceId, Integer> counts(
            List<Node> nodes, List<MaterialEdge> edges, boolean producers) {
        Map<ResourceId, Integer> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.nodeId(), 0));
        edges.forEach(edge -> result.merge(
                producers ? edge.producerNodeId() : edge.consumerNodeId(), 1, Integer::sum));
        return result;
    }

    private static void validateAcyclic(List<Node> nodes, List<MaterialEdge> edges) {
        Map<ResourceId, Integer> incoming = counts(nodes, edges, false);
        ArrayDeque<ResourceId> ready = new ArrayDeque<>(incoming.entrySet().stream()
                .filter(value -> value.getValue() == 0).map(Map.Entry::getKey)
                .sorted(Comparator.comparing(ResourceId::toString)).toList());
        int visited = 0;
        while (!ready.isEmpty()) {
            ResourceId node = ready.removeFirst();
            visited++;
            edges.stream().filter(edge -> edge.producerNodeId().equals(node))
                    .sorted(Comparator.comparing(edge -> edge.consumerNodeId().toString()))
                    .forEach(edge -> {
                        int remaining = incoming.merge(edge.consumerNodeId(), -1, Integer::sum);
                        if (remaining == 0) ready.addLast(edge.consumerNodeId());
                    });
        }
        if (visited != nodes.size()) {
            throw new IllegalArgumentException("Composite production graph contains a cycle");
        }
    }

    private static Node nodeById(List<Node> nodes, ResourceId id) {
        return nodes.stream().filter(value -> value.nodeId().equals(id)).findFirst().orElseThrow();
    }

    private static Set<ResourceId> sortedIds(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_NODES || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " is outside its bounded contract");
        }
        LinkedHashSet<ResourceId> ordered = values.stream()
                .sorted(Comparator.comparing(ResourceId::toString))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return java.util.Collections.unmodifiableSet(ordered);
    }

    private static <T> List<T> boundedCopy(
            List<T> values, String name, int minimum, int maximum) {
        Objects.requireNonNull(values, name);
        if (values.size() < minimum || values.size() > maximum
                || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " is outside its bounded contract");
        }
        return List.copyOf(values);
    }
}
