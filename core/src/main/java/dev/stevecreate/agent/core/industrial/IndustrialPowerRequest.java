package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Unverified per-batch power input supplied to {@link IndustrialProductionPlanner}. */
public record IndustrialPowerRequest(
        IndustrialPowerMode mode,
        long energyPerBatchFe,
        Map<ResourceId, Long> fuelItemsPerBatch,
        long minimumBurnTicksPerBatch,
        Optional<ElectricalNetworkGraph> electricalNetwork) {
    public IndustrialPowerRequest {
        Objects.requireNonNull(mode, "mode");
        fuelItemsPerBatch = Map.copyOf(Objects.requireNonNull(
                fuelItemsPerBatch, "fuelItemsPerBatch"));
        electricalNetwork = Objects.requireNonNull(electricalNetwork, "electricalNetwork");
        // Reuse the verified requirement's exact shape checks with unscaled values.
        if (mode == IndustrialPowerMode.ELECTRICAL_NETWORK) {
            if (electricalNetwork.isEmpty()) {
                throw new IllegalArgumentException("electrical power request has no network");
            }
            IndustrialPowerRequirement.electrical(energyPerBatchFe,
                    electricalNetwork.orElseThrow().graphId(),
                    electricalNetwork.orElseThrow().fingerprint());
        } else {
            if (electricalNetwork.isPresent()) {
                throw new IllegalArgumentException("fuel power request carries an electrical network");
            }
            IndustrialPowerRequirement.itemFuel(
                    fuelItemsPerBatch, minimumBurnTicksPerBatch);
            if (energyPerBatchFe != 0) {
                throw new IllegalArgumentException("fuel power request invents FE");
            }
        }
    }

    public static IndustrialPowerRequest electrical(
            long energyPerBatchFe, ElectricalNetworkGraph electricalNetwork) {
        return new IndustrialPowerRequest(IndustrialPowerMode.ELECTRICAL_NETWORK,
                energyPerBatchFe, Map.of(), 0, Optional.of(electricalNetwork));
    }

    public static IndustrialPowerRequest itemFuel(
            Map<ResourceId, Long> fuelItemsPerBatch, long minimumBurnTicksPerBatch) {
        return new IndustrialPowerRequest(IndustrialPowerMode.ITEM_FUEL, 0,
                fuelItemsPerBatch, minimumBurnTicksPerBatch, Optional.empty());
    }
}
