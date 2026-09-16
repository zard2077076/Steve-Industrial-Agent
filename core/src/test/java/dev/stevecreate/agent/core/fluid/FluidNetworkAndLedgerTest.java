package dev.stevecreate.agent.core.fluid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FluidNetworkAndLedgerTest {
    private static final FluidIdentity CREOSOTE = new FluidIdentity(
            id("immersiveengineering:creosote"), FluidIdentity.EMPTY_COMPONENT_SHA256);

    @Test
    void verifiesPumpDirectionCapacityAndExactFluidIdentity() {
        FluidNetworkNode tank = node("tank", FluidNodeKind.TANK, Optional.of(CREOSOTE),
                1_000, 4_000, Set.of(Direction6.WEST), Set.of(Direction6.EAST), 0);
        FluidNetworkNode pump = node("pump", FluidNodeKind.PUMP, Optional.empty(),
                0, 1_000, Set.of(Direction6.WEST), Set.of(Direction6.EAST), 250);
        FluidNetworkNode machine = node("machine", FluidNodeKind.MACHINE_INPUT, Optional.empty(),
                0, 2_000, Set.of(Direction6.WEST), Set.of(), 0);
        FluidNetworkGraph graph = new FluidNetworkGraph(id("fluid:test"), "world",
                id("minecraft:overworld"), List.of(tank, pump, machine),
                List.of(route("tank_pump", tank, pump, true),
                        route("pump_machine", pump, machine, true)), 0);

        assertThat(new FluidNetworkVerifier().verify(graph).accepted()).isTrue();
    }

    @Test
    void journalsWithdrawalDeliveryConsumptionAndBalancesMillibuckets() {
        ProjectFluidLedger ledger = new ProjectFluidLedger();
        ResourceId transaction = id("fluid_tx:one");
        ResourceId project = id("project:one");
        ledger.prepare(transaction, project, id("task:refinery"), id("tank:one"),
                CREOSOTE, 1_000, 1);
        ledger.advance(transaction, FluidTransactionState.WITHDRAWN, 2);
        ledger.advance(transaction, FluidTransactionState.DELIVERED, 3);
        ledger.advance(transaction, FluidTransactionState.CONSUMED, 4);

        assertThat(ledger.balance(project)).satisfies(report -> {
            assertThat(report.withdrawnMb()).isEqualTo(1_000);
            assertThat(report.consumedMb()).isEqualTo(1_000);
            assertThat(report.unaccountedMb()).isZero();
            assertThat(report.balanced()).isTrue();
            assertThat(report.duplicateWithdrawals()).isZero();
            assertThat(report.duplicateReturns()).isZero();
        });
    }

    /**
     * The case the balance check exists for, and could not previously report.
     *
     * <p>RETURN_PENDING means the fluid left the source, the delivery diverged, and the
     * reclaim did not finish. It is in neither tank. The ledger used to count it as
     * outstanding, which cancelled it against the withdrawal and made balanced() true —
     * so a ledger reporting health was the guaranteed outcome of the one failure it is
     * supposed to catch.</p>
     */
    @Test
    void refusesToBalanceWhenAReturnNeverCompleted() {
        ProjectFluidLedger ledger = new ProjectFluidLedger();
        ResourceId transaction = id("fluid_tx:stuck");
        ResourceId project = id("project:stuck");
        ledger.prepare(transaction, project, id("task:refinery"), id("tank:one"),
                CREOSOTE, 750, 1);
        ledger.advance(transaction, FluidTransactionState.WITHDRAWN, 2);
        ledger.advance(transaction, FluidTransactionState.RETURN_PENDING, 3);

        assertThat(ledger.balance(project)).satisfies(report -> {
            assertThat(report.balanced()).isFalse();
            assertThat(report.unaccountedMb()).isEqualTo(750);
            // Not outstanding: outstanding is fluid that is where it should be.
            assertThat(report.outstandingMb()).isZero();
        });

        // And once the return completes, the ledger balances again.
        ledger.advance(transaction, FluidTransactionState.RETURNED, 4);
        assertThat(ledger.balance(project)).satisfies(report -> {
            assertThat(report.balanced()).isTrue();
            assertThat(report.returnedMb()).isEqualTo(750);
            assertThat(report.unaccountedMb()).isZero();
        });
    }

    /**
     * Two transactions are not one duplicated transaction.
     *
     * <p>The duplicate counters were hard-coded zeros, so the assertions on them could
     * not fail. Counting them for real introduces the opposite risk — a count taken over
     * the whole journal would call the second transaction's withdrawal a duplicate — and
     * this is what separates the two.</p>
     */
    @Test
    void countsDuplicatesPerTransactionRatherThanPerProject() {
        ProjectFluidLedger ledger = new ProjectFluidLedger();
        ResourceId project = id("project:two");
        ResourceId first = id("fluid_tx:first");
        ResourceId second = id("fluid_tx:second");
        ledger.prepare(first, project, id("task:a"), id("tank:one"), CREOSOTE, 100, 1);
        ledger.advance(first, FluidTransactionState.WITHDRAWN, 2);
        ledger.prepare(second, project, id("task:b"), id("tank:two"), CREOSOTE, 200, 3);
        ledger.advance(second, FluidTransactionState.WITHDRAWN, 4);

        assertThat(ledger.balance(project)).satisfies(report -> {
            assertThat(report.duplicateWithdrawals()).isZero();
            assertThat(report.withdrawnMb()).isEqualTo(300);
            assertThat(report.outstandingMb()).isEqualTo(300);
            assertThat(report.balanced()).isTrue();
        });

        // A different project's transactions must not appear in this project's count.
        ResourceId other = id("project:other");
        ResourceId third = id("fluid_tx:third");
        ledger.prepare(third, other, id("task:c"), id("tank:three"), CREOSOTE, 400, 5);
        ledger.advance(third, FluidTransactionState.WITHDRAWN, 6);
        assertThat(ledger.balance(project).withdrawnMb()).isEqualTo(300);
        assertThat(ledger.balance(other).withdrawnMb()).isEqualTo(400);
    }

    @Test
    void restoresTheLegalDeliveredThenReturnedPathAndRejectsTransactionIdReuse() {
        ProjectFluidLedger ledger = new ProjectFluidLedger();
        ResourceId project = id("project:return_after_delivery");
        ResourceId transaction = id("fluid_tx:return_after_delivery");
        ledger.prepare(transaction, project, id("task:machine"), id("tank:source"),
                CREOSOTE, 250, 1);
        ledger.advance(transaction, FluidTransactionState.WITHDRAWN, 2);
        ledger.advance(transaction, FluidTransactionState.DELIVERED, 3);
        ledger.advance(transaction, FluidTransactionState.RETURN_PENDING, 4);
        ledger.advance(transaction, FluidTransactionState.RETURNED, 5);

        ProjectFluidLedger restored = ProjectFluidLedger.restore(ledger.snapshot());
        assertThat(restored.balance(project)).satisfies(report -> {
            assertThat(report.returnedMb()).isEqualTo(250);
            assertThat(report.balanced()).isTrue();
            assertThat(report.duplicateReturns()).isZero();
        });
        assertThatThrownBy(() -> restored.prepare(transaction, project, id("task:other"),
                id("tank:source"), CREOSOTE, 250, 6))
                .hasMessageContaining("identity was reused");
    }

    private static FluidNetworkNode node(
            String name, FluidNodeKind kind, Optional<FluidIdentity> fluid,
            long amount, long capacity, Set<Direction6> input, Set<Direction6> output, long pump) {
        return new FluidNetworkNode(id("fluid_node:" + name), kind,
                new BlockPos3i(name.hashCode() & 7, 0, 0), fluid, amount, capacity,
                input, output, pump, "a".repeat(64), true);
    }

    private static FluidRoute route(
            String name, FluidNetworkNode from, FluidNetworkNode to, boolean directional) {
        return new FluidRoute(id("fluid_route:" + name), from.nodeId(), to.nodeId(), CREOSOTE,
                List.of(from.position(), to.position()), 250, 250, directional,
                true, true, true, "b".repeat(64), true);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
