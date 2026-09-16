package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reload payload; active work is restored only as reconciliation-required, never replayed. */
public record BotFleetCoordinatorSnapshot(
        ResourceId sessionId,
        ResourceId graphId,
        String graphFingerprint,
        Map<ResourceId, TaskAssignment> activeAssignments,
        Map<ResourceId, TaskExecutionResult> latestResults,
        Map<ResourceId, TaskExecutionResult> completedResults,
        Map<ResourceId, ResourceId> completedWorkerIds,
        Map<ResourceId, TaskFailure> terminalFailures,
        Map<ResourceId, WorkPositionReservation> workPositionReservations,
        Map<ResourceId, TaskAssignment> recoveryAssignments,
        Map<ResourceId, Integer> reassignmentCounts,
        Set<ResourceId> reconciliationRequiredTaskIds,
        boolean globallyCancelled,
        long generation,
        long savedTick) {
    public BotFleetCoordinatorSnapshot {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(graphFingerprint, "graphFingerprint");
        if (!graphFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Fleet graph fingerprint must be lowercase SHA-256");
        }
        activeAssignments = Map.copyOf(Objects.requireNonNull(activeAssignments, "activeAssignments"));
        latestResults = Map.copyOf(Objects.requireNonNull(latestResults, "latestResults"));
        completedResults = Map.copyOf(Objects.requireNonNull(completedResults, "completedResults"));
        completedWorkerIds = Map.copyOf(Objects.requireNonNull(
                completedWorkerIds, "completedWorkerIds"));
        terminalFailures = Map.copyOf(Objects.requireNonNull(terminalFailures, "terminalFailures"));
        workPositionReservations = Map.copyOf(Objects.requireNonNull(
                workPositionReservations, "workPositionReservations"));
        recoveryAssignments = Map.copyOf(Objects.requireNonNull(
                recoveryAssignments, "recoveryAssignments"));
        reassignmentCounts = Map.copyOf(Objects.requireNonNull(
                reassignmentCounts, "reassignmentCounts"));
        reconciliationRequiredTaskIds = Set.copyOf(Objects.requireNonNull(
                reconciliationRequiredTaskIds, "reconciliationRequiredTaskIds"));
        if (activeAssignments.size() > BotFleetCoordinator.MAX_WORKERS
                || latestResults.size() > ConstructionTaskGraph.MAX_TASKS
                || completedResults.size() > ConstructionTaskGraph.MAX_TASKS
                || completedWorkerIds.size() > ConstructionTaskGraph.MAX_TASKS
                || terminalFailures.size() > ConstructionTaskGraph.MAX_TASKS
                || workPositionReservations.size() > ConstructionTaskGraph.MAX_TASKS
                || recoveryAssignments.size() > ConstructionTaskGraph.MAX_TASKS
                || reassignmentCounts.size() > ConstructionTaskGraph.MAX_TASKS
                || reconciliationRequiredTaskIds.size() > ConstructionTaskGraph.MAX_TASKS) {
            throw new IllegalArgumentException("Fleet snapshot exceeds a bounded collection limit");
        }
        activeAssignments.forEach((taskId, assignment) -> {
            if (!taskId.equals(assignment.taskId())) {
                throw new IllegalArgumentException("Active assignment key does not match task identity");
            }
        });
        latestResults.forEach((taskId, result) -> requireTaskIdentity(taskId, result, "latest result"));
        completedResults.forEach((taskId, result) ->
                requireTaskIdentity(taskId, result, "completed result"));
        completedWorkerIds.forEach((taskId, workerId) -> {
            Objects.requireNonNull(taskId, "completed worker task id");
            Objects.requireNonNull(workerId, "completed worker id");
        });
        if (!completedWorkerIds.keySet().equals(completedResults.keySet())) {
            throw new IllegalArgumentException(
                    "Every completed fleet result requires its exact worker identity");
        }
        workPositionReservations.forEach((reservationId, reservation) -> {
            if (!reservationId.equals(reservation.reservationId())) {
                throw new IllegalArgumentException("Work reservation key does not match its identity");
            }
        });
        if (!recoveryAssignments.keySet().containsAll(reconciliationRequiredTaskIds)
                || !latestResults.keySet().containsAll(reconciliationRequiredTaskIds)) {
            throw new IllegalArgumentException(
                    "Every reconciliation task requires its exact assignment and result");
        }
        if (generation < 0 || savedTick < 0) {
            throw new IllegalArgumentException("Fleet snapshot generation/tick must be non-negative");
        }
    }

    private static void requireTaskIdentity(
            ResourceId taskId,
            TaskExecutionResult result,
            String kind) {
        if (!Objects.requireNonNull(taskId, kind + " key").equals(result.taskId())) {
            throw new IllegalArgumentException("Fleet " + kind + " key does not match task identity");
        }
    }
}
