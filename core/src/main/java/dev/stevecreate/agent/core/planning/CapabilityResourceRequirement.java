package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/** A bounded typed power or resource requirement declared by one machine capability. */
public record CapabilityResourceRequirement(
        GenericResourceType resourceType,
        long minimumAmount,
        boolean continuous) {
    public static final long MAXIMUM_AMOUNT = 1_000_000_000_000L;

    public CapabilityResourceRequirement {
        Objects.requireNonNull(resourceType, "resourceType");
        if (minimumAmount <= 0 || minimumAmount > MAXIMUM_AMOUNT) {
            throw new IllegalArgumentException("minimumAmount must be positive and bounded");
        }
    }
}
