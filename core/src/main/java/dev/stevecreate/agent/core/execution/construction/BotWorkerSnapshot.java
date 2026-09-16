package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete bounded readback of one controlled Bot; no game entity object is exposed. */
public record BotWorkerSnapshot(
        ResourceId workerId,
        BotWorkerStatus status,
        BlockPos3i position,
        ResourceId authorizedRegionId,
        Set<BotWorkerCapability> capabilities,
        Optional<ResourceId> sessionId,
        Optional<ResourceId> taskId,
        Optional<ResourceId> assignmentId,
        BotInventory inventory,
        int health,
        boolean loaded,
        boolean testOnlyMovement,
        long generation,
        long observedTick) {
    public static final int MAX_HEALTH = 1_024;

    public BotWorkerSnapshot {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(authorizedRegionId, "authorizedRegionId");
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("Bot worker capabilities must not be empty");
        }
        capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(inventory, "inventory");
        if (!inventory.workerId().equals(workerId)) {
            throw new IllegalArgumentException("Bot inventory belongs to another worker");
        }
        boolean assigned = sessionId.isPresent() || taskId.isPresent() || assignmentId.isPresent();
        if (assigned && !(sessionId.isPresent() && taskId.isPresent() && assignmentId.isPresent())) {
            throw new IllegalArgumentException("Bot assignment identity must be complete or absent");
        }
        if ((status == BotWorkerStatus.BUSY) != assigned) {
            throw new IllegalArgumentException("Only a BUSY Bot may carry an assignment identity");
        }
        if (health < 0 || health > MAX_HEALTH
                || (status == BotWorkerStatus.DEAD) != (health == 0)) {
            throw new IllegalArgumentException("Bot health/status is inconsistent");
        }
        if (status == BotWorkerStatus.OFFLINE && loaded) {
            throw new IllegalArgumentException("An offline Bot cannot be marked loaded");
        }
        if (generation < 0 || observedTick < 0) {
            throw new IllegalArgumentException("Bot generation/tick must be non-negative");
        }
    }

    public boolean assignedTo(TaskAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        return status == BotWorkerStatus.BUSY
                && sessionId.filter(assignment.sessionId()::equals).isPresent()
                && taskId.filter(assignment.taskId()::equals).isPresent()
                && assignmentId.filter(assignment.assignmentId()::equals).isPresent();
    }

    public boolean healthyAndLoaded() {
        return loaded && health > 0
                && (status == BotWorkerStatus.IDLE || status == BotWorkerStatus.BUSY);
    }
}
