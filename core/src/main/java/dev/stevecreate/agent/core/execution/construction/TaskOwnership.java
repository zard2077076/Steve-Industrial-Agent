package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Exact renewable task lease; an in-memory owner boolean is intentionally insufficient. */
public record TaskOwnership(
        ResourceId sessionId,
        ResourceId graphId,
        ResourceId taskId,
        ResourceId executorId,
        ExecutionMode mode,
        Optional<ResourceId> workerId,
        long generation,
        long acquiredTick,
        long expiresTick,
        String ownershipTokenHash) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public TaskOwnership {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(mode, "mode");
        workerId = Objects.requireNonNull(workerId, "workerId");
        if (mode == ExecutionMode.BOTS && workerId.isEmpty()) {
            throw new IllegalArgumentException("Bot ownership requires an exact worker identity");
        }
        if (mode == ExecutionMode.DIRECT && workerId.isPresent()) {
            throw new IllegalArgumentException("Direct ownership cannot claim a Bot worker identity");
        }
        if (generation < 0 || acquiredTick < 0 || expiresTick <= acquiredTick) {
            throw new IllegalArgumentException("Ownership generation/ticks must be non-negative with future expiry");
        }
        Objects.requireNonNull(ownershipTokenHash, "ownershipTokenHash");
        if (!SHA_256.matcher(ownershipTokenHash).matches()) {
            throw new IllegalArgumentException("ownershipTokenHash must be a lowercase SHA-256 value");
        }
    }

    public boolean validAt(long tick) {
        return tick >= acquiredTick && tick < expiresTick;
    }
}
