package dev.stevecreate.agent.forge1201.fluid;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.fluid.FluidTransactionState;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.model.ResourceId;
import org.junit.jupiter.api.Test;

class ForgeFluidTransactionExecutorTest {
    private static final FluidIdentity WATER = new FluidIdentity(id("minecraft:water"),
            FluidIdentity.EMPTY_COMPONENT_SHA256);

    @Test
    void transfersExactMillibucketsAndLeavesDeliveredObligationBalanced() {
        TankEndpoint source = new TankEndpoint(WATER, 2_000, 4_000);
        TankEndpoint destination = new TankEndpoint(WATER, 0, 4_000);
        ProjectFluidLedger ledger = new ProjectFluidLedger();

        var result = ForgeFluidTransactionExecutor.transfer(source, destination, ledger,
                id("test:transaction"), id("test:project"), id("test:task"),
                id("test:source"), new ForgeFluidTransactionExecutor.FluidPacket(WATER, 1_000),
                10);

        assertThat(result.success()).isTrue();
        assertThat(result.state()).isEqualTo(FluidTransactionState.DELIVERED);
        assertThat(result.deliveredMb()).isEqualTo(1_000);
        assertThat(source.amount).isEqualTo(1_000);
        assertThat(destination.amount).isEqualTo(1_000);
        assertThat(result.ledgerBalanced()).isTrue();
    }

    @Test
    void fullDestinationReleasesWithoutWithdrawingAnything() {
        TankEndpoint source = new TankEndpoint(WATER, 2_000, 4_000);
        TankEndpoint destination = new TankEndpoint(WATER, 1_000, 1_000);
        ProjectFluidLedger ledger = new ProjectFluidLedger();

        var result = ForgeFluidTransactionExecutor.transfer(source, destination, ledger,
                id("test:full_transaction"), id("test:project"), id("test:task"),
                id("test:source"), new ForgeFluidTransactionExecutor.FluidPacket(WATER, 1_000),
                10);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DESTINATION_CAPACITY_INSUFFICIENT");
        assertThat(result.state()).isEqualTo(FluidTransactionState.RELEASED);
        assertThat(source.amount).isEqualTo(2_000);
        assertThat(destination.amount).isEqualTo(1_000);
        assertThat(result.ledgerBalanced()).isTrue();
    }

    @Test
    void deliveryDriftReclaimsAndReturnsTheExactWithdrawal() {
        TankEndpoint source = new TankEndpoint(WATER, 2_000, 4_000);
        TankEndpoint physicalDestination = new TankEndpoint(WATER, 0, 4_000);
        ForgeFluidTransactionExecutor.Endpoint driftingDestination =
                new ForgeFluidTransactionExecutor.Endpoint() {
                    @Override
                    public ForgeFluidTransactionExecutor.FluidPacket drain(
                            ForgeFluidTransactionExecutor.FluidPacket requested,
                            boolean simulate) {
                        return physicalDestination.drain(requested, simulate);
                    }

                    @Override
                    public int fill(ForgeFluidTransactionExecutor.FluidPacket offered,
                            boolean simulate) {
                        if (simulate) return offered.amountMb();
                        return physicalDestination.fill(new ForgeFluidTransactionExecutor.FluidPacket(
                                offered.identity(), offered.amountMb() / 2), false);
                    }
                };
        ProjectFluidLedger ledger = new ProjectFluidLedger();

        var result = ForgeFluidTransactionExecutor.transfer(source, driftingDestination, ledger,
                id("test:drift_transaction"), id("test:project"), id("test:task"),
                id("test:source"), new ForgeFluidTransactionExecutor.FluidPacket(WATER, 1_000),
                10);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DELIVERY_DIVERGED_RETURNED");
        assertThat(result.state()).isEqualTo(FluidTransactionState.RETURNED);
        assertThat(source.amount).isEqualTo(2_000);
        assertThat(physicalDestination.amount).isZero();
        assertThat(result.ledgerBalanced()).isTrue();
    }

