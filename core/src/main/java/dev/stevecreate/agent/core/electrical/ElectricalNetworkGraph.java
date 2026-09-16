package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable server-observed FE topology across generators, links, transformers, loads and storage. */
public final class ElectricalNetworkGraph {
    public static final int MAX_NODES = 1_024;
    public static final int MAX_EDGES = 4_096;
    private final ResourceId graphId;
    private final String worldIdentity;
    private final ResourceId dimension;
    private final Map<ResourceId, ElectricalNetworkNode> nodes;
    private final Map<ResourceId, ElectricalWireEdge> edges;
    private final long generation;
    private final String fingerprint;

    public ElectricalNetworkGraph(
            ResourceId graphId,
            String worldIdentity,
            ResourceId dimension,
            List<ElectricalNetworkNode> nodes,
            List<ElectricalWireEdge> edges,
            long generation) {
        this.graphId = Objects.requireNonNull(graphId, "graphId");
        this.worldIdentity = Objects.requireNonNull(worldIdentity, "worldIdentity");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.nodes = indexNodes(nodes);
        this.edges = indexEdges(edges);
        if (generation < 0) throw new IllegalArgumentException("electrical generation is negative");
        this.generation = generation;
        for (ElectricalWireEdge edge : this.edges.values()) {
            if (!this.nodes.containsKey(edge.fromNodeId()) || !this.nodes.containsKey(edge.toNodeId())) {
                throw new IllegalArgumentException("electrical edge references an unknown node");
            }
        }
        this.fingerprint = computeFingerprint();
    }

    public ResourceId graphId() { return graphId; }
    public String worldIdentity() { return worldIdentity; }
    public ResourceId dimension() { return dimension; }
    public Map<ResourceId, ElectricalNetworkNode> nodes() { return nodes; }
    public Map<ResourceId, ElectricalWireEdge> edges() { return edges; }
    public long generation() { return generation; }
    public String fingerprint() { return fingerprint; }

    /** Physical FE endpoint and wire identity without mutable stored-energy observations. */
    public String topologyFingerprint() {
        StringBuilder canonical = new StringBuilder().append(graphId).append('|')
                .append(worldIdentity).append('|').append(dimension).append('\n');
        nodes.values().forEach(value -> canonical.append("N|").append(value.nodeId()).append('|')
                .append(value.kind()).append('|').append(value.position()).append('|')
                .append(value.primaryTier()).append('|').append(value.secondaryTier()).append('|')
                .append(sorted(value.inputFaces())).append('|').append(sorted(value.outputFaces()))
                .append('|').append(value.generationPerTick()).append('|')
                .append(value.consumptionPerTick()).append('|').append(value.storageCapacity())
                .append('\n'));
        edges.values().forEach(value -> canonical.append("E|").append(value.edgeId()).append('|')
                .append(value.fromNodeId()).append('|').append(value.toNodeId()).append('|')
                .append(value.voltageTier()).append('|').append(value.transferDistance()).append('|')
                .append(value.maximumDistance()).append('|').append(value.capacityPerTick()).append('|')
                .append(value.collisionEnvelope()).append('|').append(value.connectionSha256())
                .append('|').append(value.endpointsConnected()).append('|')
                .append(value.collisionFree()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Map<ResourceId, ElectricalNetworkNode> indexNodes(List<ElectricalNetworkNode> values) {
        Objects.requireNonNull(values, "nodes");
        if (values.isEmpty() || values.size() > MAX_NODES) {
            throw new IllegalArgumentException("electrical nodes are empty or unbounded");
        }
        LinkedHashMap<ResourceId, ElectricalNetworkNode> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(value -> value.nodeId().toString()))
                .forEach(value -> {
                    if (result.putIfAbsent(value.nodeId(), value) != null) {
                        throw new IllegalArgumentException("duplicate electrical node");
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private Map<ResourceId, ElectricalWireEdge> indexEdges(List<ElectricalWireEdge> values) {
        Objects.requireNonNull(values, "edges");
        if (values.isEmpty() || values.size() > MAX_EDGES) {
            throw new IllegalArgumentException("electrical edges are empty or unbounded");
        }
        LinkedHashMap<ResourceId, ElectricalWireEdge> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(value -> value.edgeId().toString()))
                .forEach(value -> {
                    if (result.putIfAbsent(value.edgeId(), value) != null) {
                        throw new IllegalArgumentException("duplicate electrical edge");
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private String computeFingerprint() {
        StringBuilder canonical = new StringBuilder();
        canonical.append(graphId).append('|').append(worldIdentity).append('|')
                .append(dimension).append('|').append(generation).append('\n');
        nodes.values().forEach(value -> canonical.append("N|").append(value.nodeId()).append('|')
                .append(value.kind()).append('|').append(value.position()).append('|')
                .append(value.primaryTier()).append('|').append(value.secondaryTier()).append('|')
                .append(sorted(value.inputFaces())).append('|')
                .append(sorted(value.outputFaces())).append('|')
                .append(value.generationPerTick()).append('|').append(value.consumptionPerTick())
                .append('|').append(value.storedEnergy()).append('|')
                .append(value.storageCapacity()).append('|').append(value.stateSha256()).append('\n'));
        edges.values().forEach(value -> canonical.append("E|").append(value.edgeId()).append('|')
                .append(value.fromNodeId()).append('|').append(value.toNodeId()).append('|')
                .append(value.voltageTier()).append('|').append(value.transferDistance()).append('|')
                .append(value.maximumDistance()).append('|').append(value.capacityPerTick()).append('|')
                .append(value.collisionEnvelope()).append('|').append(value.connectionSha256())
                .append('|').append(value.endpointsConnected()).append('|')
                .append(value.collisionFree()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sorted(java.util.Set<?> values) {
        return values.stream().map(Object::toString).sorted().toList().toString();
    }
}
