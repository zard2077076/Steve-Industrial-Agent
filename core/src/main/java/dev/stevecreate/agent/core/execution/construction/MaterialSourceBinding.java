package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Server-authoritative binding to one player-selected inventory capability. */
public record MaterialSourceBinding(
        ResourceId sourceId,
        ResourceId projectId,
        String ownerId,
        ResourceId dimension,
        BlockPos3i position,
        String accessFace,
        String blockEntityType,
        String blockStateSha256,
        String inventorySha256,
        int priority,
        long maximumWithdrawal,
        long boundAtEpochMillis,
        long expiresAtEpochMillis,
        List<MaterialSlotSnapshot> slots) {
    public MaterialSourceBinding {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(projectId, "projectId");
        ownerId = text(ownerId, "ownerId", 96);
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        accessFace = text(accessFace, "accessFace", 32);
        blockEntityType = text(blockEntityType, "blockEntityType", 160);
        blockStateSha256 = hash(blockStateSha256, "blockStateSha256");
        inventorySha256 = hash(inventorySha256, "inventorySha256");
        if (priority < 0 || priority >= 8 || maximumWithdrawal < 1
                || boundAtEpochMillis < 0 || expiresAtEpochMillis <= boundAtEpochMillis) {
            throw new IllegalArgumentException("material source authority is invalid");
        }
        Objects.requireNonNull(slots, "slots");
        if (slots.size() > 4_096) throw new IllegalArgumentException("source slots are unbounded");
        List<MaterialSlotSnapshot> ordered = slots.stream()
                .sorted(Comparator.comparingInt(MaterialSlotSnapshot::slot)).toList();
        if (ordered.stream().map(MaterialSlotSnapshot::slot).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("source contains duplicate slots");
        }
        slots = List.copyOf(ordered);
    }

    public long quantity(MaterialIdentity identity) {
        return slots.stream().filter(slot -> slot.identity().equals(identity))
                .mapToLong(MaterialSlotSnapshot::quantity).sum();
    }

    private static String text(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
