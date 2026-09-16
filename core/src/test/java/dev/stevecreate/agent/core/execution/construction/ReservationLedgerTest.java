package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ReservationLedgerTest {
    private static final ResourceId SESSION = id("session:bot_fleet");
    private static final ResourceId TASK = id("task:deliver_iron");
    private static final ResourceId SOURCE = id("source:dedicated_test_chest");
    private static final ResourceId IRON = id("minecraft:iron_ingot");
    private static final ResourceId WORKER = id("worker:one");

    @Test
    void reserveWithdrawDeliverReturnAndCancelNeverDoubleDeduct() {
        ReservationLedger ledger = ledger(10, 8);
        MaterialReservation reserved = success(ledger.reserve(request("reservation:one", 4))).reservation();
        assertThat(reserved.reservedQuantity()).isEqualTo(4);

        MaterialLedgerResult.Success withdrawn = success(ledger.withdraw(
                reserved.reservationId(), id("delivery:one"), WORKER, 4, 2));
        assertThat(withdrawn.source().available(IRON)).isEqualTo(6);
        assertThat(withdrawn.inventory().orElseThrow().quantity(IRON)).isEqualTo(4);

        MaterialLedgerResult.Success delivered = success(ledger.deliver(id("delivery:one"), 3, 3));
        assertThat(delivered.reservation().deliveredQuantity()).isEqualTo(3);
        assertThat(delivered.inventory().orElseThrow().quantity(IRON)).isEqualTo(1);

        MaterialLedgerResult.Success returned = success(ledger.returnToSource(
                id("delivery:one"), 1, 4));
        assertThat(returned.reservation().returnedQuantity()).isEqualTo(1);
        assertThat(returned.source().available(IRON)).isEqualTo(7);
        assertThat(returned.delivery().orElseThrow().status())
                .isEqualTo(MaterialDeliveryStatus.SETTLED);

        MaterialLedgerResult.Success cancelled = success(ledger.cancel(reserved.reservationId(), 5));
        assertThat(cancelled.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(cancelled.reservation().withdrawnQuantity()).isEqualTo(4);
        assertThat(cancelled.reservation().deliveredQuantity()
                + cancelled.reservation().returnedQuantity()).isEqualTo(4);

        assertThat(ledger.deliver(id("delivery:one"), 1, 6))
                .isInstanceOfSatisfying(MaterialLedgerResult.Failure.class, failure ->
                        assertThat(failure.code()).isEqualTo(ConstructionFailureCode.RESERVATION_CONFLICT));
    }

    @Test
    void concurrentBotsCannotOverReserveTheSameSource() throws Exception {
        ReservationLedger ledger = ledger(10, 8);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<MaterialLedgerResult>> contenders = new ArrayList<>();
            contenders.add(() -> ledger.reserve(request("reservation:bot_one", 6)));
            contenders.add(() -> ledger.reserve(new MaterialReservationRequest(
                    id("reservation:bot_two"), SESSION, id("task:bot_two"), SOURCE, IRON, 6, 1)));
            List<MaterialLedgerResult> results = pool.invokeAll(contenders).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertThat(results).filteredOn(MaterialLedgerResult.Success.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(MaterialLedgerResult.Failure.class::isInstance).hasSize(1);
            assertThat(((MaterialLedgerResult.Failure) results.stream()
                    .filter(MaterialLedgerResult.Failure.class::isInstance)
                    .findFirst().orElseThrow()).code())
                    .isEqualTo(ConstructionFailureCode.MATERIAL_INSUFFICIENT);
            assertThat(ledger.reservations().values())
                    .extracting(MaterialReservation::reservedQuantity)
                    .containsExactly(6L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void capacityUnauthorizedSourceAndCarryingCancellationFailClosed() {
        ReservationLedger ledger = ledger(10, 2);
        MaterialReservation reserved = success(ledger.reserve(request("reservation:capacity", 4)))
                .reservation();
        assertThat(ledger.withdraw(
                reserved.reservationId(), id("delivery:capacity"), WORKER, 4, 2))
                .isInstanceOfSatisfying(MaterialLedgerResult.Failure.class, failure ->
                        assertThat(failure.code()).isEqualTo(ConstructionFailureCode.MATERIAL_INSUFFICIENT));

        MaterialReservation small = success(ledger.reserve(new MaterialReservationRequest(
                id("reservation:small"), SESSION, id("task:small"), SOURCE, IRON, 2, 3)))
                .reservation();
        success(ledger.withdraw(small.reservationId(), id("delivery:small"), WORKER, 2, 4));
        assertThat(ledger.cancel(small.reservationId(), 5))
                .isInstanceOfSatisfying(MaterialLedgerResult.Failure.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION));

        ReservationLedger unauthorized = new ReservationLedger(
                MaterialReturnPolicy.RETURN_TO_ORIGINAL_SOURCE);
        unauthorized.registerSource(new MaterialSource(
                SOURCE,
                MaterialSourceScope.AUTHORIZED_DEDICATED_CONTAINER,
                new BlockPos3i(0, 64, 0),
                Set.of(id("session:someone_else")),
                Map.of(IRON, 10L),
                true,
                0,
                0));
        assertThat(unauthorized.reserve(request("reservation:unauthorized", 1)))
                .isInstanceOfSatisfying(MaterialLedgerResult.Failure.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                ConstructionFailureCode.MATERIAL_SOURCE_FORBIDDEN));
    }

    @Test
    void reloadPreservesOutstandingCarryAndRequiresExactReturn() {
        ReservationLedger ledger = ledger(10, 8);
        MaterialReservation reservation = success(ledger.reserve(request("reservation:reload", 4)))
                .reservation();
        success(ledger.withdraw(
                reservation.reservationId(), id("delivery:reload"), WORKER, 4, 2));
        success(ledger.deliver(id("delivery:reload"), 1, 3));

        ReservationLedger restored = ReservationLedger.restore(ledger.snapshot(20));
        assertThat(restored.inventories().get(WORKER).quantity(IRON)).isEqualTo(3);
        assertThat(restored.deliveries().get(id("delivery:reload")).outstandingQuantity())
                .isEqualTo(3);
        MaterialLedgerResult.Success returned = success(restored.returnToSource(
                id("delivery:reload"), 3, 21));
        assertThat(returned.source().available(IRON)).isEqualTo(9);
        assertThat(success(restored.cancel(reservation.reservationId(), 22))
                .reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThatThrownBy(() -> restored.snapshot(21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void reloadRejectsDeliveryArithmeticOrInventoryTampering() {
        ReservationLedger ledger = ledger(10, 8);
        MaterialReservation reservation = success(ledger.reserve(request("reservation:tamper", 4)))
                .reservation();
        success(ledger.withdraw(
                reservation.reservationId(), id("delivery:tamper"), WORKER, 4, 2));
        ReservationLedgerSnapshot valid = ledger.snapshot(10);

        Map<ResourceId, BotInventory> emptyInventory = Map.of(
                WORKER, new BotInventory(WORKER, 8, Map.of(), 0, 10));
        ReservationLedgerSnapshot tampered = new ReservationLedgerSnapshot(
                valid.sources(),
                valid.reservations(),
                emptyInventory,
                valid.deliveries(),
                valid.returnPolicy(),
                valid.generation(),
                valid.savedTick());

        assertThatThrownBy(() -> ReservationLedger.restore(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks carried material");
    }

    private static ReservationLedger ledger(long sourceQuantity, int inventoryCapacity) {
        ReservationLedger ledger = new ReservationLedger(
                MaterialReturnPolicy.RETURN_TO_ORIGINAL_SOURCE);
        ledger.registerSource(new MaterialSource(
                SOURCE,
                MaterialSourceScope.TEST_ONLY_BOUNDED_SOURCE,
                new BlockPos3i(0, 64, 0),
                Set.of(SESSION),
                Map.of(IRON, sourceQuantity),
                true,
                0,
                0));
        ledger.registerInventory(new BotInventory(WORKER, inventoryCapacity, Map.of(), 0, 0));
        return ledger;
    }

    private static MaterialReservationRequest request(String reservationId, long quantity) {
        return new MaterialReservationRequest(
                id(reservationId), SESSION, TASK, SOURCE, IRON, quantity, 1);
    }

    private static MaterialLedgerResult.Success success(MaterialLedgerResult result) {
        assertThat(result).isInstanceOf(MaterialLedgerResult.Success.class);
        return (MaterialLedgerResult.Success) result;
    }
}
