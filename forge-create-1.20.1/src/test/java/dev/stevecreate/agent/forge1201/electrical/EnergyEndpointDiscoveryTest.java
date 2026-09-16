package dev.stevecreate.agent.forge1201.electrical;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraftforge.energy.IEnergyStorage;
import org.junit.jupiter.api.Test;

/**
 * The rule that separates energy discovery from the item and fluid versions.
 *
 * <p>A chest and a tank can both be drawn from and filled. An energy endpoint often
 * cannot, and pairing two consumers moves nothing while looking entirely reasonable in a
 * list of stored/capacity numbers. These assert the direction rule directly, with fakes,
 * because the classification is the content and it needs no world to be wrong in.</p>
 */
class EnergyEndpointDiscoveryTest {

    @Test
    void readsAGeneratorFromWhatItWillAndWillNotDo() {
        assertThat(EnergyEndpointDiscovery.classify(storage(true, false)))
                .contains(ElectricalNodeKind.GENERATOR);
    }

    @Test
    void readsAConsumerFromWhatItWillAndWillNotDo() {
        assertThat(EnergyEndpointDiscovery.classify(storage(false, true)))
                .contains(ElectricalNodeKind.CONSUMER);
    }

    @Test
    void readsABatteryAsStorageBecauseItDoesBoth() {
        assertThat(EnergyEndpointDiscovery.classify(storage(true, true)))
                .contains(ElectricalNodeKind.STORAGE);
    }

    /**
     * A handler that can do neither still answers the capability — a meter, a display, a
     * relay segment. It cannot participate in a transfer, and offering it to a player
     * would be offering something that does nothing.
     */
    @Test
    void refusesAHandlerThatCanNeitherGiveNorTake() {
        assertThat(EnergyEndpointDiscovery.classify(storage(false, false))).isEmpty();
    }

    @Test
    void tellsSuppliersAndConsumersApartWhenSummarising() {
        EnergyEndpointDiscovery.Endpoint generator =
                endpoint("a", ElectricalNodeKind.GENERATOR, 5_000, 10_000);
        EnergyEndpointDiscovery.Endpoint consumer =
                endpoint("b", ElectricalNodeKind.CONSUMER, 0, 2_000);
        EnergyEndpointDiscovery.Endpoint battery =
                endpoint("c", ElectricalNodeKind.STORAGE, 1_000, 4_000);

        assertThat(generator.canSupply()).isTrue();
        assertThat(generator.canAccept()).isFalse();
        assertThat(consumer.canSupply()).isFalse();
        assertThat(consumer.canAccept()).isTrue();
        // A battery counts on both sides, which is the honest answer to "is there
        // anything that can give" and "is there anything that can take".
        assertThat(battery.canSupply()).isTrue();
        assertThat(battery.canAccept()).isTrue();

        EnergyEndpointDiscovery.Grid grid =
                EnergyEndpointDiscovery.summarise(List.of(generator, consumer, battery));
        assertThat(grid.storedFe()).isEqualTo(6_000);
        assertThat(grid.capacityFe()).isEqualTo(16_000);
        assertThat(grid.suppliers()).isEqualTo(2);
        assertThat(grid.consumers()).isEqualTo(2);
        assertThat(grid.canMoveEnergy()).isTrue();
    }

    /**
     * The failure this classification exists to prevent: several endpoints, plenty of
     * stored energy, and nothing that can move any of it. Stored and capacity totals look
     * healthy in both cases, which is why they are not sufficient on their own.
     */
    @Test
    void reportsThatNothingCanMoveWhenEveryEndpointFacesTheSameWay() {
        EnergyEndpointDiscovery.Grid allSuppliers = EnergyEndpointDiscovery.summarise(List.of(
                endpoint("a", ElectricalNodeKind.GENERATOR, 5_000, 10_000),
                endpoint("b", ElectricalNodeKind.GENERATOR, 7_000, 10_000)));
        assertThat(allSuppliers.canMoveEnergy()).isFalse();
        assertThat(allSuppliers.storedFe()).isEqualTo(12_000);

        EnergyEndpointDiscovery.Grid allConsumers = EnergyEndpointDiscovery.summarise(List.of(
                endpoint("a", ElectricalNodeKind.CONSUMER, 0, 10_000),
                endpoint("b", ElectricalNodeKind.CONSUMER, 0, 10_000)));
        assertThat(allConsumers.canMoveEnergy()).isFalse();

        assertThat(EnergyEndpointDiscovery.summarise(List.of()).canMoveEnergy()).isFalse();
    }

    /**
     * One battery is both a supplier and a consumer, so counting alone says energy can
     * move — from the block to itself. The fluid executor refuses that shape outright as
     * ENDPOINTS_IDENTICAL; this question is asked before any pairing exists, so it has to
     * find a genuine pair rather than a non-zero count on each side.
     */
    @Test
    void refusesToCallASingleBatteryAMovableGrid() {
        EnergyEndpointDiscovery.Grid alone = EnergyEndpointDiscovery.summarise(
                List.of(endpoint("a", ElectricalNodeKind.STORAGE, 5_000, 10_000)));

        assertThat(alone.suppliers()).isEqualTo(1);
        assertThat(alone.consumers()).isEqualTo(1);
        assertThat(alone.canMoveEnergy()).isFalse();

        // Two of them can trade, and that is the difference.
        assertThat(EnergyEndpointDiscovery.summarise(List.of(
                        endpoint("a", ElectricalNodeKind.STORAGE, 5_000, 10_000),
                        endpoint("b", ElectricalNodeKind.STORAGE, 0, 10_000)))
                .canMoveEnergy()).isTrue();
    }

    /**
     * The direction rule cannot tell a generator from a battery on real blocks — every IE
     * endpoint answers yes to both flags — so behaviour has to be watched instead. This
     * pins the one thing a watcher must not do: call a direction from a single reading.
     */
    @Test
    void aSingleReadingHasNoDirection() {
        EnergyBehaviourSampler sampler = new EnergyBehaviourSampler();
        assertThat(sampler.behaviour()).isEmpty();
        assertThat(sampler.firstGenerating()).isEmpty();
    }

    private static EnergyEndpointDiscovery.Endpoint endpoint(
            String name, ElectricalNodeKind kind, long stored, long capacity) {
        return new EnergyEndpointDiscovery.Endpoint(
                new BlockPos3i(name.charAt(0), 0, 0),
                ResourceId.parse("test:" + name),
                Direction.UP,
                kind,
                stored,
                capacity,
                kind == ElectricalNodeKind.GENERATOR ? 0 : capacity - stored,
                kind == ElectricalNodeKind.CONSUMER ? 0 : stored);
    }

    private static IEnergyStorage storage(boolean canExtract, boolean canReceive) {
        return new IEnergyStorage() {
            @Override
            public int receiveEnergy(int maxReceive, boolean simulate) { return 0; }

            @Override
            public int extractEnergy(int maxExtract, boolean simulate) { return 0; }

            @Override
            public int getEnergyStored() { return 0; }

            @Override
            public int getMaxEnergyStored() { return 0; }

            @Override
            public boolean canExtract() { return canExtract; }

            @Override
            public boolean canReceive() { return canReceive; }
        };
    }
}
