package dev.stevecreate.agent.core.execution.construction;

import java.util.Objects;

/** One exact server-observed container slot. */
public record MaterialSlotSnapshot(int slot, MaterialIdentity identity, long quantity) {
    public MaterialSlotSnapshot {
        if (slot < 0 || slot >= 4_096) throw new IllegalArgumentException("slot is outside the bound");
        Objects.requireNonNull(identity, "identity");
        if (quantity < 1 || quantity > 1_000_000_000L) {
            throw new IllegalArgumentException("slot quantity is outside the bound");
        }
    }
}
