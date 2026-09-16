package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One required or optional relative component; never an unchecked world coordinate. */
public record CapabilityComponentRequirement(
        CapabilityComponentRole role,
        BlockPos3i relativePosition,
        List<ResourceId> acceptedBlockIds,
        boolean required,
        boolean blockEntityRequired,
        boolean liveReadbackRequired) {
    public CapabilityComponentRequirement {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(relativePosition, "relativePosition");
        acceptedBlockIds = List.copyOf(Objects.requireNonNull(acceptedBlockIds, "acceptedBlockIds"));
        if (acceptedBlockIds.isEmpty() && role != CapabilityComponentRole.ITEM_PROCESSING_LANE
                && role != CapabilityComponentRole.ITEM_INPUT
                && role != CapabilityComponentRole.ITEM_OUTPUT
                && role != CapabilityComponentRole.HELD_ITEM
                && role != CapabilityComponentRole.ROTATIONAL_POWER_INPUT) {
            throw new IllegalArgumentException("Physical component role requires an accepted block ID");
        }
        if (!acceptedBlockIds.equals(acceptedBlockIds.stream()
                .distinct().sorted(Comparator.comparing(ResourceId::toString)).toList())) {
            throw new IllegalArgumentException("acceptedBlockIds must be unique and canonically sorted");
        }
        if (required && !liveReadbackRequired) {
            throw new IllegalArgumentException("Required capability components need live readback");
        }
    }
}
