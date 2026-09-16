package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.fleet.FleetTaskAdapter;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Typed terrain view over the shared 2-5 worker fleet kernel. */
public final class TerrainPreparationFleetTaskAdapter
        implements FleetTaskAdapter<TerrainPreparationTaskGraph, TerrainPreparationTask> {
    public static final ResourceId AUTHORITATIVE_TERRAIN_RESCAN =
            ResourceId.parse("site-prep:authoritative_terrain_rescan");
    public static final ResourceId PATH_UNREACHABLE =
            ResourceId.parse("site-prep:bot_clearing_path_unreachable");
    public static final ResourceId WORKER_UNAVAILABLE =
            ResourceId.parse("site-prep:bot_worker_unavailable");
    public static final ResourceId CHUNK_NOT_LOADED =
            ResourceId.parse("site-prep:chunk_not_loaded");

    @Override
    public ResourceId graphId(TerrainPreparationTaskGraph graph) {
        return ResourceId.parse(graph.graphIdentity());
    }

    @Override
    public String graphFingerprint(TerrainPreparationTaskGraph graph) {
        StringBuilder canonical = new StringBuilder()
                .append(graph.graphIdentity()).append('|')
                .append(graph.planHash()).append('|')
                .append(graph.siteSnapshotHash()).append('|')
                .append(graph.approvalTokenIdentity()).append('|')
                .append(graph.maximumMutations());
        ordered(graph).forEach(task -> canonical.append("|task:")
                .append(task.taskIdentity()).append(':').append(task.kind()).append(':')
                .append(task.positions()).append(':')
                .append(task.predecessorTaskIdentities().stream().sorted().toList()).append(':')
                .append(task.maximumMutations()).append(':').append(task.maximumTicks()));
        return SitePreparationHashes.sha256(canonical.toString());
    }

    @Override
    public Map<ResourceId, TerrainPreparationTask> tasks(TerrainPreparationTaskGraph graph) {
        Map<ResourceId, TerrainPreparationTask> indexed = new LinkedHashMap<>();
        ordered(graph).forEach(task -> indexed.put(taskId(task), task));
        return Map.copyOf(indexed);
    }

    @Override
    public ResourceId taskId(TerrainPreparationTask task) {
        return ResourceId.parse(task.taskIdentity());
    }

    @Override
    public Set<ResourceId> predecessorTaskIds(
            TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        return task.predecessorTaskIdentities().stream()
                .map(ResourceId::parse)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<ResourceId> requiredWorkLeaseIds(
            TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        Set<ResourceId> leases = new LinkedHashSet<>();
        for (BlockPos3i position : task.positions()) {
            leases.add(leaseId(task, position));
        }
        return Set.copyOf(leases);
    }

    /** Exact lease-position map required by the shared coordinator constructor. */
    public Map<ResourceId, BlockPos3i> workPositions(TerrainPreparationTaskGraph graph) {
        Map<ResourceId, BlockPos3i> positions = new LinkedHashMap<>();
        ordered(graph).forEach(task -> task.positions().forEach(position ->
                positions.put(leaseId(task, position), position)));
        return Map.copyOf(positions);
    }

    @Override
    public Set<BotWorkerCapability> requiredCapabilities(
            TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        return switch (task.kind()) {
            case RESERVE_CLEARANCE_POSITIONS, RELEASE_CLEARANCE_POSITIONS ->
                    EnumSet.of(BotWorkerCapability.REGISTRATION,
                            BotWorkerCapability.REPORT_EVIDENCE);
            case REMOVE_SAFE_FOLIAGE, MINE_AUTHORIZED_BLOCK -> EnumSet.of(
                    BotWorkerCapability.NAVIGATE_TO, BotWorkerCapability.REACHABILITY,
                    BotWorkerCapability.LOOK_AT,
                    BotWorkerCapability.REMOVE_APPROVED_TERRAIN_BLOCK,
                    BotWorkerCapability.VERIFY_BLOCK_STATE,
                    BotWorkerCapability.REPORT_EVIDENCE);
            case COLLECT_DROPS -> EnumSet.of(
                    BotWorkerCapability.NAVIGATE_TO, BotWorkerCapability.REACHABILITY,
                    BotWorkerCapability.COLLECT_APPROVED_SALVAGE,
                    BotWorkerCapability.CARRY_MATERIAL,
                    BotWorkerCapability.REPORT_EVIDENCE);
            case DELIVER_SALVAGE -> EnumSet.of(
                    BotWorkerCapability.NAVIGATE_TO, BotWorkerCapability.REACHABILITY,
                    BotWorkerCapability.CARRY_MATERIAL,
                    BotWorkerCapability.DELIVER_APPROVED_SALVAGE,
                    BotWorkerCapability.REPORT_EVIDENCE);
            case FILL_MINOR_HOLE, LEVEL_SURFACE -> EnumSet.of(
                    BotWorkerCapability.NAVIGATE_TO, BotWorkerCapability.REACHABILITY,
                    BotWorkerCapability.LOOK_AT,
                    BotWorkerCapability.FILL_APPROVED_TERRAIN,
                    BotWorkerCapability.VERIFY_BLOCK_STATE,
                    BotWorkerCapability.REPORT_EVIDENCE);
            case VERIFY_GROUND -> EnumSet.of(
                    BotWorkerCapability.REACHABILITY,
                    BotWorkerCapability.VERIFY_PREPARED_GROUND,
                    BotWorkerCapability.REPORT_EVIDENCE);
        };
    }

    @Override
    public int priority(TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        List<TerrainPreparationTask> order = topologicalOrder(graph);
        int priority = order.indexOf(task);
        if (priority < 0) throw new IllegalArgumentException("Unknown terrain task");
        return priority;
    }

    @Override
    public int maximumAttempts(TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        return switch (task.kind()) {
            case RESERVE_CLEARANCE_POSITIONS, RELEASE_CLEARANCE_POSITIONS,
                    VERIFY_GROUND -> 1;
            default -> 3;
        };
    }

    @Override
    public boolean allowsReassignment(
            TerrainPreparationTaskGraph graph,
            TerrainPreparationTask task,
            ResourceId failureCode) {
        return maximumAttempts(graph, task) > 1
                && (failureCode.equals(GraphNeutralFleetCoordinator.RELOAD_INTERRUPTED)
                || failureCode.equals(PATH_UNREACHABLE)
                || failureCode.equals(WORKER_UNAVAILABLE)
                || failureCode.equals(CHUNK_NOT_LOADED));
    }

    @Override
    public Set<ResourceId> requiredReconciliationEvidenceIds(
            TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        return maximumAttempts(graph, task) > 1
                ? Set.of(AUTHORITATIVE_TERRAIN_RESCAN) : Set.of();
    }

    @Override
    public Optional<ResourceId> workerContinuityPredecessorId(
            TerrainPreparationTaskGraph graph, TerrainPreparationTask task) {
        TerrainPreparationTaskKind required = switch (task.kind()) {
            case COLLECT_DROPS -> TerrainPreparationTaskKind.MINE_AUTHORIZED_BLOCK;
            case DELIVER_SALVAGE -> TerrainPreparationTaskKind.COLLECT_DROPS;
            default -> null;
        };
        if (required == null) return Optional.empty();
        List<ResourceId> candidates = task.predecessorTaskIdentities().stream()
                .map(findTask(graph))
                .filter(predecessor -> predecessor.kind() == required)
                .map(this::taskId)
                .sorted(Comparator.comparing(ResourceId::toString))
                .toList();
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(task.kind()
                    + " requires one exact same-worker predecessor");
        }
        return Optional.of(candidates.get(0));
    }

    private java.util.function.Function<String, TerrainPreparationTask> findTask(
            TerrainPreparationTaskGraph graph) {
        Map<String, TerrainPreparationTask> indexed = graph.tasks().stream().collect(
                java.util.stream.Collectors.toMap(
                        TerrainPreparationTask::taskIdentity, task -> task));
        return identity -> Optional.ofNullable(indexed.get(identity)).orElseThrow(() ->
                new IllegalArgumentException("Unknown terrain predecessor " + identity));
    }

    private ResourceId leaseId(TerrainPreparationTask task, BlockPos3i position) {
        return ResourceId.parse("site-prep:lease/" + SitePreparationHashes.sha256(
                task.taskIdentity() + "|" + position).substring(0, 32));
    }

    private static List<TerrainPreparationTask> ordered(TerrainPreparationTaskGraph graph) {
        return graph.tasks().stream()
                .sorted(Comparator.comparing(TerrainPreparationTask::taskIdentity)).toList();
    }

    private List<TerrainPreparationTask> topologicalOrder(TerrainPreparationTaskGraph graph) {
        Map<String, TerrainPreparationTask> remaining = ordered(graph).stream().collect(
                java.util.stream.Collectors.toMap(
                        TerrainPreparationTask::taskIdentity, task -> task,
                        (left, right) -> left, LinkedHashMap::new));
        Set<String> completed = new LinkedHashSet<>();
        List<TerrainPreparationTask> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<TerrainPreparationTask> ready = remaining.values().stream()
                    .filter(task -> completed.containsAll(task.predecessorTaskIdentities()))
                    .sorted(Comparator.comparing(TerrainPreparationTask::taskIdentity)).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Terrain graph contains a cycle");
            ready.forEach(task -> {
                result.add(task);
                completed.add(task.taskIdentity());
                remaining.remove(task.taskIdentity());
            });
        }
        return List.copyOf(result);
    }
}
