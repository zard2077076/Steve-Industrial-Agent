package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The one reading of the durable material ledger.
 *
 * <p>Two places derived this independently — the completion report and the reload
 * gate's withdrawal total — each with its own copy of which states count as having left
 * the player's chest. A new state added to one and not the other would make the reload
 * gate and the report disagree about how much was taken, which is precisely the
 * accounting the industrial order envelope exists to guarantee. Disagreeing quietly is
 * worse than either answer being wrong.</p>
 *
 * <p>A reservation has one authoritative current row; earlier rows for the same
 * reservation are superseded history, not additional material.</p>
 */
public final class MaterialLedgerProjection {
    private MaterialLedgerProjection() {}

    /**
     * Whether a state means the item is no longer in the player's container.
     *
     * <p>{@code RETURNED} counts: the item did leave, and the return is recorded
     * separately. Netting it out here would make a completed order look as though it
     * never withdrew anything.</p>
     */
    public static boolean countsAsWithdrawn(MaterialTransactionState state) {
        return switch (state) {
            case WITHDRAWN, DELIVERED, CONSUMED, RETURN_PENDING, RETURNED -> true;
            default -> false;
        };
    }

    /** The current row per reservation, later rows superseding earlier ones. */
    public static Map<UUID, PlayerMaterialSavedData.Transaction> latestPerReservation(
            List<PlayerMaterialSavedData.Transaction> transactions) {
        Objects.requireNonNull(transactions, "transactions");
        Map<UUID, PlayerMaterialSavedData.Transaction> latest = new LinkedHashMap<>();
        transactions.forEach(value -> latest.put(value.reservationId(), value));
        return latest;
    }

    /** Everything the ledger says left the player's containers. */
    public static long withdrawnTotal(PlayerMaterialSavedData.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        return latestPerReservation(entry.transactions()).values().stream()
                .filter(transaction -> countsAsWithdrawn(transaction.state()))
                .mapToLong(PlayerMaterialSavedData.Transaction::quantity)
                .sum();
    }

    /**
     * Per-item evidence for the completion report.
     *
     * <p>The legacy player report keeps aggregate counters for its UI; the shared order
     * envelope must retain exact identities so a later adapter cannot mistake "some
     * materials" for the requested ones.</p>
     */
    public static Evidence project(PlayerMaterialSavedData.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        Map<UUID, PlayerMaterialSavedData.Reservation> reservations = new LinkedHashMap<>();
        entry.reservations().forEach(value -> reservations.put(value.reservationId(), value));
        Map<ResourceId, Long> withdrawn = new LinkedHashMap<>();
        Map<ResourceId, Long> consumed = new LinkedHashMap<>();
        Map<ResourceId, Long> returned = new LinkedHashMap<>();
        latestPerReservation(entry.transactions()).values().forEach(transaction -> {
            PlayerMaterialSavedData.Reservation reservation =
                    reservations.get(transaction.reservationId());
            if (reservation == null) return;
            ResourceId item = reservation.identity().itemId();
            if (countsAsWithdrawn(transaction.state())) {
                withdrawn.merge(item, transaction.quantity(), Math::addExact);
            }
            if (transaction.state() == MaterialTransactionState.CONSUMED) {
                consumed.merge(item, transaction.quantity(), Math::addExact);
            } else if (transaction.state() == MaterialTransactionState.RETURNED) {
                returned.merge(item, transaction.quantity(), Math::addExact);
            }
        });
        return new Evidence(Map.copyOf(withdrawn), Map.copyOf(consumed), Map.copyOf(returned));
    }

    public record Evidence(
            Map<ResourceId, Long> withdrawn,
            Map<ResourceId, Long> consumed,
            Map<ResourceId, Long> returned) {
        public Evidence {
            withdrawn = Map.copyOf(Objects.requireNonNull(withdrawn, "withdrawn"));
            consumed = Map.copyOf(Objects.requireNonNull(consumed, "consumed"));
            returned = Map.copyOf(Objects.requireNonNull(returned, "returned"));
        }
    }
}
