package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PlayerMaterialSavedDataTest {
    @Test
    void roundTripsSourcesReservationsTransactionsAndBalancedCompletionEvidence() {
        UUID project = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        MaterialIdentity identity = new MaterialIdentity(ResourceId.parse("create:shaft"),
                MaterialIdentity.EMPTY_PAYLOAD_SHA256);
        var slot = new PlayerMaterialSavedData.Slot(3, identity, 9);
        var sourceEntry = new PlayerMaterialSavedData.Source(source, new BlockPos3i(4, 70, 6),
                "up", "minecraft:chest", "a".repeat(64), "b".repeat(64), 0, 12,
                100, 1_000, false, List.of(slot));
        var reserved = new PlayerMaterialSavedData.Reservation(reservation,
                ResourceId.parse("create:shaft"), source, 3, identity, 4, 1_000);
        var transaction = new PlayerMaterialSavedData.Transaction(UUID.randomUUID(), reservation,
                "task-17", MaterialExecutorKind.BOT, MaterialTransactionState.RETURNED, 1, 44);
        var report = new PlayerMaterialSavedData.CompletionReport(
                4, 4, 3, 1, 0, 1, 1, 0, 0, 0, 0, true);
        var expected = new PlayerMaterialSavedData.Entry(project, player,
                ResourceId.parse("minecraft:overworld"), Map.of(ResourceId.parse("create:shaft"), 4L),
                "c".repeat(64), "runtime:test", List.of(sourceEntry), List.of(reserved),
                List.of(transaction), List.of(new PlayerMaterialSavedData.JournalEvent(
                        1, transaction.transactionId(), MaterialTransactionState.RETURNED, 44,
                        "MATERIALS_RETURNED")), new PlayerMaterialSavedData.Logistics(
                        new BlockPos3i(8, 70, 8), new BlockPos3i(9, 70, 8)),
                false, 100, 200, "COMPLETED", report);
        PlayerMaterialSavedData data = new PlayerMaterialSavedData();
        data.put(expected);

        CompoundTag encoded = data.save(new CompoundTag());
        PlayerMaterialSavedData decoded = PlayerMaterialSavedData.load(encoded);

        assertThat(decoded.entry(project)).contains(expected);
        assertThat(encoded.toString()).doesNotContain("password", "player_inventory");

        encoded.putInt("Schema", 1);
        encoded.getList("Projects", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).remove("Logistics");
        assertThat(PlayerMaterialSavedData.load(encoded).entry(project).orElseThrow().logistics())
                .isNull();
    }

    @Test
    void everyTransactionTransitionAppendsAnOrderedDurableJournalEvent() {
        UUID project = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        MaterialIdentity identity = new MaterialIdentity(ResourceId.parse("create:shaft"),
                MaterialIdentity.EMPTY_PAYLOAD_SHA256);
        var sourceEntry = new PlayerMaterialSavedData.Source(source, new BlockPos3i(4, 70, 6),
                "up", "minecraft:chest", "a".repeat(64), "b".repeat(64), 0, 4,
                100, 1_000, false, List.of(new PlayerMaterialSavedData.Slot(0, identity, 4)));
        var reservation = new PlayerMaterialSavedData.Reservation(reservationId,
                ResourceId.parse("create:shaft"), source, 0, identity, 4, 1_000);
        var entry = new PlayerMaterialSavedData.Entry(project, player,
                ResourceId.parse("minecraft:overworld"), Map.of(ResourceId.parse("create:shaft"), 4L),
                "c".repeat(64), "runtime:test", List.of(sourceEntry), List.of(reservation),
                List.of(), List.of(), null, false, 100, 100, "READY", null);
        for (MaterialTransactionState state : List.of(MaterialTransactionState.PREPARED,
                MaterialTransactionState.WITHDRAWN, MaterialTransactionState.DELIVERED,
                MaterialTransactionState.RETURN_PENDING, MaterialTransactionState.RETURNED)) {
            entry = entry.withTransactions(List.of(new PlayerMaterialSavedData.Transaction(
                    transactionId, reservationId, "task-17", MaterialExecutorKind.BOT,
                    state, 4, 44 + entry.journal().size())), 200 + entry.journal().size(),
                    state.name(), null);
        }
        assertThat(entry.journal()).extracting(PlayerMaterialSavedData.JournalEvent::state)
                .containsExactly(MaterialTransactionState.PREPARED, MaterialTransactionState.WITHDRAWN,
                        MaterialTransactionState.DELIVERED, MaterialTransactionState.RETURN_PENDING,
                        MaterialTransactionState.RETURNED);
        assertThat(entry.journal()).extracting(PlayerMaterialSavedData.JournalEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);

        PlayerMaterialSavedData data = new PlayerMaterialSavedData();
        data.put(entry);
        assertThat(PlayerMaterialSavedData.load(data.save(new CompoundTag())).entry(project))
                .contains(entry);
    }
}
