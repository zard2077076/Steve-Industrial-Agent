package dev.stevecreate.agent.core.execution.fleet;

import dev.stevecreate.agent.core.execution.construction.AssignmentPolicy;
import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.execution.construction.DeadlockDetector;
import dev.stevecreate.agent.core.execution.construction.RetryBudget;
import dev.stevecreate.agent.core.execution.construction.WorkerHealthPolicy;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared deterministic 2-5 worker assignment, lease, reassignment and recovery kernel.
 *
 * <p>The kernel knows no construction or terrain type. A typed {@link FleetTaskAdapter} supplies
 * only an already-authorized DAG, and a matching {@link FleetTaskDispatcher} remains the sole
 * domain execution boundary. The kernel never casts or translates between graph domains.</p>
 */
public final class GraphNeutralFleetCoordinator<G, T> {
    public static final int MIN_WORKERS = 2;
    public static final int MAX_WORKERS = 5;
    public static final int MAX_TASKS = 256;
    public static final int MAX_EVIDENCE_FIELDS = 256;
    public static final long MAX_OWNERSHIP_TICKS = 72_000L;
    public static final ResourceId RELOAD_INTERRUPTED =
            ResourceId.parse("fleet:reload_interrupted");

    private final ResourceId sessionId;
    private final G graph;
    private final FleetTaskAdapter<G, T> adapter;
    private final FleetTaskDispatcher<G, T> dispatcher;
    private final ResourceId dispatcherId;
    private final ResourceId graphId;
    private final String graphFingerprint;
    private final Map<ResourceId, T> tasks;
    private final Map<ResourceId, FleetWorker> workers;
    private final Map<ResourceId, BlockPos3i> workPositions;
    private final AssignmentPolicy assignmentPolicy;
    private final RetryBudget retryBudget;
    private final WorkerHealthPolicy workerHealthPolicy;
    private final long ownershipTicks;
    private final Map<ResourceId, Assignment> assignments = new LinkedHashMap<>();
    private final Map<ResourceId, Update> latestUpdates = new LinkedHashMap<>();
    private final Map<ResourceId, Update> completedUpdates = new LinkedHashMap<>();
    private final Map<ResourceId, Update> terminalUpdates = new LinkedHashMap<>();
    private final Map<ResourceId, WorkLease> workLeases = new LinkedHashMap<>();
    private final Map<ResourceId, Integer> reassignmentCounts = new LinkedHashMap<>();
    private final Map<ResourceId, FailedAssignment> reconciliationRequired =
            new LinkedHashMap<>();
    private final Map<ResourceId, Long> reassignmentEligibleTick = new LinkedHashMap<>();
    private final Map<ResourceId, ResourceId> lastFailedWorker = new LinkedHashMap<>();
    private final Map<ResourceId, ResourceId> waitsForWorker = new LinkedHashMap<>();
    private long generation;
    private long lastTick = -1;
    private boolean globallyCancelled;

