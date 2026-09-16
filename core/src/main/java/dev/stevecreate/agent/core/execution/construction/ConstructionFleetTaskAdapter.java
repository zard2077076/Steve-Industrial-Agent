package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.execution.fleet.FleetTaskAdapter;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Typed construction facade for the shared graph-neutral fleet kernel. */
public final class ConstructionFleetTaskAdapter
        implements FleetTaskAdapter<ConstructionTaskGraph, ConstructionTask> {
    private final PriorityPolicy priorityPolicy;

    public ConstructionFleetTaskAdapter(PriorityPolicy priorityPolicy) {
        this.priorityPolicy = Objects.requireNonNull(priorityPolicy, "priorityPolicy");
    }

    @Override
    public ResourceId graphId(ConstructionTaskGraph graph) {
        return Objects.requireNonNull(graph, "graph").graphId();
    }

    @Override
    public String graphFingerprint(ConstructionTaskGraph graph) {
        Objects.requireNonNull(graph, "graph");
        StringBuilder canonical = new StringBuilder()
                .append(graph.graphId()).append('|')
                .append(graph.verifiedPhysicalPlanId()).append('|')
                .append(graph.runtimeFingerprint()).append('|')
                .append(graph.worldSnapshotFingerprint());
        // All nested construction values are immutable and canonically ordered by their
        // contract constructors. Bind the entire descriptor/task/edge authority envelope so a
        // reload cannot accept the same graph id with changed actions, reservations or policy.
        graph.descriptors().values().forEach(descriptor ->
                canonical.append("|descriptor:").append(descriptor));
        graph.tasks().values().forEach(task -> canonical.append("|task:").append(task));
        graph.dependencies().forEach(dependency ->
                canonical.append("|edge:").append(dependency));
        return sha256(canonical.toString());
    }

    @Override
    public Map<ResourceId, ConstructionTask> tasks(ConstructionTaskGraph graph) {
        return Objects.requireNonNull(graph, "graph").tasks();
    }

    @Override
    public ResourceId taskId(ConstructionTask task) {
        return Objects.requireNonNull(task, "task").taskId();
    }

    @Override
    public Set<ResourceId> predecessorTaskIds(
            ConstructionTaskGraph graph, ConstructionTask task) {
        return graph.dependencies().stream()
                .filter(dependency -> dependency.successorTaskId().equals(task.taskId()))
                .map(TaskDependency::predecessorTaskId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<ResourceId> requiredWorkLeaseIds(
            ConstructionTaskGraph graph, ConstructionTask task) {
        return task.requiredPlacementReservationIds();
    }

    @Override
    public Set<BotWorkerCapability> requiredCapabilities(
            ConstructionTaskGraph graph, ConstructionTask task) {
        return BotWorkerCapability.requiredFor(task.kind());
    }

    @Override
    public int priority(ConstructionTaskGraph graph, ConstructionTask task) {
        Map<ResourceId, Integer> ranks = new LinkedHashMap<>();
        graph.topologicalOrder().stream()
                .sorted(priorityPolicy.comparator())
                .forEach(value -> ranks.put(value.taskId(), ranks.size()));
        return ranks.get(task.taskId());
    }

    @Override
    public int maximumAttempts(ConstructionTaskGraph graph, ConstructionTask task) {
        return task.retryPolicy().maximumAttempts();
    }

    @Override
    public boolean allowsReassignment(
            ConstructionTaskGraph graph, ConstructionTask task, ResourceId failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        return task.recoveryPolicy().strategy().allowsReassignment()
                && (failureCode.equals(GraphNeutralFleetCoordinator.RELOAD_INTERRUPTED)
                || failureCode.equals(ConstructionFailureCode.WORKER_UNAVAILABLE.id())
                || failureCode.equals(ConstructionFailureCode.NAVIGATION_BLOCKED.id())
                || failureCode.equals(ConstructionFailureCode.CHUNK_NOT_LOADED.id()));
    }

    @Override
    public Set<ResourceId> requiredReconciliationEvidenceIds(
            ConstructionTaskGraph graph, ConstructionTask task) {
        return task.recoveryPolicy().requiredReconciliationEvidence();
    }

    @Override
    public Optional<ResourceId> workerContinuityPredecessorId(
            ConstructionTaskGraph graph, ConstructionTask task) {
        if (task.requiredMaterialReservationIds().isEmpty()) return Optional.empty();
        List<ResourceId> carryingPredecessors = graph.dependencies().stream()
                .filter(dependency -> dependency.successorTaskId().equals(task.taskId()))
                // MATERIAL_DELIVERY is an explicit, world-observable hand-off boundary. The
                // successor may therefore require another worker capability (for example the
                // C-10 logistics Bot delivers to an owned buffer before the builder operates
                // the exact safe machine face). Other dependency kinds retain the same-worker
                // invariant while a material reservation remains physically carried.
                .filter(dependency -> dependency.kind() != TaskDependencyKind.MATERIAL_DELIVERY)
                .map(dependency -> graph.task(dependency.predecessorTaskId()))
                .filter(predecessor -> predecessor.requiredMaterialReservationIds().stream()
                        .anyMatch(task.requiredMaterialReservationIds()::contains))
                .map(ConstructionTask::taskId)
                .sorted(Comparator.comparing(ResourceId::toString))
                .toList();
        if (carryingPredecessors.size() > 1) {
            throw new IllegalArgumentException(
                    "A material task cannot inherit two worker-carried reservation chains");
        }
        return carryingPredecessors.stream().findFirst();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime lacks SHA-256", exception);
        }
    }
}
