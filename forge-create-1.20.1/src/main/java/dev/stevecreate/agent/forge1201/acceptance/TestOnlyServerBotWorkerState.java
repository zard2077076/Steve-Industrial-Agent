package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.construction.ReservationLedgerSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Safe idle-boundary reload payload for the repository-owned Bot GameTest. */
record TestOnlyServerBotWorkerState(
        ResourceId workerId,
        ResourceId sessionId,
        UUID entityId,
        ReservationLedgerSnapshot ledgerSnapshot,
        Map<BlockPos, OwnedBlock> ownedBlocks,
        long generation,
        long savedTick) {
    TestOnlyServerBotWorkerState {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(ledgerSnapshot, "ledgerSnapshot");
        ownedBlocks = Map.copyOf(Objects.requireNonNull(ownedBlocks, "ownedBlocks"));
        if (ownedBlocks.size() > 256 || generation < 0 || savedTick < 0) {
            throw new IllegalArgumentException("Bot worker reload payload is unbounded or time-invalid");
        }
        ownedBlocks.forEach((position, owned) -> {
            Objects.requireNonNull(position, "owned block position");
            Objects.requireNonNull(owned, "owned block value");
            if (!position.equals(owned.position())) {
                throw new IllegalArgumentException("Owned block map key does not match its position");
            }
        });
    }

    record OwnedBlock(
            BlockPos position,
            ResourceId sessionId,
            ResourceId taskId,
            BlockState beforeState,
            BlockState afterState) {
        OwnedBlock {
            position = Objects.requireNonNull(position, "position").immutable();
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(beforeState, "beforeState");
            Objects.requireNonNull(afterState, "afterState");
            if (beforeState.equals(afterState)) {
                throw new IllegalArgumentException("Owned block must describe one exact state change");
            }
        }
    }
}
