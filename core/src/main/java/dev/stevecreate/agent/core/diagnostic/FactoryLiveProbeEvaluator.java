package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loader-neutral classification for exact, plan-owned live observations.
 *
 * <p>The caller supplies already-bounded measurements. This type has no world, inventory,
 * executor or repair handle, so a probe result can explain a stop but cannot fix one.</p>
 */
public final class FactoryLiveProbeEvaluator {
    public static final long DEFAULT_LOGISTICS_STALL_TICKS = 200;

    public FactoryHealthObservation outputCapacity(
            OutputCapacityProbe probe, Set<ResourceId> resources) {
        Objects.requireNonNull(probe, "probe");
        resources = Set.copyOf(Objects.requireNonNull(resources, "resources"));
        if (!probe.observerAvailable()) {
            return unavailable(FactoryHealthCategory.OUTPUT_CAPACITY,
                    "PLAN_OUTPUT_CAPACITY_UNAVAILABLE", resources,
                    "No exact plan-owned output destination can be observed");
        }
        Map<String, Long> metrics = Map.of(
                "required", probe.requiredUnits(),
                "available", probe.availableUnits(),
                "destinationPresent", probe.destinationPresent() ? 1L : 0L);
        boolean enough = probe.destinationPresent()
                && probe.availableUnits() >= probe.requiredUnits();
        return observation(FactoryHealthCategory.OUTPUT_CAPACITY,
                enough ? FactoryObservationState.HEALTHY : FactoryObservationState.FAULT,
                enough ? "PLAN_OUTPUT_CAPACITY_AVAILABLE"
                        : probe.destinationPresent() ? "PLAN_OUTPUT_CAPACITY_EXHAUSTED"
                                : "PLAN_OUTPUT_DESTINATION_MISSING",
                metrics, resources,
                enough ? "The exact project output destination can accept the planned output"
                        : probe.destinationPresent()
                                ? "The exact project output destination cannot accept the planned output"
                                : "The exact project output destination is missing");
    }

    public FactoryHealthObservation logistics(
            LogisticsProgressProbe probe, Set<ResourceId> resources) {
        Objects.requireNonNull(probe, "probe");
        resources = Set.copyOf(Objects.requireNonNull(resources, "resources"));
        if (!probe.observerAvailable()) {
            return unavailable(FactoryHealthCategory.LOGISTICS_ROUTE,
                    "PLAN_LOGISTICS_OBSERVER_UNAVAILABLE", resources,
                    "No exact plan-owned courier or physical route observer is available");
        }
        long idleTicks = Math.max(0, probe.observedTick() - probe.lastProgressTick());
        Map<String, Long> metrics = Map.of(
                "expectedTransfers", probe.expectedTransfers(),
                "completedTransfers", probe.completedTransfers(),
                "idleTicks", idleTicks,
                "stallThresholdTicks", probe.stallThresholdTicks(),
                "explicitlyBlocked", probe.explicitlyBlocked() ? 1L : 0L);
        boolean complete = probe.completedTransfers() == probe.expectedTransfers();
        boolean stalled = !complete && (probe.explicitlyBlocked()
                || idleTicks > probe.stallThresholdTicks());
        return observation(FactoryHealthCategory.LOGISTICS_ROUTE,
                stalled ? FactoryObservationState.FAULT : FactoryObservationState.HEALTHY,
                stalled ? "PLAN_LOGISTICS_STALLED"
                        : complete ? "PLAN_LOGISTICS_COMPLETE" : "PLAN_LOGISTICS_PROGRESS",
                metrics, resources,
                stalled ? "The exact project route has stopped making bounded progress"
                        : complete ? "Every exact project material transfer is complete"
                                : "The exact project route is still making bounded progress");
    }

