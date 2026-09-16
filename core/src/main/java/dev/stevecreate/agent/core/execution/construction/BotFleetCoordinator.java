package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Command;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ExecutionContext;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.LeaseStatus;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Outcome;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ReconciliationEvidence;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Snapshot;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.TickResult;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Update;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.WorkLease;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Construction-typed compatibility facade over the shared graph-neutral 2-5 worker kernel.
 *
 * <p>The public constructor, snapshots and execution results retain the original verified
 * {@link ConstructionTaskGraph} contract. Terrain work must supply its own typed adapter and
 * dispatcher; it cannot enter this facade or manufacture VerifiedPhysicalPlan provenance.</p>
 */
public final class BotFleetCoordinator {
    public static final int MIN_WORKERS = GraphNeutralFleetCoordinator.MIN_WORKERS;
    public static final int MAX_WORKERS = GraphNeutralFleetCoordinator.MAX_WORKERS;
    public static final long MAX_OWNERSHIP_TICKS =
            GraphNeutralFleetCoordinator.MAX_OWNERSHIP_TICKS;

    private final ResourceId sessionId;
    private final ConstructionTaskGraph graph;
    private final BotFleetExecutor executor;
    private final Map<ResourceId, BlockPos3i> verifiedWorkPositions;
    private final AssignmentPolicy assignmentPolicy;
    private final PriorityPolicy priorityPolicy;
    private final RetryBudget retryBudget;
    private final WorkerHealthPolicy workerHealthPolicy;
    private final ChunkLoadPolicy chunkLoadPolicy;
    private final long ownershipTicks;
    private final ConstructionFleetTaskAdapter adapter;
    private final Map<ResourceId, TaskExecutionResult> latestResults = new LinkedHashMap<>();
    private final Map<ResourceId, TaskExecutionResult> completedResults = new LinkedHashMap<>();
    private final Map<ResourceId, TaskFailure> terminalFailures = new LinkedHashMap<>();
    private GraphNeutralFleetCoordinator<ConstructionTaskGraph, ConstructionTask> kernel;