    public GraphNeutralFleetCoordinator(
            ResourceId sessionId,
            G graph,
            FleetTaskAdapter<G, T> adapter,
            FleetTaskDispatcher<G, T> dispatcher,
            ResourceId dispatcherId,
            List<? extends FleetWorker> workers,
            Map<ResourceId, BlockPos3i> workPositions,
            AssignmentPolicy assignmentPolicy,
            RetryBudget retryBudget,
            WorkerHealthPolicy workerHealthPolicy,
            long ownershipTicks) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.dispatcherId = Objects.requireNonNull(dispatcherId, "dispatcherId");
        this.graphId = Objects.requireNonNull(adapter.graphId(graph), "graphId");
        this.graphFingerprint = requireFingerprint(adapter.graphFingerprint(graph));
        this.tasks = copyTasks(graph, adapter);
        this.workers = copyWorkers(workers);
        this.workPositions = copyWorkPositions(workPositions);
        this.assignmentPolicy = Objects.requireNonNull(assignmentPolicy, "assignmentPolicy");
        this.retryBudget = Objects.requireNonNull(retryBudget, "retryBudget");
        this.workerHealthPolicy = Objects.requireNonNull(workerHealthPolicy, "workerHealthPolicy");
        if (ownershipTicks < 1 || ownershipTicks > MAX_OWNERSHIP_TICKS) {
            throw new IllegalArgumentException("ownershipTicks is outside the bounded task lease");
        }
        this.ownershipTicks = ownershipTicks;
        validateGraphAndPositions();
    }

    public synchronized TickResult tick(long currentTick) {
        requireTick(currentTick);
        lastTick = currentTick;
        Optional<DeadlockDetector.Deadlock> deadlock = DeadlockDetector.detect(waitsForWorker);
        if (deadlock.isPresent() || globallyCancelled) {
            return result(currentTick, List.of(), deadlock);
        }
        executeActive(currentTick);
        return result(currentTick, scheduleReady(currentTick), Optional.empty());
    }

    public synchronized TickResult cancelAll(ResourceId reason, long currentTick) {
        Objects.requireNonNull(reason, "reason");
        requireTick(currentTick);
        lastTick = currentTick;
        globallyCancelled = true;
        List<ResourceId> active = assignments.keySet().stream()
                .sorted(Comparator.comparing(ResourceId::toString))
                .toList();
        for (ResourceId taskId : active) {
            Assignment assignment = assignments.get(taskId);
            Update update = dispatch(taskId, assignment, Command.CANCEL, currentTick,
                    Optional.of(reason));
            latestUpdates.put(taskId, update);
            if (update.outcome() != Outcome.CANCELLED) terminalUpdates.put(taskId, update);
            releaseTask(taskId, currentTick);
        }
        return result(currentTick, List.of(), Optional.empty());
    }

    public synchronized boolean recordReconciliation(
            ResourceId taskId,
            List<ReconciliationEvidence> evidence,
            long currentTick) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(evidence, "evidence");
        requireTick(currentTick);
        lastTick = currentTick;
        FailedAssignment failed = reconciliationRequired.get(taskId);
        if (failed == null || !retryBudget.allows(reassignmentCounts.getOrDefault(taskId, 0))) {
            return false;
        }
        T task = task(taskId);
        ResourceId failureCode = failed.update().failureCode().orElse(RELOAD_INTERRUPTED);
        if (!adapter.allowsReassignment(graph, task, failureCode)) return false;
        Set<ResourceId> passing = new LinkedHashSet<>();
        for (ReconciliationEvidence item : evidence) {
            if (!item.belongsTo(sessionId, graphId, failed.assignment())
                    || item.observedTick() > currentTick || !item.passed()) {
                return false;
            }
            passing.add(item.requirementId());
        }
        if (!passing.containsAll(adapter.requiredReconciliationEvidenceIds(graph, task))) {
            return false;
        }
        reconciliationRequired.remove(taskId);
        releaseRecoveryLeases(taskId, currentTick);
        reassignmentCounts.merge(taskId, 1, Math::addExact);
        reassignmentEligibleTick.put(
                taskId, Math.addExact(currentTick, retryBudget.reassignmentBackoffTicks()));
        latestUpdates.remove(taskId);
        return true;
    }

    public synchronized void recordWait(ResourceId workerId, ResourceId blockingWorkerId) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(blockingWorkerId, "blockingWorkerId");
        if (!workers.containsKey(workerId) || !workers.containsKey(blockingWorkerId)
                || workerId.equals(blockingWorkerId)) {
            throw new IllegalArgumentException(
                    "wait-for edge must name two different registered workers");
        }
        waitsForWorker.put(workerId, blockingWorkerId);
    }

    public synchronized void clearWait(ResourceId workerId) {
        waitsForWorker.remove(Objects.requireNonNull(workerId, "workerId"));
    }

    public synchronized Snapshot snapshot(long currentTick) {
        requireTick(currentTick);
        lastTick = currentTick;
        Set<ResourceId> recovery = new LinkedHashSet<>(reconciliationRequired.keySet());
        recovery.addAll(assignments.keySet());
        Map<ResourceId, Update> snapshotUpdates = new LinkedHashMap<>(latestUpdates);
        Map<ResourceId, Assignment> recoveryAssignments = new LinkedHashMap<>();
        reconciliationRequired.forEach((taskId, failed) ->
                recoveryAssignments.put(taskId, failed.assignment()));
        assignments.forEach((taskId, assignment) -> {
            recoveryAssignments.put(taskId, assignment);
            snapshotUpdates.computeIfAbsent(taskId, ignored -> new Update(
                    taskId, assignment.assignmentId(), assignment.workerId(), dispatcherId,
                    currentTick, Outcome.RETRYABLE_FAILURE, Optional.of(RELOAD_INTERRUPTED),
                    Set.of(), Map.of(
                    ResourceId.parse("fleet:graph_fingerprint"), graphFingerprint,
                    ResourceId.parse("fleet:ownership_fingerprint"),
                    assignment.ownershipFingerprint()),
                    "Assigned fleet task was interrupted before its first dispatcher update"));
        });
        return new Snapshot(
                sessionId, graphId, graphFingerprint, assignments, snapshotUpdates,
                completedUpdates, terminalUpdates, workLeases, recoveryAssignments,
                reassignmentCounts, recovery, globallyCancelled, generation, currentTick);
    }

    public static <G, T> GraphNeutralFleetCoordinator<G, T> restore(
            Snapshot snapshot,
            G graph,
            FleetTaskAdapter<G, T> adapter,
            FleetTaskDispatcher<G, T> dispatcher,
            ResourceId dispatcherId,
            List<? extends FleetWorker> workers,
            Map<ResourceId, BlockPos3i> workPositions,
            AssignmentPolicy assignmentPolicy,
            RetryBudget retryBudget,
            WorkerHealthPolicy workerHealthPolicy,
            long ownershipTicks) {
        Objects.requireNonNull(snapshot, "snapshot");
        GraphNeutralFleetCoordinator<G, T> coordinator = new GraphNeutralFleetCoordinator<>(
                snapshot.sessionId(), graph, adapter, dispatcher, dispatcherId, workers,
                workPositions, assignmentPolicy, retryBudget, workerHealthPolicy, ownershipTicks);
        if (!snapshot.graphId().equals(coordinator.graphId)
                || !snapshot.graphFingerprint().equals(coordinator.graphFingerprint)) {
            throw new IllegalArgumentException("Fleet reload graph identity/fingerprint changed");
        }
        coordinator.validateRestoredSnapshot(snapshot);
        coordinator.completedUpdates.putAll(snapshot.completedUpdates());
        coordinator.latestUpdates.putAll(snapshot.latestUpdates());
        coordinator.terminalUpdates.putAll(snapshot.terminalUpdates());
        coordinator.reassignmentCounts.putAll(snapshot.reassignmentCounts());
        coordinator.globallyCancelled = snapshot.globallyCancelled();
        coordinator.generation = snapshot.generation();
        coordinator.lastTick = snapshot.savedTick();
        snapshot.workLeases().forEach((leaseId, lease) -> {
            LeaseStatus status = lease.status() == LeaseStatus.ACTIVE
                    && snapshot.reconciliationRequiredTaskIds().contains(lease.taskId())
                    ? LeaseStatus.RECONCILIATION_REQUIRED : lease.status();
            coordinator.workLeases.put(leaseId, lease.withStatus(status));
        });
        for (ResourceId taskId : snapshot.reconciliationRequiredTaskIds()) {
            Assignment assignment = snapshot.recoveryAssignments().get(taskId);
            Update last = snapshot.latestUpdates().get(taskId);
            if (assignment == null || last == null) {
                throw new IllegalArgumentException(
                        "Reload recovery task lacks its exact prior assignment/update");
            }
            coordinator.reconciliationRequired.put(taskId, new FailedAssignment(assignment, last));
            coordinator.lastFailedWorker.put(taskId, assignment.workerId());
        }
        return coordinator;
    }

    public ResourceId sessionId() { return sessionId; }

    public ResourceId graphId() { return graphId; }

    public String graphFingerprint() { return graphFingerprint; }

    public Map<ResourceId, FleetWorker> workers() { return workers; }

    private void executeActive(long currentTick) {
        List<ResourceId> taskIds = assignments.keySet().stream()
                .sorted(Comparator.comparing(ResourceId::toString)).toList();
        for (ResourceId taskId : taskIds) {
            Assignment assignment = assignments.get(taskId);
            Command command = latestUpdates.containsKey(taskId) ? Command.CONTINUE : Command.START;
            Update update = dispatch(taskId, assignment, command, currentTick, Optional.empty());
            latestUpdates.put(taskId, update);
            if (update.outcome() == Outcome.SUCCEEDED) {
                completedUpdates.put(taskId, update);
                releaseTask(taskId, currentTick);
            } else if (update.outcome() != Outcome.PENDING) {
                T task = task(taskId);
                if (update.outcome() == Outcome.RETRYABLE_FAILURE
                        && adapter.allowsReassignment(
                        graph, task, update.failureCode().orElseThrow())
                        && retryBudget.allows(reassignmentCounts.getOrDefault(taskId, 0))) {
                    reconciliationRequired.put(taskId, new FailedAssignment(assignment, update));
                    lastFailedWorker.put(taskId, assignment.workerId());
                } else {
                    terminalUpdates.put(taskId, update);
                }
                releaseTask(taskId, currentTick);
            }
        }
    }

    private List<ResourceId> scheduleReady(long currentTick) {
        Set<ResourceId> excluded = new LinkedHashSet<>(terminalUpdates.keySet());
        excluded.addAll(reconciliationRequired.keySet());
        List<T> ready = tasks.values().stream()
                .filter(task -> !completedUpdates.containsKey(adapter.taskId(task)))
                .filter(task -> !assignments.containsKey(adapter.taskId(task)))
                .filter(task -> !excluded.contains(adapter.taskId(task)))
                .filter(task -> completedUpdates.keySet().containsAll(
                        adapter.predecessorTaskIds(graph, task)))
                .sorted(Comparator.comparingInt((T task) -> adapter.priority(graph, task))
                        .thenComparing(task -> adapter.taskId(task).toString()))
                .toList();
        List<BotWorkerSnapshot> idle = workers.values().stream()
                .map(worker -> observed(worker, currentTick))
                .filter(workerHealthPolicy::eligible)
                .sorted(assignmentPolicy.comparator())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<ResourceId> newlyAssigned = new ArrayList<>();
        for (T task : ready) {
            if (idle.isEmpty()) break;
            ResourceId taskId = adapter.taskId(task);
            if (currentTick < reassignmentEligibleTick.getOrDefault(taskId, 0L)) continue;
            Set<BotWorkerCapability> required = adapter.requiredCapabilities(graph, task);
            Optional<ResourceId> continuityPredecessor =
                    adapter.workerContinuityPredecessorId(graph, task);
            ResourceId continuityWorker = continuityPredecessor
                    .map(completedUpdates::get)
                    .map(Update::workerId)
                    .orElse(null);
            BotWorkerSnapshot worker = idle.stream()
                    .filter(value -> value.capabilities().containsAll(required))
                    .filter(value -> continuityWorker == null
                            || value.workerId().equals(continuityWorker))
                    .filter(value -> !value.workerId().equals(lastFailedWorker.get(taskId))
                            || idle.stream().noneMatch(other ->
                            !other.workerId().equals(value.workerId())
                                    && other.capabilities().containsAll(required)))
                    .findFirst().orElse(null);
            if (worker == null || hasPositionConflict(task, currentTick)) continue;
            int attempt = reassignmentCounts.getOrDefault(taskId, 0) + 1;
            if (attempt > adapter.maximumAttempts(graph, task)) {
                terminalUpdates.put(taskId, Update.terminal(
                        taskId, unassignedId(taskId), dispatcherId, dispatcherId, currentTick,
                        ResourceId.parse("fleet:retry_exhausted"),
                        "Worker reassignment exceeds the typed task retry policy"));
                continue;
            }
            Assignment assignment = assignment(taskId, worker.workerId(), attempt, currentTick);
            assignments.put(taskId, assignment);
            reserveWorkPositions(task, assignment, currentTick);
            idle.remove(worker);
            newlyAssigned.add(taskId);
        }
        return List.copyOf(newlyAssigned);
    }

    private Update dispatch(
            ResourceId taskId,
            Assignment assignment,
            Command command,
            long currentTick,
            Optional<ResourceId> reason) {
        Update prior = latestUpdates.get(taskId);
        ExecutionContext context = new ExecutionContext(
                command, currentTick, Optional.ofNullable(prior), reason);
        Update update = Objects.requireNonNull(dispatcher.execute(
                graph, task(taskId), assignment, context), "fleet dispatcher update");
        if (!update.taskId().equals(taskId)
                || !update.assignmentId().equals(assignment.assignmentId())
                || !update.workerId().equals(assignment.workerId())
                || !update.dispatcherId().equals(dispatcherId)
                || update.tick() != currentTick) {
            throw new IllegalStateException(
                    "Fleet dispatcher returned an update for another task/assignment/worker/tick");
        }
        if (command == Command.CANCEL && update.outcome() != Outcome.CANCELLED) {
            throw new IllegalStateException("Fleet cancellation must return CANCELLED");
        }
        return update;
    }

    private Assignment assignment(
            ResourceId taskId, ResourceId workerId, int attempt, long currentTick) {
        long assignmentGeneration = nextGeneration();
        ResourceId assignmentId = new ResourceId(
                sessionId.namespace(),
                sessionId.path() + "/fleet_assignment/" + taskId.namespace()
                        + "/" + taskId.path() + "/" + assignmentGeneration);
        String ownershipFingerprint = sha256(sessionId + "|" + graphId + "|" + taskId
                + "|" + workerId + "|" + assignmentGeneration);
        return new Assignment(
                assignmentId, sessionId, graphId, graphFingerprint, taskId, dispatcherId,
                workerId, attempt, assignmentGeneration, currentTick,
                Math.addExact(currentTick, ownershipTicks), ownershipFingerprint);
    }

    private void reserveWorkPositions(T task, Assignment assignment, long currentTick) {
        for (ResourceId leaseId : adapter.requiredWorkLeaseIds(graph, task)) {
            workLeases.put(leaseId, new WorkLease(
                    leaseId, graphId, adapter.taskId(task), assignment.assignmentId(),
                    assignment.workerId(), workPositions.get(leaseId), LeaseStatus.ACTIVE,
                    nextGeneration(), currentTick, Math.addExact(currentTick, ownershipTicks)));
        }
    }

    private boolean hasPositionConflict(T task, long currentTick) {
        for (ResourceId leaseId : adapter.requiredWorkLeaseIds(graph, task)) {
            BlockPos3i position = workPositions.get(leaseId);
            for (WorkLease lease : workLeases.values()) {
                if (lease.activeAt(currentTick) && lease.position().equals(position)
                        && !lease.taskId().equals(adapter.taskId(task))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void releaseTask(ResourceId taskId, long currentTick) {
        assignments.remove(taskId);
        releaseLeases(taskId, currentTick, LeaseStatus.ACTIVE);
    }

    private void releaseRecoveryLeases(ResourceId taskId, long currentTick) {
        releaseLeases(taskId, currentTick, LeaseStatus.RECONCILIATION_REQUIRED);
    }

    private void releaseLeases(ResourceId taskId, long currentTick, LeaseStatus from) {
        for (Map.Entry<ResourceId, WorkLease> entry :
                new ArrayList<>(workLeases.entrySet())) {
            WorkLease lease = entry.getValue();
            if (lease.taskId().equals(taskId) && lease.status() == from) {
                workLeases.put(entry.getKey(), new WorkLease(
                        lease.leaseId(), lease.graphId(), lease.taskId(), lease.assignmentId(),
                        lease.workerId(), lease.position(), LeaseStatus.RELEASED,
                        nextGeneration(), lease.acquiredTick(), currentTick));
            }
        }
    }

    private TickResult result(
            long tick, List<ResourceId> newlyAssigned,
            Optional<DeadlockDetector.Deadlock> deadlock) {
        return new TickResult(
                sessionId, graphId, tick, assignments, latestUpdates, completedUpdates,
                terminalUpdates, workLeases, newlyAssigned, deadlock, globallyCancelled);
    }

    private BotWorkerSnapshot observed(FleetWorker worker, long currentTick) {
        BotWorkerSnapshot snapshot = Objects.requireNonNull(
                worker.snapshot(currentTick), "fleet worker snapshot");
        if (!snapshot.workerId().equals(worker.workerId())
                || snapshot.observedTick() != currentTick) {
            throw new IllegalStateException(
                    "Fleet worker returned another identity or observation tick");
        }
        return snapshot;
    }

    private T task(ResourceId taskId) {
        T task = tasks.get(Objects.requireNonNull(taskId, "taskId"));
        if (task == null) throw new IllegalArgumentException("Unknown fleet task " + taskId);
        return task;
    }

    private void validateGraphAndPositions() {
        Set<ResourceId> allLeases = new LinkedHashSet<>();
        for (T task : tasks.values()) {
            ResourceId taskId = adapter.taskId(task);
            Set<ResourceId> predecessors = Set.copyOf(
                    adapter.predecessorTaskIds(graph, task));
            if (predecessors.contains(taskId) || !tasks.keySet().containsAll(predecessors)) {
                throw new IllegalArgumentException("Fleet task has invalid predecessor identity");
            }
            Set<BotWorkerCapability> capabilities = Set.copyOf(
                    adapter.requiredCapabilities(graph, task));
            if (capabilities.isEmpty()) {
                throw new IllegalArgumentException("Fleet task has no required worker capability");
            }
            int maximumAttempts = adapter.maximumAttempts(graph, task);
            if (maximumAttempts < 1 || maximumAttempts > 64) {
                throw new IllegalArgumentException("Fleet task retry policy is unbounded");
            }
            if (adapter.priority(graph, task) < 0) {
                throw new IllegalArgumentException("Fleet task priority must be non-negative");
            }
            allLeases.addAll(Set.copyOf(adapter.requiredWorkLeaseIds(graph, task)));
            Set.copyOf(adapter.requiredReconciliationEvidenceIds(graph, task));
            Optional<ResourceId> continuity = adapter.workerContinuityPredecessorId(graph, task);
            if (continuity.isPresent() && !predecessors.contains(continuity.orElseThrow())) {
                throw new IllegalArgumentException(
                        "Fleet worker continuity must name one exact task predecessor");
            }
        }
        if (!workPositions.keySet().equals(allLeases)) {
            throw new IllegalArgumentException(
                    "Work positions must exactly cover the typed graph lease identities");
        }
        validateAcyclic();
    }

    private void validateRestoredSnapshot(Snapshot snapshot) {
        if (!snapshot.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("Fleet reload session identity changed");
        }
        snapshot.recoveryAssignments().forEach((taskId, assignment) -> {
            if (!taskId.equals(assignment.taskId())
                    || !assignment.sessionId().equals(sessionId)
                    || !assignment.graphId().equals(graphId)
                    || !assignment.graphFingerprint().equals(graphFingerprint)
                    || !assignment.dispatcherId().equals(dispatcherId)
                    || !workers.containsKey(assignment.workerId())) {
                throw new IllegalArgumentException(
                        "Fleet recovery assignment identity/worker/dispatcher changed");
            }
        });
        snapshot.completedUpdates().forEach((taskId, update) -> {
            if (!taskId.equals(update.taskId())
                    || !update.dispatcherId().equals(dispatcherId)
                    || !workers.containsKey(update.workerId())
                    || update.outcome() != Outcome.SUCCEEDED) {
                throw new IllegalArgumentException(
                        "Fleet completed update identity/worker/dispatcher changed");
            }
        });
        snapshot.workLeases().forEach((leaseId, lease) -> {
            if (!leaseId.equals(lease.leaseId())
                    || !lease.graphId().equals(graphId)
                    || !tasks.containsKey(lease.taskId())
                    || !workers.containsKey(lease.workerId())) {
                throw new IllegalArgumentException(
                        "Fleet work lease identity/worker changed during reload");
            }
        });
    }

    private void validateAcyclic() {
        Set<ResourceId> completed = new LinkedHashSet<>();
        while (completed.size() < tasks.size()) {
            List<ResourceId> ready = tasks.values().stream()
                    .map(adapter::taskId)
                    .filter(taskId -> !completed.contains(taskId))
                    .filter(taskId -> completed.containsAll(
                            adapter.predecessorTaskIds(graph, tasks.get(taskId))))
                    .sorted(Comparator.comparing(ResourceId::toString)).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Fleet task graph contains a cycle");
            completed.addAll(ready);
        }
    }

    private long nextGeneration() {
        generation = Math.addExact(generation, 1);
        return generation;
    }

    private void requireTick(long currentTick) {
        if (currentTick < 0) throw new IllegalArgumentException("currentTick must be non-negative");
        if (currentTick < lastTick) {
            throw new IllegalArgumentException("Bot fleet tick cannot move backwards");
        }
    }

    private ResourceId unassignedId(ResourceId taskId) {
        return new ResourceId(sessionId.namespace(), sessionId.path()
                + "/unassigned/" + taskId.namespace() + "/" + taskId.path());
    }

    private static <G, T> Map<ResourceId, T> copyTasks(
            G graph, FleetTaskAdapter<G, T> adapter) {
        Map<ResourceId, T> supplied = Objects.requireNonNull(adapter.tasks(graph), "tasks");
        if (supplied.isEmpty() || supplied.size() > MAX_TASKS) {
            throw new IllegalArgumentException("Fleet task count is outside the bounded limit");
        }
        Map<ResourceId, T> copied = new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> {
                    ResourceId key = Objects.requireNonNull(entry.getKey(), "task key");
                    T task = Objects.requireNonNull(entry.getValue(), "task");
                    if (!key.equals(adapter.taskId(task)) || copied.put(key, task) != null) {
                        throw new IllegalArgumentException(
                                "Fleet task key is duplicate or differs from typed identity");
                    }
                });
        return Map.copyOf(copied);
    }

    private static Map<ResourceId, FleetWorker> copyWorkers(
            List<? extends FleetWorker> supplied) {
        Objects.requireNonNull(supplied, "workers");
        if (supplied.size() < MIN_WORKERS || supplied.size() > MAX_WORKERS) {
            throw new IllegalArgumentException(
                    "Fleet size must be between " + MIN_WORKERS + " and " + MAX_WORKERS);
        }
        Map<ResourceId, FleetWorker> copied = new LinkedHashMap<>();
        supplied.stream().map(worker -> Objects.requireNonNull(worker, "worker"))
                .sorted(Comparator.comparing(worker -> worker.workerId().toString()))
                .forEach(worker -> {
                    if (copied.put(worker.workerId(), worker) != null) {
                        throw new IllegalArgumentException("Duplicate fleet worker " + worker.workerId());
                    }
                });
        return Map.copyOf(copied);
    }

    private static Map<ResourceId, BlockPos3i> copyWorkPositions(
            Map<ResourceId, BlockPos3i> supplied) {
        Objects.requireNonNull(supplied, "workPositions");
        if (supplied.size() > MAX_TASKS) {
            throw new IllegalArgumentException("Fleet work-position count exceeds its bound");
        }
        Map<ResourceId, BlockPos3i> copied = new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> copied.put(
                        Objects.requireNonNull(entry.getKey(), "work lease id"),
                        Objects.requireNonNull(entry.getValue(), "work position")));
        return Map.copyOf(copied);
    }

    private static String requireFingerprint(String value) {
        Objects.requireNonNull(value, "graphFingerprint");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fleet graph fingerprint must be lowercase SHA-256");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime lacks SHA-256", exception);
        }
    }

    public enum Command { START, CONTINUE, CANCEL }

    public enum Outcome { PENDING, SUCCEEDED, RETRYABLE_FAILURE, TERMINAL_FAILURE, CANCELLED }

    public enum LeaseStatus { ACTIVE, RECONCILIATION_REQUIRED, RELEASED }

    public record Assignment(
            ResourceId assignmentId,
            ResourceId sessionId,
            ResourceId graphId,
            String graphFingerprint,
            ResourceId taskId,
            ResourceId dispatcherId,
            ResourceId workerId,
            int attempt,
            long generation,
            long assignedTick,
            long expiresTick,
            String ownershipFingerprint) {
        public Assignment {
            Objects.requireNonNull(assignmentId, "assignmentId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(graphId, "graphId");
            graphFingerprint = requireFingerprint(graphFingerprint);
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(dispatcherId, "dispatcherId");
            Objects.requireNonNull(workerId, "workerId");
            if (attempt < 1 || generation < 1 || assignedTick < 0 || expiresTick <= assignedTick) {
                throw new IllegalArgumentException("Fleet assignment attempt/generation/ticks are invalid");
            }
            ownershipFingerprint = requireFingerprint(ownershipFingerprint);
        }
    }

    public record ExecutionContext(
            Command command,
            long currentTick,
            Optional<Update> priorUpdate,
            Optional<ResourceId> cancellationReason) {
        public ExecutionContext {
            Objects.requireNonNull(command, "command");
            if (currentTick < 0) throw new IllegalArgumentException("currentTick is negative");
            priorUpdate = Objects.requireNonNull(priorUpdate, "priorUpdate");
            cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason");
            if ((command == Command.CANCEL) != cancellationReason.isPresent()) {
                throw new IllegalArgumentException(
                        "Only fleet cancellation carries an exact reason identity");
            }
        }
    }

    public record Update(
            ResourceId taskId,
            ResourceId assignmentId,
            ResourceId workerId,
            ResourceId dispatcherId,
            long tick,
            Outcome outcome,
            Optional<ResourceId> failureCode,
            Set<ResourceId> evidenceIds,
            Map<ResourceId, String> evidenceFields,
            String detail) {
        public Update {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(assignmentId, "assignmentId");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(dispatcherId, "dispatcherId");
            if (tick < 0) throw new IllegalArgumentException("Fleet update tick is negative");
            Objects.requireNonNull(outcome, "outcome");
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            evidenceFields = Map.copyOf(Objects.requireNonNull(evidenceFields, "evidenceFields"));
            if (evidenceIds.size() > MAX_EVIDENCE_FIELDS
                    || evidenceFields.size() > MAX_EVIDENCE_FIELDS) {
                throw new IllegalArgumentException("Fleet update evidence exceeds its bound");
            }
            if ((outcome == Outcome.RETRYABLE_FAILURE || outcome == Outcome.TERMINAL_FAILURE)
                    != failureCode.isPresent()) {
                throw new IllegalArgumentException(
                        "Only failed fleet updates require one typed failure code");
            }
            if (detail == null || detail.isBlank() || detail.length() > 2_048) {
                throw new IllegalArgumentException("Fleet update detail is absent or unbounded");
            }
        }

        public static Update terminal(
                ResourceId taskId, ResourceId assignmentId, ResourceId workerId,
                ResourceId dispatcherId,
                long tick, ResourceId failureCode, String detail) {
            return new Update(taskId, assignmentId, workerId, dispatcherId, tick,
                    Outcome.TERMINAL_FAILURE, Optional.of(failureCode), Set.of(), Map.of(), detail);
        }
    }

    public record ReconciliationEvidence(
            ResourceId requirementId,
            ResourceId sessionId,
            ResourceId graphId,
            ResourceId taskId,
            ResourceId assignmentId,
            ResourceId workerId,
            long observedTick,
            boolean passed,
            Map<ResourceId, String> fields,
            String provenance) {
        public ReconciliationEvidence {
            Objects.requireNonNull(requirementId, "requirementId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(assignmentId, "assignmentId");
            Objects.requireNonNull(workerId, "workerId");
            if (observedTick < 0) throw new IllegalArgumentException("evidence tick is negative");
            fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
            if (fields.size() > MAX_EVIDENCE_FIELDS) {
                throw new IllegalArgumentException("Fleet reconciliation evidence is unbounded");
            }
            if (provenance == null || provenance.isBlank() || provenance.length() > 1_024) {
                throw new IllegalArgumentException("Fleet reconciliation provenance is invalid");
            }
        }

        boolean belongsTo(ResourceId session, ResourceId graph, Assignment assignment) {
            return sessionId.equals(session) && graphId.equals(graph)
                    && taskId.equals(assignment.taskId())
                    && assignmentId.equals(assignment.assignmentId())
                    && workerId.equals(assignment.workerId());
        }
    }

    public record WorkLease(
            ResourceId leaseId,
            ResourceId graphId,
            ResourceId taskId,
            ResourceId assignmentId,
            ResourceId workerId,
            BlockPos3i position,
            LeaseStatus status,
            long generation,
            long acquiredTick,
            long expiresTick) {
        public WorkLease {
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(assignmentId, "assignmentId");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(status, "status");
            if (generation < 0 || acquiredTick < 0 || expiresTick < acquiredTick
                    || (status == LeaseStatus.ACTIVE && expiresTick == acquiredTick)) {
                throw new IllegalArgumentException("Fleet work lease generation/ticks are invalid");
            }
        }

        public boolean activeAt(long tick) {
            return status == LeaseStatus.ACTIVE && tick >= acquiredTick && tick < expiresTick;
        }

        WorkLease withStatus(LeaseStatus replacement) {
            return new WorkLease(leaseId, graphId, taskId, assignmentId, workerId, position,
                    replacement, generation, acquiredTick, expiresTick);
        }
    }

    public record Snapshot(
            ResourceId sessionId,
            ResourceId graphId,
            String graphFingerprint,
            Map<ResourceId, Assignment> activeAssignments,
            Map<ResourceId, Update> latestUpdates,
            Map<ResourceId, Update> completedUpdates,
            Map<ResourceId, Update> terminalUpdates,
            Map<ResourceId, WorkLease> workLeases,
            Map<ResourceId, Assignment> recoveryAssignments,
            Map<ResourceId, Integer> reassignmentCounts,
            Set<ResourceId> reconciliationRequiredTaskIds,
            boolean globallyCancelled,
            long generation,
            long savedTick) {
        public Snapshot {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(graphId, "graphId");
            graphFingerprint = requireFingerprint(graphFingerprint);
            activeAssignments = bounded(activeAssignments, "activeAssignments");
            latestUpdates = bounded(latestUpdates, "latestUpdates");
            completedUpdates = bounded(completedUpdates, "completedUpdates");
            terminalUpdates = bounded(terminalUpdates, "terminalUpdates");
            workLeases = bounded(workLeases, "workLeases");
            recoveryAssignments = bounded(recoveryAssignments, "recoveryAssignments");
            reassignmentCounts = bounded(reassignmentCounts, "reassignmentCounts");
            reconciliationRequiredTaskIds = Set.copyOf(Objects.requireNonNull(
                    reconciliationRequiredTaskIds, "reconciliationRequiredTaskIds"));
            if (activeAssignments.size() > MAX_WORKERS
                    || reconciliationRequiredTaskIds.size() > MAX_TASKS
                    || generation < 0 || savedTick < 0) {
                throw new IllegalArgumentException("Fleet snapshot exceeds a bounded limit");
            }
            if (!recoveryAssignments.keySet().containsAll(reconciliationRequiredTaskIds)
                    || !latestUpdates.keySet().containsAll(reconciliationRequiredTaskIds)) {
                throw new IllegalArgumentException(
                        "Every reconciliation task requires its exact assignment/update");
            }
        }

        private static <V> Map<ResourceId, V> bounded(Map<ResourceId, V> supplied, String name) {
            Map<ResourceId, V> copied = Map.copyOf(Objects.requireNonNull(supplied, name));
            if (copied.size() > MAX_TASKS) {
                throw new IllegalArgumentException("Fleet snapshot " + name + " is unbounded");
            }
            return copied;
        }
    }

    public record TickResult(
            ResourceId sessionId,
            ResourceId graphId,
            long tick,
            Map<ResourceId, Assignment> activeAssignments,
            Map<ResourceId, Update> latestUpdates,
            Map<ResourceId, Update> completedUpdates,
            Map<ResourceId, Update> terminalUpdates,
            Map<ResourceId, WorkLease> workLeases,
            List<ResourceId> newlyAssignedTaskIds,
            Optional<DeadlockDetector.Deadlock> deadlock,
            boolean globallyCancelled) {
        public TickResult {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(graphId, "graphId");
            if (tick < 0) throw new IllegalArgumentException("Fleet result tick is negative");
            activeAssignments = Map.copyOf(activeAssignments);
            latestUpdates = Map.copyOf(latestUpdates);
            completedUpdates = Map.copyOf(completedUpdates);
            terminalUpdates = Map.copyOf(terminalUpdates);
            workLeases = Map.copyOf(workLeases);
            newlyAssignedTaskIds = List.copyOf(newlyAssignedTaskIds);
            deadlock = Objects.requireNonNull(deadlock, "deadlock");
        }
    }

    private record FailedAssignment(Assignment assignment, Update update) {
        private FailedAssignment {
            Objects.requireNonNull(assignment, "assignment");
            Objects.requireNonNull(update, "update");
        }
    }
}
