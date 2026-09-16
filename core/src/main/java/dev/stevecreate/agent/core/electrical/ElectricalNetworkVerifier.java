package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic FE topology verifier; it observes but never creates or repairs connections. */
public final class ElectricalNetworkVerifier {
    public VerificationReport verify(ElectricalNetworkGraph graph) {
        Objects.requireNonNull(graph, "graph");
        EnumSet<ElectricalNetworkFailure> failures = EnumSet.noneOf(ElectricalNetworkFailure.class);
        long generation = graph.nodes().values().stream()
                .mapToLong(ElectricalNetworkNode::generationPerTick).sum();
        long load = graph.nodes().values().stream()
                .mapToLong(ElectricalNetworkNode::consumptionPerTick).sum();
        long headroom = graph.nodes().values().stream()
                .filter(value -> value.kind() == ElectricalNodeKind.STORAGE)
                .mapToLong(value -> value.storageCapacity() - value.storedEnergy()).sum();
        long edgeCapacity = graph.edges().values().stream()
                .mapToLong(ElectricalWireEdge::capacityPerTick).min().orElse(0);
        for (ElectricalWireEdge edge : graph.edges().values()) {
            ElectricalNetworkNode from = graph.nodes().get(edge.fromNodeId());
            ElectricalNetworkNode to = graph.nodes().get(edge.toNodeId());
            if (edge.transferDistance() > edge.maximumDistance()) {
                failures.add(ElectricalNetworkFailure.EDGE_TOO_LONG);
            }
            if (!from.supports(edge.voltageTier()) || !to.supports(edge.voltageTier())) {
                failures.add(ElectricalNetworkFailure.VOLTAGE_TIER_MISMATCH);
            }
            if (from.outputFaces().isEmpty() || to.inputFaces().isEmpty()) {
                failures.add(ElectricalNetworkFailure.INTERFACE_DIRECTION_MISSING);
            }
            if (!edge.collisionFree()) failures.add(ElectricalNetworkFailure.WIRE_COLLISION);
            if (!edge.endpointsConnected()) failures.add(ElectricalNetworkFailure.DISCONNECTED);
            if (edge.capacityPerTick() < load) {
                failures.add(ElectricalNetworkFailure.EDGE_CAPACITY_EXCEEDED);
            }
        }
        if (generation + graph.nodes().values().stream()
                .filter(value -> value.kind() == ElectricalNodeKind.STORAGE)
                .mapToLong(ElectricalNetworkNode::storedEnergy).sum() < load) {
            failures.add(ElectricalNetworkFailure.GENERATION_DEFICIT);
        }
        if (generation > load && headroom < generation - load
                && graph.nodes().values().stream().anyMatch(value ->
                        value.kind() == ElectricalNodeKind.STORAGE)) {
            failures.add(ElectricalNetworkFailure.STORAGE_HEADROOM_INSUFFICIENT);
        }
        Set<ResourceId> reachable = reachableFromGenerators(graph);
        if (graph.nodes().values().stream().filter(value -> value.kind() == ElectricalNodeKind.CONSUMER)
                .anyMatch(value -> !reachable.contains(value.nodeId()))) {
            failures.add(ElectricalNetworkFailure.CONSUMER_UNREACHABLE);
        }
        return new VerificationReport(graph.graphId(), graph.fingerprint(), generation, load,
                headroom, edgeCapacity, List.copyOf(failures), failures.isEmpty());
    }

    private static Set<ResourceId> reachableFromGenerators(ElectricalNetworkGraph graph) {
        ArrayDeque<ResourceId> queue = new ArrayDeque<>();
        graph.nodes().values().stream().filter(value -> value.kind() == ElectricalNodeKind.GENERATOR)
                .map(ElectricalNetworkNode::nodeId).forEach(queue::add);
        Set<ResourceId> visited = new LinkedHashSet<>();
        while (!queue.isEmpty() && visited.size() <= ElectricalNetworkGraph.MAX_NODES) {
            ResourceId current = queue.removeFirst();
            if (!visited.add(current)) continue;
            graph.edges().values().stream().filter(value -> value.endpointsConnected()
                            && value.fromNodeId().equals(current))
                    .map(ElectricalWireEdge::toNodeId).forEach(queue::addLast);
        }
        return Collections.unmodifiableSet(visited);
    }

    public record VerificationReport(
            ResourceId graphId,
            String graphFingerprint,
            long generationPerTick,
            long loadPerTick,
            long storageHeadroom,
            long minimumEdgeCapacity,
            List<ElectricalNetworkFailure> failures,
            boolean accepted) {
        public VerificationReport {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(graphFingerprint, "graphFingerprint");
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            if (accepted == !failures.isEmpty()) {
                throw new IllegalArgumentException("electrical verification result is inconsistent");
            }
        }
    }
}