    public FactoryHealthObservation rotationalPower(
            RotationalPowerProbe probe, Set<ResourceId> resources) {
        Objects.requireNonNull(probe, "probe");
        resources = Set.copyOf(Objects.requireNonNull(resources, "resources"));
        if (!probe.observerAvailable()) {
            return unavailable(FactoryHealthCategory.ENERGY_SUPPLY,
                    "PLAN_ROTATIONAL_POWER_UNAVAILABLE", resources,
                    "The exact Create kinetic endpoints are not currently observable");
        }
        Map<String, Long> metrics = Map.of(
                "expectedComponents", probe.expectedComponents(),
                "observedComponents", probe.observedComponents(),
                "poweredComponents", probe.poweredComponents(),
                "overstressedComponents", probe.overstressedComponents(),
                "minimumRpmMilli", probe.minimumRpmMilli(),
                "stressCapacityMilli", probe.stressCapacityMilli(),
                "stressLoadMilli", probe.stressLoadMilli());
        boolean powered = probe.observedComponents() == probe.expectedComponents()
                && probe.poweredComponents() == probe.expectedComponents()
                && probe.overstressedComponents() == 0
                && probe.stressLoadMilli() <= probe.stressCapacityMilli();
        return observation(FactoryHealthCategory.ENERGY_SUPPLY,
                powered ? FactoryObservationState.HEALTHY : FactoryObservationState.FAULT,
                powered ? "PLAN_ROTATIONAL_POWER_HEALTHY"
                        : probe.overstressedComponents() > 0
                                || probe.stressLoadMilli() > probe.stressCapacityMilli()
                                ? "PLAN_ROTATIONAL_POWER_OVERSTRESSED"
                                : "PLAN_ROTATIONAL_POWER_INSUFFICIENT",
                metrics, resources,
                powered ? "Every exact plan-owned kinetic endpoint is powered within capacity"
                        : "One or more exact plan-owned kinetic endpoints are missing, stopped or overstressed");
    }

    public FactoryHealthObservation forgeEnergy(
            ForgeEnergyProbe probe, Set<ResourceId> resources) {
        Objects.requireNonNull(probe, "probe");
        resources = Set.copyOf(Objects.requireNonNull(resources, "resources"));
        if (!probe.observerAvailable()) {
            return unavailable(FactoryHealthCategory.ENERGY_SUPPLY,
                    "PLAN_FE_POWER_UNAVAILABLE", resources,
                    "The exact plan-owned Forge Energy endpoints are not currently observable");
        }
        Map<String, Long> metrics = Map.of(
                "requiredFe", probe.requiredFe(),
                "storedFe", probe.storedFe(),
                "expectedConnections", probe.expectedConnections(),
                "observedConnections", probe.observedConnections(),
                "chargingAccepted", probe.chargingAccepted() ? 1L : 0L,
                "processingAccepted", probe.processingAccepted() ? 1L : 0L,
                "energySettled", probe.energySettled() ? 1L : 0L);
        boolean topology = probe.observedConnections() == probe.expectedConnections();
        boolean energy = probe.energySettled() || probe.storedFe() >= probe.requiredFe()
                || probe.chargingAccepted() || probe.processingAccepted();
        boolean healthy = topology && energy;
        String code = healthy
                ? probe.energySettled() ? "PLAN_FE_ENERGY_SETTLED"
                        : probe.storedFe() >= probe.requiredFe() ? "PLAN_FE_POWER_HEALTHY"
                                : probe.processingAccepted() ? "PLAN_FE_ENERGY_IN_USE"
                                        : "PLAN_FE_CHARGING_PROGRESS"
                : !topology ? "PLAN_FE_CONNECTION_MISMATCH" : "PLAN_FE_POWER_INSUFFICIENT";
        return observation(FactoryHealthCategory.ENERGY_SUPPLY,
                healthy ? FactoryObservationState.HEALTHY : FactoryObservationState.FAULT,
                code, metrics, resources,
                healthy ? "The exact plan-owned FE topology is connected and its order evidence is current"
                        : "The exact plan-owned FE topology or required energy is insufficient");
    }