    public BotFleetCoordinator(
            ResourceId sessionId,
            ConstructionTaskGraph graph,
            BotFleetExecutor executor,
            Map<ResourceId, BlockPos3i> verifiedWorkPositions,
            AssignmentPolicy assignmentPolicy,
            PriorityPolicy priorityPolicy,
            RetryBudget retryBudget,
            WorkerHealthPolicy workerHealthPolicy,
            ChunkLoadPolicy chunkLoadPolicy,
            long ownershipTicks) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.verifiedWorkPositions = copyWorkPositions(verifiedWorkPositions, graph);
        this.assignmentPolicy = Objects.requireNonNull(assignmentPolicy, "assignmentPolicy");
        this.priorityPolicy = Objects.requireNonNull(priorityPolicy, "priorityPolicy");
        this.retryBudget = Objects.requireNonNull(retryBudget, "retryBudget");
        this.workerHealthPolicy = Objects.requireNonNull(workerHealthPolicy, "workerHealthPolicy");
        this.chunkLoadPolicy = Objects.requireNonNull(chunkLoadPolicy, "chunkLoadPolicy");
        if (ownershipTicks < 1 || ownershipTicks > MAX_OWNERSHIP_TICKS) {
            throw new IllegalArgumentException("ownershipTicks is outside the bounded task lease");
        }
        this.ownershipTicks = ownershipTicks;
        this.adapter = new ConstructionFleetTaskAdapter(priorityPolicy);
        this.kernel = newKernel();
    }

    public synchronized BotFleetTickResult tick(long currentTick) {
        TickResult result = kernel.tick(currentTick);
        syncTerminalFailures(result);
        return result(result);
    }

    public synchronized BotFleetTickResult cancelAll(ResourceId reason, long currentTick) {
        TickResult result = kernel.cancelAll(reason, currentTick);
        syncTerminalFailures(result);
        return result(result);
    }

    public synchronized boolean recordReconciliation(
            ResourceId taskId,
            List<ExecutionEvidence> evidence,
            long currentTick) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(evidence, "evidence");
        Snapshot snapshot = kernel.snapshot(currentTick);
        Assignment neutral = snapshot.recoveryAssignments().get(taskId);
        if (neutral == null) return false;
        TaskAssignment assignment = typed(neutral);
        List<ReconciliationEvidence> mapped = new ArrayList<>();
        for (ExecutionEvidence item : evidence) {
            if (!item.belongsTo(assignment)
                    || item.kind() != ExecutionEvidenceKind.RECOVERY_RECONCILED) {
                return false;
            }
            mapped.add(new ReconciliationEvidence(
                    item.requirementId(), item.sessionId(), item.graphId(), item.taskId(),
                    item.assignmentId(), neutral.workerId(), item.observedTick(), item.passed(),
                    item.observedValues(), item.provenance()));
        }
        boolean accepted = kernel.recordReconciliation(taskId, mapped, currentTick);
        if (accepted) latestResults.remove(taskId);
        return accepted;
    }

    public synchronized void recordWait(ResourceId workerId, ResourceId blockingWorkerId) {
        kernel.recordWait(workerId, blockingWorkerId);
    }

    public synchronized void clearWait(ResourceId workerId) {
        kernel.clearWait(workerId);
    }

    public synchronized BotFleetCoordinatorSnapshot snapshot(long currentTick) {
        Snapshot snapshot = kernel.snapshot(currentTick);
        return new BotFleetCoordinatorSnapshot(
                sessionId,
                graph.graphId(),
                adapter.graphFingerprint(graph),
                assignments(snapshot.activeAssignments()),
                latestResults,
                completedResults,
                workerIds(snapshot.completedUpdates()),
                terminalFailures,
                reservations(snapshot.workLeases(), currentTick),
                assignments(snapshot.recoveryAssignments()),
                snapshot.reassignmentCounts(),
                snapshot.reconciliationRequiredTaskIds(),
                snapshot.globallyCancelled(),
                snapshot.generation(),
                snapshot.savedTick());
    }

    public static BotFleetCoordinator restore(
            BotFleetCoordinatorSnapshot snapshot,
            ConstructionTaskGraph graph,
            BotFleetExecutor executor,
            Map<ResourceId, BlockPos3i> verifiedWorkPositions,
            AssignmentPolicy assignmentPolicy,
            PriorityPolicy priorityPolicy,
            RetryBudget retryBudget,
            WorkerHealthPolicy workerHealthPolicy,
            ChunkLoadPolicy chunkLoadPolicy,
            long ownershipTicks) {
        Objects.requireNonNull(snapshot, "snapshot");
        BotFleetCoordinator coordinator = new BotFleetCoordinator(
                snapshot.sessionId(), graph, executor, verifiedWorkPositions,
                assignmentPolicy, priorityPolicy, retryBudget, workerHealthPolicy,
                chunkLoadPolicy, ownershipTicks);
        if (!snapshot.graphId().equals(graph.graphId())
                || !snapshot.graphFingerprint().equals(
                coordinator.adapter.graphFingerprint(graph))) {
            throw new IllegalArgumentException(
                    "Fleet reload graph identity/fingerprint changed");
        }
        coordinator.latestResults.putAll(snapshot.latestResults());
        coordinator.completedResults.putAll(snapshot.completedResults());
        coordinator.terminalFailures.putAll(snapshot.terminalFailures());
        Snapshot neutral = coordinator.neutral(snapshot);
        coordinator.kernel = GraphNeutralFleetCoordinator.restore(
                neutral, graph, coordinator.adapter, coordinator::dispatch, executor.executorId(),
                new ArrayList<>(executor.workers().values()), coordinator.verifiedWorkPositions,
                assignmentPolicy, retryBudget, workerHealthPolicy, ownershipTicks);
        return coordinator;
    }

    public ResourceId sessionId() { return sessionId; }

    public ConstructionTaskGraph graph() { return graph; }

    public ChunkLoadPolicy chunkLoadPolicy() { return chunkLoadPolicy; }

    private GraphNeutralFleetCoordinator<ConstructionTaskGraph, ConstructionTask> newKernel() {
        return new GraphNeutralFleetCoordinator<>(
                sessionId, graph, adapter, this::dispatch, executor.executorId(),
                new ArrayList<>(executor.workers().values()), verifiedWorkPositions,
                assignmentPolicy, retryBudget, workerHealthPolicy, ownershipTicks);
    }

    private Update dispatch(
            ConstructionTaskGraph suppliedGraph,
            ConstructionTask task,
            Assignment assignment,
            ExecutionContext context) {
        if (suppliedGraph != graph) {
            throw new IllegalArgumentException("Construction fleet dispatcher received another graph");
        }
        TaskAssignment typed = typed(assignment);
        TaskExecutionResult prior = latestResults.get(task.taskId());
        ConstructionExecutionCommand command = switch (context.command()) {
            case START -> ConstructionExecutionCommand.START;
            case CONTINUE -> ConstructionExecutionCommand.CONTINUE;
            case CANCEL -> ConstructionExecutionCommand.CANCEL;
        };
        ConstructionExecutionContext typedContext = new ConstructionExecutionContext(
                command,
                context.currentTick(),
                prior == null ? List.of() : prior.evidence(),
                prior == null ? Optional.empty() : prior.failure(),
                context.cancellationReason());
        TaskExecutionResult update = executor.execute(graph, task, typed, typedContext);
        latestResults.put(task.taskId(), update);
        if (update.outcome() == TaskExecutionOutcome.SUCCEEDED) {
            completedResults.put(task.taskId(), update);
        }
        Outcome outcome = switch (update.outcome()) {
            case PENDING -> Outcome.PENDING;
            case SUCCEEDED -> Outcome.SUCCEEDED;
            case CANCELLED -> Outcome.CANCELLED;
            case RETRYABLE_FAILURE -> reassignmentCandidate(task, update)
                    ? Outcome.RETRYABLE_FAILURE : Outcome.TERMINAL_FAILURE;
            case TERMINAL_FAILURE, RECOVERY_REQUIRED, UNSUPPORTED -> Outcome.TERMINAL_FAILURE;
        };
        Optional<ResourceId> failureCode = outcome == Outcome.RETRYABLE_FAILURE
                || outcome == Outcome.TERMINAL_FAILURE
                ? update.failure().map(TaskFailure::code)
                : Optional.empty();
        Map<ResourceId, String> fields = Map.of(
                ResourceId.parse("fleet:evidence_count"),
                Integer.toString(update.evidence().size()),
                ResourceId.parse("fleet:world_mutations"),
                Integer.toString(update.worldMutationCount()),
                ResourceId.parse("fleet:material_mutations"),
                Integer.toString(update.materialMutationCount()),
                ResourceId.parse("fleet:domain"), "construction");
        return new Update(
                task.taskId(), assignment.assignmentId(), assignment.workerId(),
                executor.executorId(), context.currentTick(), outcome, failureCode,
                update.evidence().stream().map(ExecutionEvidence::evidenceId)
                        .collect(Collectors.toUnmodifiableSet()),
                fields, update.detail());
    }

    private boolean reassignmentCandidate(ConstructionTask task, TaskExecutionResult update) {
        if (!task.recoveryPolicy().strategy().allowsReassignment()) return false;
        return update.failure().map(value ->
                value.code().equals(ConstructionFailureCode.WORKER_UNAVAILABLE.id())
                        || value.code().equals(ConstructionFailureCode.NAVIGATION_BLOCKED.id())
                        || value.code().equals(ConstructionFailureCode.CHUNK_NOT_LOADED.id()))
                .orElse(false);
    }

    private TaskAssignment typed(Assignment assignment) {
        TaskOwnership ownership = new TaskOwnership(
                assignment.sessionId(), assignment.graphId(), assignment.taskId(),
                assignment.dispatcherId(), ExecutionMode.BOTS, Optional.of(assignment.workerId()),
                assignment.generation(), assignment.assignedTick(), assignment.expiresTick(),
                assignment.ownershipFingerprint());
        return TaskAssignment.assign(
                graph, assignment.taskId(), assignment.assignmentId(), ownership,
                assignment.attempt(), assignment.assignedTick());
    }

    private Map<ResourceId, TaskAssignment> assignments(Map<ResourceId, Assignment> supplied) {
        Map<ResourceId, TaskAssignment> mapped = new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> mapped.put(entry.getKey(), typed(entry.getValue())));
        return Map.copyOf(mapped);
    }

    private Map<ResourceId, WorkPositionReservation> reservations(
            Map<ResourceId, WorkLease> supplied, long currentTick) {
        Map<ResourceId, WorkPositionReservation> mapped = new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> {
                    WorkLease lease = entry.getValue();
                    ReservationStatus status = lease.status() == LeaseStatus.ACTIVE
                            ? ReservationStatus.ACTIVE : ReservationStatus.RELEASED;
                    long expires = status == ReservationStatus.ACTIVE
                            ? lease.expiresTick() : Math.max(lease.acquiredTick(),
                            Math.min(currentTick, lease.expiresTick()));
                    mapped.put(entry.getKey(), new WorkPositionReservation(
                            lease.leaseId(), lease.graphId(), lease.taskId(), lease.assignmentId(),
                            lease.workerId(), lease.position(), status, lease.generation(),
                            lease.acquiredTick(), expires));
                });
        return Map.copyOf(mapped);
    }

    private BotFleetTickResult result(TickResult result) {
        return new BotFleetTickResult(
                sessionId, result.tick(), assignments(result.activeAssignments()),
                latestResults, completedResults, terminalFailures,
                reservations(result.workLeases(), result.tick()), result.newlyAssignedTaskIds(),
                result.deadlock(), result.globallyCancelled());
    }

    private void syncTerminalFailures(TickResult result) {
        for (ResourceId taskId : result.terminalUpdates().keySet()) {
            TaskExecutionResult typed = latestResults.get(taskId);
            if (typed != null && typed.failure().isPresent()) {
                terminalFailures.put(taskId, typed.failure().orElseThrow());
            } else if (!terminalFailures.containsKey(taskId)) {
                terminalFailures.put(taskId, failure(
                        ConstructionFailureCode.RETRY_EXHAUSTED,
                        graph.task(taskId),
                        "Worker reassignment exceeds the task retry policy"));
            }
        }
    }

    private Snapshot neutral(BotFleetCoordinatorSnapshot snapshot) {
        Map<ResourceId, Assignment> active = neutralAssignments(
                snapshot.activeAssignments(), snapshot.graphFingerprint());
        Map<ResourceId, Assignment> recovery = neutralAssignments(
                snapshot.recoveryAssignments(), snapshot.graphFingerprint());
        Map<ResourceId, Update> latest = neutralUpdates(
                snapshot.latestResults(), recovery, active, Map.of());
        Map<ResourceId, Update> completed = neutralUpdates(
                snapshot.completedResults(), recovery, active, snapshot.completedWorkerIds());
        Map<ResourceId, Update> terminal = new LinkedHashMap<>();
        snapshot.terminalFailures().forEach((taskId, failure) -> {
            TaskExecutionResult result = snapshot.latestResults().get(taskId);
            if (result != null) {
                terminal.put(taskId, update(
                        result, recovery.get(taskId), active.get(taskId), null));
            } else {
                ResourceId workerId = executor.workers().keySet().stream()
                        .sorted(Comparator.comparing(ResourceId::toString)).findFirst().orElseThrow();
                ResourceId assignmentId = new ResourceId(
                        sessionId.namespace(), sessionId.path() + "/recovered_terminal/"
                                + taskId.namespace() + "/" + taskId.path());
                terminal.put(taskId, new Update(
                        taskId, assignmentId, workerId, executor.executorId(), snapshot.savedTick(),
                        Outcome.TERMINAL_FAILURE, Optional.of(failure.code()), Set.of(),
                        Map.of(ResourceId.parse("fleet:domain"), "construction"), failure.detail()));
            }
        });
        Map<ResourceId, WorkLease> leases = new LinkedHashMap<>();
        snapshot.workPositionReservations().forEach((id, reservation) -> leases.put(id,
                new WorkLease(
                        id, reservation.graphId(), reservation.taskId(), reservation.assignmentId(),
                        reservation.workerId(), reservation.position(),
                        reservation.status() == ReservationStatus.ACTIVE
                                ? LeaseStatus.ACTIVE : LeaseStatus.RELEASED,
                        reservation.generation(), reservation.acquiredTick(),
                        reservation.expiresTick())));
        return new Snapshot(
                sessionId, graph.graphId(), snapshot.graphFingerprint(), active, latest,
                completed, terminal, leases, recovery, snapshot.reassignmentCounts(),
                snapshot.reconciliationRequiredTaskIds(), snapshot.globallyCancelled(),
                snapshot.generation(), snapshot.savedTick());
    }

    private Map<ResourceId, Assignment> neutralAssignments(
            Map<ResourceId, TaskAssignment> supplied, String graphFingerprint) {
        Map<ResourceId, Assignment> mapped = new LinkedHashMap<>();
        supplied.forEach((taskId, assignment) -> mapped.put(taskId, new Assignment(
                assignment.assignmentId(), assignment.sessionId(), assignment.graphId(),
                graphFingerprint, assignment.taskId(), assignment.executorId(),
                assignment.workerId().orElseThrow(), assignment.attempt(),
                assignment.ownership().generation(), assignment.assignedTick(),
                assignment.ownership().expiresTick(),
                assignment.ownership().ownershipTokenHash())));
        return mapped;
    }

    private Map<ResourceId, Update> neutralUpdates(
            Map<ResourceId, TaskExecutionResult> supplied,
            Map<ResourceId, Assignment> recovery,
            Map<ResourceId, Assignment> active,
            Map<ResourceId, ResourceId> completedWorkerIds) {
        Map<ResourceId, Update> mapped = new LinkedHashMap<>();
        supplied.forEach((taskId, result) ->
                mapped.put(taskId, update(
                        result, recovery.get(taskId), active.get(taskId),
                        completedWorkerIds.get(taskId))));
        return mapped;
    }

    private Update update(
            TaskExecutionResult result, Assignment recovery, Assignment active,
            ResourceId completedWorkerId) {
        Assignment assignment = recovery != null ? recovery : active;
        ResourceId workerId = assignment != null ? assignment.workerId()
                : completedWorkerId != null ? completedWorkerId
                : executor.workers().keySet().stream()
                .sorted(Comparator.comparing(ResourceId::toString)).findFirst().orElseThrow();
        Outcome outcome = switch (result.outcome()) {
            case PENDING -> Outcome.PENDING;
            case SUCCEEDED -> Outcome.SUCCEEDED;
            case RETRYABLE_FAILURE -> Outcome.RETRYABLE_FAILURE;
            case CANCELLED -> Outcome.CANCELLED;
            case TERMINAL_FAILURE, RECOVERY_REQUIRED, UNSUPPORTED -> Outcome.TERMINAL_FAILURE;
        };
        Optional<ResourceId> failure = outcome == Outcome.RETRYABLE_FAILURE
                || outcome == Outcome.TERMINAL_FAILURE
                ? result.failure().map(TaskFailure::code) : Optional.empty();
        return new Update(
                result.taskId(), result.assignmentId(), workerId, result.executorId(),
                result.resultTick(), outcome, failure,
                result.evidence().stream().map(ExecutionEvidence::evidenceId)
                        .collect(Collectors.toUnmodifiableSet()),
                Map.of(ResourceId.parse("fleet:domain"), "construction"), result.detail());
    }

    private static Map<ResourceId, ResourceId> workerIds(
            Map<ResourceId, Update> completed) {
        Map<ResourceId, ResourceId> mapped = new LinkedHashMap<>();
        completed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> mapped.put(entry.getKey(), entry.getValue().workerId()));
        return Map.copyOf(mapped);
    }

    private static Map<ResourceId, BlockPos3i> copyWorkPositions(
            Map<ResourceId, BlockPos3i> positions,
            ConstructionTaskGraph graph) {
        Objects.requireNonNull(positions, "verifiedWorkPositions");
        Map<ResourceId, BlockPos3i> copied = new LinkedHashMap<>();
        positions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> copied.put(
                        Objects.requireNonNull(entry.getKey(), "work position reservation id"),
                        Objects.requireNonNull(entry.getValue(), "work position")));
        Set<ResourceId> required = graph.tasks().values().stream()
                .flatMap(task -> task.requiredPlacementReservationIds().stream())
                .collect(Collectors.toSet());
        if (!copied.keySet().equals(required)) {
            throw new IllegalArgumentException(
                    "Verified work positions must exactly cover graph placement reservation identities");
        }
        return Map.copyOf(copied);
    }

    private TaskFailure failure(
            ConstructionFailureCode code,
            ConstructionTask task,
            String detail) {
        return new TaskFailure(
                code.id(), code.category(), false, Optional.empty(), Optional.of(task.taskId()),
                detail, List.of(graph.graphId()),
                "Inspect worker health, exact reconciliation and the bounded retry budget");
    }
}
