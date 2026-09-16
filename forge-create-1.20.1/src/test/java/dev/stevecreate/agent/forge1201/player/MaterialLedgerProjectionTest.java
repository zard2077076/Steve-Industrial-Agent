package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The ledger reading that both the completion report and the reload gate depend on.
 *
 * <p>Its central risk is not being wrong but being inconsistent: the two callers used
 * to derive it separately, so they could disagree about how much a player was charged
 * while each looked internally correct.</p>
 */
class MaterialLedgerProjectionTest {
    private static final ResourceId LOG = ResourceId.parse("minecraft:oak_log");
    private static final ResourceId SHAFT = ResourceId.parse("create:shaft");

    @Test
    void countsOnlyTheCurrentRowPerReservation() {
        UUID reservation = UUID.nameUUIDFromBytes("a".getBytes());
        PlayerMaterialSavedData.Entry entry = entry(
                List.of(reservation(reservation, LOG, 4)),
                List.of(transaction(reservation, MaterialTransactionState.WITHDRAWN, 4),
                        transaction(reservation, MaterialTransactionState.DELIVERED, 4),
                        transaction(reservation, MaterialTransactionState.CONSUMED, 4)));

        assertThat(MaterialLedgerProjection.withdrawnTotal(entry)).isEqualTo(4);
        assertThat(MaterialLedgerProjection.project(entry).consumed())
                .containsExactlyInAnyOrderEntriesOf(Map.of(LOG, 4L));
    }

    /** A returned item still left the chest; the return is recorded separately. */
    @Test
    void treatsAReturnedItemAsHavingBeenWithdrawn() {
        UUID reservation = UUID.nameUUIDFromBytes("b".getBytes());
        PlayerMaterialSavedData.Entry entry = entry(
                List.of(reservation(reservation, LOG, 3)),
                List.of(transaction(reservation, MaterialTransactionState.RETURNED, 3)));

        MaterialLedgerProjection.Evidence evidence = MaterialLedgerProjection.project(entry);

        assertThat(MaterialLedgerProjection.withdrawnTotal(entry)).isEqualTo(3);
        assertThat(evidence.withdrawn()).containsExactlyInAnyOrderEntriesOf(Map.of(LOG, 3L));
        assertThat(evidence.returned()).containsExactlyInAnyOrderEntriesOf(Map.of(LOG, 3L));
        assertThat(evidence.consumed()).isEmpty();
    }

    /** A released reservation never left the chest and must not be charged. */
    @Test
    void ignoresReservationsThatWereReleased() {
        UUID reservation = UUID.nameUUIDFromBytes("c".getBytes());
        PlayerMaterialSavedData.Entry entry = entry(
                List.of(reservation(reservation, LOG, 5)),
                List.of(transaction(reservation, MaterialTransactionState.WITHDRAWN, 5),
                        transaction(reservation, MaterialTransactionState.RELEASED, 5)));

        assertThat(MaterialLedgerProjection.withdrawnTotal(entry)).isZero();
        assertThat(MaterialLedgerProjection.project(entry).withdrawn()).isEmpty();
    }

    /**
     * The invariant that matters: the total and the per-item evidence are two readings
     * of one ledger and must never disagree, whatever the mix of states.
     */
    @Test
    void theTotalAlwaysEqualsTheSumOfThePerItemEvidence() {
        List<PlayerMaterialSavedData.Reservation> reservations = new ArrayList<>();
        List<PlayerMaterialSavedData.Transaction> transactions = new ArrayList<>();
        MaterialTransactionState[] states = MaterialTransactionState.values();
        for (int index = 0; index < states.length; index++) {
            UUID reservation = UUID.nameUUIDFromBytes(("mix" + index).getBytes());
            reservations.add(reservation(reservation, index % 2 == 0 ? LOG : SHAFT, index + 1));
            transactions.add(transaction(reservation, states[index], index + 1));
        }
        PlayerMaterialSavedData.Entry entry = entry(reservations, transactions);

        long total = MaterialLedgerProjection.withdrawnTotal(entry);
        long summed = MaterialLedgerProjection.project(entry).withdrawn().values().stream()
                .mapToLong(Long::longValue).sum();

        assertThat(summed).isEqualTo(total);
    }

    @Test
    void dropsTransactionsWhoseReservationIsGone() {
        UUID orphan = UUID.nameUUIDFromBytes("orphan".getBytes());
        PlayerMaterialSavedData.Entry entry = entry(List.of(),
                List.of(transaction(orphan, MaterialTransactionState.CONSUMED, 9)));

        assertThat(MaterialLedgerProjection.project(entry).withdrawn()).isEmpty();
    }

    private static PlayerMaterialSavedData.Reservation reservation(
            UUID id, ResourceId item, long quantity) {
        return new PlayerMaterialSavedData.Reservation(id,
                ResourceId.parse("material:line_000"), UUID.nameUUIDFromBytes("src".getBytes()),
                0, new MaterialIdentity(item, MaterialIdentity.EMPTY_PAYLOAD_SHA256),
                quantity, 1L);
    }

    private static PlayerMaterialSavedData.Transaction transaction(
            UUID reservation, MaterialTransactionState state, long quantity) {
        return new PlayerMaterialSavedData.Transaction(UUID.randomUUID(), reservation,
                "composite", MaterialExecutorKind.DIRECT, state, quantity, 1L);
    }

    private static PlayerMaterialSavedData.Entry entry(
            List<PlayerMaterialSavedData.Reservation> reservations,
            List<PlayerMaterialSavedData.Transaction> transactions) {
        return new PlayerMaterialSavedData.Entry(
                UUID.nameUUIDFromBytes("project".getBytes()),
                UUID.nameUUIDFromBytes("player".getBytes()),
                ResourceId.parse("minecraft:overworld"),
                Map.of(LOG, 1L),
                "a".repeat(64), "runtime", List.of(), reservations, transactions,
                List.of(), null, false, 1L, 1L, "TEST", null);
    }
}
