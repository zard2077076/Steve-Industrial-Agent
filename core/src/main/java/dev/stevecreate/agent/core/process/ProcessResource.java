package dev.stevecreate.agent.core.process;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/** One typed resource quantity used as a process input, output, or optional byproduct. */
public record ProcessResource(
        ResourceId resourceId,
        GenericResourceType resourceType,
        long amount) {
    public static final long MAX_AMOUNT = 1_000_000_000L;

    public ProcessResource {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourceType, "resourceType");
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException(
                    "amount must be between 1 and " + MAX_AMOUNT);
        }
    }
}