    public FactoryHealthObservation structure(
            StructureProbe probe, Set<ResourceId> resources) {
        Objects.requireNonNull(probe, "probe");
        resources = Set.copyOf(Objects.requireNonNull(resources, "resources"));
        if (!probe.observerAvailable()) {
            return unavailable(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    "PLAN_STRUCTURE_OBSERVER_UNAVAILABLE", resources,
                    "The exact plan-owned structure cells are not currently observable");
        }
        Map<String, Long> metrics = Map.of(
                "expectedCells", probe.expectedCells(),
                "observedCells", probe.observedCells(),
                "identityMismatches", probe.identityMismatches(),
                "orientationMismatches", probe.orientationMismatches(),
                "unloadedCells", probe.unloadedCells());
        boolean healthy = probe.unloadedCells() == 0
                && probe.observedCells() == probe.expectedCells()
                && probe.identityMismatches() == 0
                && probe.orientationMismatches() == 0;
        return observation(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                healthy ? FactoryObservationState.HEALTHY : FactoryObservationState.FAULT,
                healthy ? "PLAN_STRUCTURE_AND_ORIENTATION_MATCH"
                        : probe.orientationMismatches() > 0
                                ? "PLAN_ORIENTATION_MISMATCH" : "PLAN_STRUCTURE_MISMATCH",
                metrics, resources,
                healthy ? "Every exact plan-owned structure cell and orientation matches"
                        : "An exact plan-owned structure cell or orientation no longer matches");
    }

    private static FactoryHealthObservation unavailable(
            FactoryHealthCategory category, String code, Set<ResourceId> resources, String detail) {
        return new FactoryHealthObservation(category, FactoryObservationState.UNKNOWN,
                FactoryEvidenceSource.LIVE_WORLD_UNAVAILABLE, code, Map.of(), resources, detail);
    }

    private static FactoryHealthObservation observation(
            FactoryHealthCategory category, FactoryObservationState state, String code,
            Map<String, Long> metrics, Set<ResourceId> resources, String detail) {
        return new FactoryHealthObservation(category, state, FactoryEvidenceSource.LIVE_WORLD,
                code, metrics, resources, detail);
    }

    public record OutputCapacityProbe(
            boolean observerAvailable, boolean destinationPresent,
            long requiredUnits, long availableUnits) {
        public OutputCapacityProbe {
            if (requiredUnits < 1 || availableUnits < 0) {
                throw new IllegalArgumentException("output-capacity probe values are invalid");
            }
        }
    }

    public record LogisticsProgressProbe(
            boolean observerAvailable, boolean explicitlyBlocked,
            long expectedTransfers, long completedTransfers,
            long lastProgressTick, long observedTick, long stallThresholdTicks) {
        public LogisticsProgressProbe {
            if (expectedTransfers < 1 || completedTransfers < 0
                    || completedTransfers > expectedTransfers || lastProgressTick < 0
                    || observedTick < lastProgressTick || stallThresholdTicks < 1) {
                throw new IllegalArgumentException("logistics-progress probe values are invalid");
            }
        }
    }

    public record RotationalPowerProbe(
            boolean observerAvailable, long expectedComponents, long observedComponents,
            long poweredComponents, long overstressedComponents, long minimumRpmMilli,
            long stressCapacityMilli, long stressLoadMilli) {
        public RotationalPowerProbe {
            if (expectedComponents < 1 || observedComponents < 0
                    || observedComponents > expectedComponents || poweredComponents < 0
                    || poweredComponents > observedComponents || overstressedComponents < 0
                    || overstressedComponents > observedComponents || minimumRpmMilli < 0
                    || stressCapacityMilli < 0 || stressLoadMilli < 0) {
                throw new IllegalArgumentException("rotational-power probe values are invalid");
            }
        }
    }

    public record ForgeEnergyProbe(
            boolean observerAvailable, long requiredFe, long storedFe,
            long expectedConnections, long observedConnections,
            boolean chargingAccepted, boolean processingAccepted, boolean energySettled) {
        public ForgeEnergyProbe {
            if (requiredFe < 1 || storedFe < 0 || expectedConnections < 1
                    || observedConnections < 0) {
                throw new IllegalArgumentException("Forge Energy probe values are invalid");
            }
        }
    }

    public record StructureProbe(
            boolean observerAvailable, long expectedCells, long observedCells,
            long identityMismatches, long orientationMismatches, long unloadedCells) {
        public StructureProbe {
            if (expectedCells < 1 || observedCells < 0 || observedCells > expectedCells
                    || identityMismatches < 0 || orientationMismatches < 0
                    || unloadedCells < 0 || identityMismatches + orientationMismatches
                            + unloadedCells > expectedCells) {
                throw new IllegalArgumentException("structure probe values are invalid");
            }
        }
    }
}
