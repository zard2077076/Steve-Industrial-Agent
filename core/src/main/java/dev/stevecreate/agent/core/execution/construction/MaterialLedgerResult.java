package dev.stevecreate.agent.core.execution.construction;

import java.util.Objects;
import java.util.Optional;

/** Typed atomic result for a reservation-ledger state transition. */
public sealed interface MaterialLedgerResult
        permits MaterialLedgerResult.Success, MaterialLedgerResult.Failure {
    record Success(
            MaterialReservation reservation,
            MaterialSource source,
            Optional<BotInventory> inventory,
            Optional<MaterialDelivery> delivery) implements MaterialLedgerResult {
        public Success {
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(source, "source");
            inventory = Objects.requireNonNull(inventory, "inventory");
            delivery = Objects.requireNonNull(delivery, "delivery");
        }
    }

    record Failure(ConstructionFailureCode code, String detail) implements MaterialLedgerResult {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank() || detail.length() > 2_048) {
                throw new IllegalArgumentException("Material ledger failure detail is blank or too long");
            }
        }
    }
}
