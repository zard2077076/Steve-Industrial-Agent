package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;

/** Inclusive relative region whose clearance or contents must be observed at runtime. */
public record CapabilityZoneRequirement(
        String zoneId,
        BlockPos3i minimum,
        BlockPos3i maximum,
        boolean obstructionFree,
        boolean itemEntitiesForbidden,
        boolean liveReadbackRequired) {
    public CapabilityZoneRequirement {
        zoneId = requireText(zoneId, "zoneId");
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (minimum.x() > maximum.x() || minimum.y() > maximum.y()
                || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("Capability zone bounds are inverted");
        }
        if (!liveReadbackRequired) {
            throw new IllegalArgumentException("Capability zones require live readback");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 96) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
