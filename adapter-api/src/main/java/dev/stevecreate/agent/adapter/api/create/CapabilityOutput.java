package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/** One runtime item output with an exact bounded probability. */
public record CapabilityOutput(
        int outputIndex,
        ProcessResource resource,
        int probabilityPerMillion,
        boolean primary) {
    public static final int PROBABILITY_DENOMINATOR = 1_000_000;

    public CapabilityOutput {
        if (outputIndex < 0 || outputIndex > 4_095) {
            throw new IllegalArgumentException("outputIndex is outside its bound");
        }
        Objects.requireNonNull(resource, "resource");
        if (resource.resourceType() != GenericResourceType.ITEM) {
            throw new IllegalArgumentException("CapabilityOutput is item-only");
        }
        if (probabilityPerMillion < 1 || probabilityPerMillion > PROBABILITY_DENOMINATOR) {
            throw new IllegalArgumentException("probabilityPerMillion is outside (0, 1]");
        }
    }

    public boolean guaranteed() {
        return probabilityPerMillion == PROBABILITY_DENOMINATOR;
    }
}