    /**
     * The one outcome that ends with fluid nowhere, and the first assertion of it.
     *
     * <p>The source is drained, the destination takes only half, the half is reclaimed —
     * and then the source refuses to take any of it back. The executor has 1000 mB in a
     * local variable and no tank will hold it, so it records RETURN_PENDING and returns.
     * That is real loss, not a refusal.
     *
     * <p>The ledger has to say so. It used to report balanced across exactly this case,
     * because RETURN_PENDING was counted as outstanding and cancelled against its own
     * withdrawal. Every other test here asserts ledgerBalanced() is true; this is the one
     * that gives that assertion its meaning, because without a case where it is false the
     * other three were agreeing with arithmetic rather than with the world.</p>
     */
    @Test
    void reportsUnbalancedWhenReturnedFluidHasNowhereToGo() {
        TankEndpoint physicalDestination = new TankEndpoint(WATER, 0, 4_000);
        // Drains normally, but will not accept anything back — a source that filled up
        // from elsewhere between withdrawal and return, or a one-way outlet.
        ForgeFluidTransactionExecutor.Endpoint refusingSource =
                new ForgeFluidTransactionExecutor.Endpoint() {
                    private final TankEndpoint tank = new TankEndpoint(WATER, 2_000, 4_000);

                    @Override
                    public ForgeFluidTransactionExecutor.FluidPacket drain(
                            ForgeFluidTransactionExecutor.FluidPacket requested, boolean simulate) {
                        return tank.drain(requested, simulate);
                    }

                    @Override
                    public int fill(ForgeFluidTransactionExecutor.FluidPacket offered,
                            boolean simulate) {
                        return 0;
                    }
                };
        ForgeFluidTransactionExecutor.Endpoint driftingDestination =
                new ForgeFluidTransactionExecutor.Endpoint() {
                    @Override
                    public ForgeFluidTransactionExecutor.FluidPacket drain(
                            ForgeFluidTransactionExecutor.FluidPacket requested, boolean simulate) {
                        return physicalDestination.drain(requested, simulate);
                    }

                    @Override
                    public int fill(ForgeFluidTransactionExecutor.FluidPacket offered,
                            boolean simulate) {
                        if (simulate) return offered.amountMb();
                        return physicalDestination.fill(new ForgeFluidTransactionExecutor.FluidPacket(
                                offered.identity(), offered.amountMb() / 2), false);
                    }
                };
        ProjectFluidLedger ledger = new ProjectFluidLedger();

        var result = ForgeFluidTransactionExecutor.transfer(refusingSource, driftingDestination,
                ledger, id("test:stranded_transaction"), id("test:project"), id("test:task"),
                id("test:source"), new ForgeFluidTransactionExecutor.FluidPacket(WATER, 1_000),
                10);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("FLUID_RETURN_PENDING");
        assertThat(result.state()).isEqualTo(FluidTransactionState.RETURN_PENDING);
        // The ledger must not call this healthy. This is the assertion that could not
        // previously fail.
        assertThat(result.ledgerBalanced()).isFalse();
        assertThat(ledger.balance(id("test:project")).unaccountedMb()).isEqualTo(1_000);
        // And the reclaim did happen, so the destination is empty even though the fluid
        // never got home — which is precisely why the ledger has to flag it.
        assertThat(physicalDestination.amount).isZero();
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static final class TankEndpoint implements ForgeFluidTransactionExecutor.Endpoint {
        private final FluidIdentity identity;
        private final int capacity;
        private int amount;

        private TankEndpoint(FluidIdentity identity, int amount, int capacity) {
            this.identity = identity;
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        public ForgeFluidTransactionExecutor.FluidPacket drain(
                ForgeFluidTransactionExecutor.FluidPacket requested, boolean simulate) {
            int drained = requested.identity().equals(identity)
                    ? Math.min(amount, requested.amountMb()) : 0;
            if (!simulate) amount -= drained;
            return new ForgeFluidTransactionExecutor.FluidPacket(identity, drained);
        }

        @Override
        public int fill(ForgeFluidTransactionExecutor.FluidPacket offered, boolean simulate) {
            if (!offered.identity().equals(identity)) return 0;
            int filled = Math.min(capacity - amount, offered.amountMb());
            if (!simulate) amount += filled;
            return filled;
        }
    }
}
