package dev.stevecreate.agent.core.execution.composite;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic material-readiness and recovery coordinator for admitted production DAGs. */
public final class CompositeProductionCoordinator {
    private final CompositeProductionGraph graph;
    private final Map<ResourceId, NodeStatus> statuses = new LinkedHashMap<>();
    private final Map<ResourceId, Long> buffers = new LinkedHashMap<>();
    private final Map<ResourceId, ResourceId> observedResources = new LinkedHashMap<>();
    private final Map<ResourceId, Long> recoveryGenerations = new LinkedHashMap<>();
    private long generation;

    public CompositeProductionCoordinator(CompositeProductionGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        graph.nodes().forEach(node -> statuses.put(node.nodeId(), NodeStatus.WAITING));
        graph.edges().forEach(edge -> {
            buffers.put(edge.edgeId(), 0L);
            observedResources.put(edge.edgeId(), edge.resourceId());
        });
    }

    public List<ResourceId> readyNodes() {
        return graph.nodes().stream()
                .filter(node -> statuses.get(node.nodeId()) == NodeStatus.WAITING)
                .filter(node -> graph.incoming(node.nodeId()).stream().allMatch(edge ->
                        statuses.get(edge.producerNodeId()) == NodeStatus.SUCCEEDED
                                && buffers.get(edge.edgeId()) >= edge.quantity()
                                && observedResources.get(edge.edgeId()).equals(edge.resourceId())))
                .map(CompositeProductionGraph.Node::nodeId)
                .sorted(Comparator.comparing(ResourceId::toString)).toList();
    }

    public Transition start(ResourceId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (!readyNodes().contains(nodeId)) {
            return Transition.rejected(nodeId, Failure.NOT_READY,
                    "Dependencies or exact intermediate buffers are not ready");
        }
        for (CompositeProductionGraph.MaterialEdge edge : graph.incoming(nodeId)) {
            buffers.compute(edge.edgeId(), (ignored, current) -> Math.subtractExact(current, edge.quantity()));
        }
        statuses.put(nodeId, NodeStatus.RUNNING);
        generation++;
        return Transition.accepted(nodeId, NodeStatus.RUNNING, "Exact inputs reserved from buffers");
    }

    public Transition complete(ResourceId nodeId, Map<ResourceId, Long> observedOutputs) {
        Objects.requireNonNull(nodeId, "nodeId");
        observedOutputs = Map.copyOf(Objects.requireNonNull(observedOutputs, "observedOutputs"));
        if (statuses.get(graph.node(nodeId).nodeId()) != NodeStatus.RUNNING) {
            return Transition.rejected(nodeId, Failure.NOT_RUNNING, "Node is not running");
        }
        Map<ResourceId, Long> requiredOutputs = new LinkedHashMap<>();
        for (CompositeProductionGraph.MaterialEdge edge : graph.outgoing(nodeId)) {
            requiredOutputs.merge(edge.resourceId(), edge.quantity(), Math::addExact);
            if (!observedResources.get(edge.edgeId()).equals(edge.resourceId())) {
                return Transition.rejected(nodeId, NodeStatus.RUNNING, Failure.CONTAMINATION,
                        "Intermediate buffer contains an unexpected resource for edge "
                                + edge.edgeId());
            }
            if (Math.addExact(buffers.get(edge.edgeId()), edge.quantity()) > edge.capacity()) {
                return Transition.rejected(nodeId, NodeStatus.RUNNING, Failure.BACKPRESSURE,
                        "Intermediate buffer is full for edge " + edge.edgeId());
            }
        }
        for (Map.Entry<ResourceId, Long> required : requiredOutputs.entrySet()) {
            long observed = observedOutputs.getOrDefault(required.getKey(), 0L);
            if (observed < required.getValue()) {
                return Transition.rejected(nodeId, NodeStatus.RUNNING, Failure.OUTPUT_MISSING,
                        "Observed output does not satisfy all exact outgoing reservations for "
                                + required.getKey());
            }
        }
        graph.outgoing(nodeId).forEach(edge -> buffers.merge(
                edge.edgeId(), edge.quantity(), Math::addExact));
        statuses.put(nodeId, NodeStatus.SUCCEEDED);
        generation++;
        return Transition.accepted(nodeId, NodeStatus.SUCCEEDED,
                "Output readback committed to exact intermediate buffers");
    }

    public Transition fail(ResourceId nodeId, String detail) {
        Objects.requireNonNull(detail, "detail");
        NodeStatus current = statuses.get(graph.node(nodeId).nodeId());
        if (current != NodeStatus.RUNNING) {
            return Transition.rejected(nodeId, Failure.NOT_RUNNING, "Node is not running");
        }
        statuses.put(nodeId, NodeStatus.FAILED);
        generation++;
        return Transition.accepted(nodeId, NodeStatus.FAILED, detail);
    }

