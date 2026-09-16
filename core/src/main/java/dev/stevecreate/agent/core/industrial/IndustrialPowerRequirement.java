package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Verified, mutually exclusive power obligation for one complete industrial plan.
 *
 * <p>Fuel remains an exact material obligation; it is not renamed to FE or hidden in
 * process inputs. Electrical plans retain their verified network identity and cannot
 * also claim an item fuel allowance.</p>
 */
public record IndustrialPowerRequirement(
        IndustrialPowerMode mode,
        long requiredEnergyFe,
        Map<ResourceId, Long> fuelItems,
        long minimumBurnTicks,
        Optional<ResourceId> electricalNetworkId,
        Optional<String> electricalNetworkFingerprint) {
    public static final int MAX_FUEL_KINDS = 16;

    public IndustrialPowerRequirement {
        Objects.requireNonNull(mode, "mode");
        fuelItems = amounts(fuelItems);
        electricalNetworkId = Objects.requireNonNull(
                electricalNetworkId, "electricalNetworkId");
        electricalNetworkFingerprint = Objects.requireNonNull(
                electricalNetworkFingerprint, "electricalNetworkFingerprint");
        switch (mode) {
            case ELECTRICAL_NETWORK -> {
                if (requiredEnergyFe < 1 || requiredEnergyFe > 1_000_000_000_000L
                        || !fuelItems.isEmpty() || minimumBurnTicks != 0
                        || electricalNetworkId.isEmpty()
                        || electricalNetworkFingerprint.isEmpty()
                        || !hash(electricalNetworkFingerprint.orElseThrow())) {
                    throw new IllegalArgumentException(
                            "electrical industrial power requirement is inconsistent");
                }
            }
            case ITEM_FUEL -> {
                if (requiredEnergyFe != 0 || fuelItems.isEmpty()
                        || minimumBurnTicks < 1 || minimumBurnTicks > 10_000_000_000L
                        || electricalNetworkId.isPresent()
                        || electricalNetworkFingerprint.isPresent()) {
                    throw new IllegalArgumentException(
                            "fuel industrial power requirement is inconsistent");
                }
            }
        }
    }

    public static IndustrialPowerRequirement electrical(
            long requiredEnergyFe, ResourceId networkId, String networkFingerprint) {
        return new IndustrialPowerRequirement(IndustrialPowerMode.ELECTRICAL_NETWORK,
                requiredEnergyFe, Map.of(), 0, Optional.of(networkId),
                Optional.of(networkFingerprint));
    }

    public static IndustrialPowerRequirement itemFuel(
            Map<ResourceId, Long> fuelItems, long minimumBurnTicks) {
        return new IndustrialPowerRequirement(IndustrialPowerMode.ITEM_FUEL, 0,
                fuelItems, minimumBurnTicks, Optional.empty(), Optional.empty());
    }

    private static Map<ResourceId, Long> amounts(Map<ResourceId, Long> source) {
        Objects.requireNonNull(source, "fuelItems");
        if (source.size() > MAX_FUEL_KINDS) {
            throw new IllegalArgumentException("industrial fuel kinds exceed their bound");
        }
        LinkedHashMap<ResourceId, Long> ordered = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).forEach(entry -> {
            Objects.requireNonNull(entry.getKey(), "fuel resource");
            Long amount = Objects.requireNonNull(entry.getValue(), "fuel amount");
            if (amount < 1 || amount > 1_000_000_000L) {
                throw new IllegalArgumentException("industrial fuel amount is invalid");
            }
            ordered.put(entry.getKey(), amount);
        });
        return Collections.unmodifiableMap(ordered);
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
