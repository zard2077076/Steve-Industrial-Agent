package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable coordinator readback for one bounded fleet tick or global cancellation. */
public record BotFleetTickResult(
        ResourceId sessionId,
        long tick,
        Map<ResourceId, TaskAssignment> activeAssignments,
        Map<ResourceId, TaskExecutionResult> latestResults,
        Map<ResourceId, TaskExecutionResult> completedResults,
        Map<ResourceId, TaskFailure> terminalFailures,
        Map<ResourceId, WorkPositionReservation> workPositionReservations,
        List<ResourceId> newlyAssignedTaskIds,
        Optional<DeadlockDetector.Deadlock> deadlock,
        boolean globallyCancelled) {
    public BotFleetTickResult {
        Objects.requireNonNull(sessionId, "sessionId");
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        activeAssignments = Map.copyOf(Objects.requireNonNull(activeAssignments, "activeAssignments"));
        latestResults = Map.copyOf(Objects.requireNonNull(latestResults, "latestResults"));
        completedResults = Map.copyOf(Objects.requireNonNull(completedResults, "completedResults"));
        terminalFailures = Map.copyOf(Objects.requireNonNull(terminalFailures, "terminalFailures"));
        workPositionReservations = Map.copyOf(Objects.requireNonNull(
                workPositionReservations, "workPositionReservations"));
        newlyAssignedTaskIds = List.copyOf(Objects.requireNonNull(
                newlyAssignedTaskIds, "newlyAssignedTaskIds"));
        deadlock = Objects.requireNonNull(deadlock, "deadlock");
    }
}