    public Transition reconcileRunningNode(
            ResourceId nodeId, RecoveryEvidence evidence) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(evidence, "evidence");
        CompositeProductionGraph.Node node = graph.node(nodeId);
        if (statuses.get(node.nodeId()) != NodeStatus.RECOVERY_REQUIRED) {
            return Transition.rejected(
                    nodeId, statuses.get(node.nodeId()), Failure.NOT_RECOVERY_REQUIRED,
                    "Composite node is not waiting for wrapper recovery");
        }
        Map<ResourceId, Long> expectedInputs = new LinkedHashMap<>();
        graph.incoming(nodeId).forEach(edge ->
                expectedInputs.put(edge.edgeId(), edge.quantity()));
        long recoveryGeneration = recoveryGenerations.getOrDefault(nodeId, -1L);
        if (!evidence.graphId().equals(graph.graphId())
                || !evidence.graphFingerprint().equals(graph.fingerprint())
                || !evidence.nodeId().equals(nodeId)
                || evidence.snapshotGeneration() != recoveryGeneration
                || !evidence.handlerStateReconciled()
                || !evidence.inputReservationsReconciled()
                || !evidence.reservedInputQuantities().equals(expectedInputs)) {
            return Transition.rejected(
                    nodeId, NodeStatus.RECOVERY_REQUIRED,
                    Failure.RECOVERY_EVIDENCE_MISMATCH,
                    "Composite wrapper recovery evidence does not match the exact running node");
        }
        statuses.put(nodeId, NodeStatus.RUNNING);
        recoveryGenerations.remove(nodeId);
        generation = Math.addExact(generation, 1);
        return Transition.accepted(
                nodeId, NodeStatus.RUNNING,
                "Exact handler and reserved-input wrapper state reconciled");
    }

    public Set<ResourceId> cancelLine(ResourceId lineId) {
        Objects.requireNonNull(lineId, "lineId");
        Set<ResourceId> cancelled = new LinkedHashSet<>();
        graph.nodes().stream().filter(node -> node.lineId().equals(lineId))
                .sorted(Comparator.comparing(node -> node.nodeId().toString()))
                .forEach(node -> {
                    NodeStatus current = statuses.get(node.nodeId());
                    if (current == NodeStatus.WAITING || current == NodeStatus.RUNNING
                            || current == NodeStatus.RECOVERY_REQUIRED) {
                        if (current == NodeStatus.RUNNING
                                || current == NodeStatus.RECOVERY_REQUIRED) {
                            for (CompositeProductionGraph.MaterialEdge edge
                                    : graph.incoming(node.nodeId())) {
                                long restored = Math.addExact(
                                        buffers.get(edge.edgeId()), edge.quantity());
                                if (restored > edge.capacity()) {
                                    throw new IllegalStateException(
                                            "Cancellation cannot restore exact reserved input for "
                                                    + edge.edgeId());
                                }
                                buffers.put(edge.edgeId(), restored);
                            }
                        }
                        statuses.put(node.nodeId(), NodeStatus.CANCELLED);
                        recoveryGenerations.remove(node.nodeId());
                        cancelled.add(node.nodeId());
                    }
                });
        if (!cancelled.isEmpty()) generation++;
        return Set.copyOf(cancelled);
    }

    public BufferObservation observeBuffer(
            ResourceId edgeId, ResourceId resourceId, long quantity) {
        Objects.requireNonNull(edgeId, "edgeId");
        Objects.requireNonNull(resourceId, "resourceId");
        if (quantity < 0) throw new IllegalArgumentException("quantity must be non-negative");
        CompositeProductionGraph.MaterialEdge edge = graph.edges().stream()
                .filter(value -> value.edgeId().equals(edgeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown composite edge " + edgeId));
        if (!edge.resourceId().equals(resourceId)) {
            observedResources.put(edgeId, resourceId);
            generation++;
            return new BufferObservation(edgeId, false, Failure.CONTAMINATION,
                    edge.resourceId(), resourceId, buffers.get(edgeId), quantity);
        }
        if (quantity > edge.capacity()) {
            return new BufferObservation(edgeId, false, Failure.BACKPRESSURE,
                    edge.resourceId(), resourceId, buffers.get(edgeId), quantity);
        }
        observedResources.put(edgeId, resourceId);
        buffers.put(edgeId, quantity);
        generation++;
        return new BufferObservation(edgeId, true, Failure.NONE,
                edge.resourceId(), resourceId, quantity, quantity);
    }

    public CompositeProductionSnapshot snapshot() {
        return new CompositeProductionSnapshot(
                graph.graphId(), graph.fingerprint(), statuses, buffers, observedResources, generation);
    }

    public static CompositeProductionCoordinator restore(
            CompositeProductionGraph graph, CompositeProductionSnapshot snapshot) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.graphId().equals(graph.graphId())
                || !snapshot.graphFingerprint().equals(graph.fingerprint())) {
            throw new IllegalArgumentException("Composite reload graph identity changed");
        }
        CompositeProductionCoordinator restored = new CompositeProductionCoordinator(graph);
        if (!snapshot.nodeStatuses().keySet().equals(restored.statuses.keySet())
                || !snapshot.bufferQuantities().keySet().equals(restored.buffers.keySet())
                || !snapshot.bufferResources().keySet().equals(restored.observedResources.keySet())) {
            throw new IllegalArgumentException("Composite reload snapshot is incomplete");
        }
        for (CompositeProductionGraph.MaterialEdge edge : graph.edges()) {
            long quantity = snapshot.bufferQuantities().get(edge.edgeId());
            if (quantity < 0 || quantity > edge.capacity()) {
                throw new IllegalArgumentException("Composite reload buffer is outside capacity");
            }
        }
        snapshot.nodeStatuses().forEach((nodeId, status) -> {
            if (status == NodeStatus.RUNNING || status == NodeStatus.RECOVERY_REQUIRED) {
                restored.statuses.put(nodeId, NodeStatus.RECOVERY_REQUIRED);
                restored.recoveryGenerations.put(nodeId, snapshot.generation());
            } else {
                restored.statuses.put(nodeId, status);
            }
        });
        restored.buffers.putAll(snapshot.bufferQuantities());
        restored.observedResources.putAll(snapshot.bufferResources());
        restored.generation = snapshot.generation();
        return restored;
    }

    public CompositeProductionGraph graph() { return graph; }
    public Map<ResourceId, NodeStatus> statuses() { return Map.copyOf(statuses); }
    public Map<ResourceId, Long> bufferQuantities() { return Map.copyOf(buffers); }

    public enum NodeStatus {
        WAITING, RUNNING, RECOVERY_REQUIRED, SUCCEEDED, FAILED, CANCELLED
    }

    public enum Failure {
        NONE,
        NOT_READY,
        NOT_RUNNING,
        OUTPUT_MISSING,
        BACKPRESSURE,
        CONTAMINATION,
        NOT_RECOVERY_REQUIRED,
        RECOVERY_EVIDENCE_MISMATCH
    }

    public record RecoveryEvidence(
            ResourceId graphId,
            String graphFingerprint,
            ResourceId nodeId,
            long snapshotGeneration,
            boolean handlerStateReconciled,
            boolean inputReservationsReconciled,
            Map<ResourceId, Long> reservedInputQuantities,
            String provenance) {
        public RecoveryEvidence {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(graphFingerprint, "graphFingerprint");
            Objects.requireNonNull(nodeId, "nodeId");
            if (!graphFingerprint.matches("[0-9a-f]{64}") || snapshotGeneration < 0) {
                throw new IllegalArgumentException(
                        "Composite recovery graph fingerprint/generation is invalid");
            }
            reservedInputQuantities = Map.copyOf(Objects.requireNonNull(
                    reservedInputQuantities, "reservedInputQuantities"));
            if (reservedInputQuantities.size() > CompositeProductionGraph.MAX_EDGES
                    || reservedInputQuantities.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getValue() == null
                            || entry.getValue() < 1)) {
                throw new IllegalArgumentException(
                        "Composite recovery input reservations are invalid or unbounded");
            }
            if (provenance == null || provenance.isBlank() || provenance.length() > 1_024) {
                throw new IllegalArgumentException(
                        "Composite recovery provenance is absent or unbounded");
            }
        }
    }

    public record Transition(
            ResourceId nodeId,
            boolean accepted,
            NodeStatus status,
            Failure failure,
            String detail) {
        public Transition {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(detail, "detail");
        }

        static Transition accepted(ResourceId nodeId, NodeStatus status, String detail) {
            return new Transition(nodeId, true, status, Failure.NONE, detail);
        }

        static Transition rejected(ResourceId nodeId, Failure failure, String detail) {
            return new Transition(nodeId, false, NodeStatus.WAITING, failure, detail);
        }

        static Transition rejected(
                ResourceId nodeId, NodeStatus status, Failure failure, String detail) {
            return new Transition(nodeId, false, status, failure, detail);
        }
    }

    public record BufferObservation(
            ResourceId edgeId,
            boolean accepted,
            Failure failure,
            ResourceId expectedResource,
            ResourceId observedResource,
            long priorQuantity,
            long observedQuantity) {
        public BufferObservation {
            Objects.requireNonNull(edgeId, "edgeId");
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(expectedResource, "expectedResource");
            Objects.requireNonNull(observedResource, "observedResource");
        }
    }
}
