package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collections;

/**
 * Immutable multi-resource authority bound to one player order.
 *
 * <p>This type grants no mutation path. It freezes the exact warehouse commitments, Bot
 * identities, FE graph and fluid graph that a versioned executor must re-observe after reload.</p>
 */
public record IndustrialResourceBindingV1(
        ResourceId projectId,
        ResourceId ownerId,
        String worldIdentity,
        ResourceId dimension,
        GlobalInventoryGraph warehouse,
        List<WarehouseReservationRequest> warehouseRequests,
        EntityLogisticsBindingV1 entityLogistics,
        Optional<ElectricalNetworkGraph> electricalNetwork,
        Optional<FluidNetworkGraph> fluidNetwork,
        Map<ResourceId, Long> plannedEnergy,
        Map<FluidIdentity, Long> plannedFluids) {
    public static final int MAX_RESOURCE_ROWS = 4_096;

    public IndustrialResourceBindingV1 {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(warehouse, "warehouse");
        if (!warehouse.ownerId().equals(ownerId)
                || !warehouse.worldIdentity().equals(worldIdentity)
                || !warehouse.dimension().equals(dimension)) {
            throw new IllegalArgumentException("warehouse authority differs from industrial order");
        }
        warehouseRequests = List.copyOf(Objects.requireNonNull(
                warehouseRequests, "warehouseRequests"));
        if (warehouseRequests.isEmpty() || warehouseRequests.size() > 512
                || warehouseRequests.stream().map(WarehouseReservationRequest::requestId)
                        .distinct().count() != warehouseRequests.size()) {
            throw new IllegalArgumentException("warehouse commitments are empty, duplicate or unbounded");
        }
        for (WarehouseReservationRequest request : warehouseRequests) {
            if (!request.projectId().equals(projectId) || !request.ownerId().equals(ownerId)
                    || !warehouse.endpoints().keySet().containsAll(request.allowedEndpointIds())) {
                throw new IllegalArgumentException("warehouse commitment authority differs from order");
            }
        }
        Objects.requireNonNull(entityLogistics, "entityLogistics");
        electricalNetwork = Objects.requireNonNull(electricalNetwork, "electricalNetwork");
        fluidNetwork = Objects.requireNonNull(fluidNetwork, "fluidNetwork");
        electricalNetwork.ifPresent(graph -> {
            if (!graph.worldIdentity().equals(worldIdentity) || !graph.dimension().equals(dimension)) {
                throw new IllegalArgumentException("electrical network authority differs from order");
            }
        });
        fluidNetwork.ifPresent(graph -> {
            if (!graph.worldIdentity().equals(worldIdentity) || !graph.dimension().equals(dimension)) {
                throw new IllegalArgumentException("fluid network authority differs from order");
            }
        });
        plannedEnergy = amounts(plannedEnergy, "plannedEnergy");
        plannedFluids = fluidAmounts(plannedFluids);
        if (plannedEnergy.isEmpty() != electricalNetwork.isEmpty()
                || plannedFluids.isEmpty() != fluidNetwork.isEmpty()) {
            throw new IllegalArgumentException("planned FE/fluid amounts require their exact network");
        }
        ensureReportIdentityIsUnambiguous(plannedFluids);
    }

    public String fingerprint() {
        StringBuilder canonical = new StringBuilder().append(projectId).append('|')
                .append(ownerId).append('|').append(worldIdentity).append('|').append(dimension)
                .append("|warehouse=").append(warehouse.fingerprint())
                .append("|logistics=").append(entityLogistics.fingerprint())
                .append("|electrical=").append(electricalNetwork
                        .map(ElectricalNetworkGraph::fingerprint).orElse(""))
                .append("|fluid=").append(fluidNetwork.map(FluidNetworkGraph::fingerprint).orElse(""));
        warehouseRequests.stream().sorted(Comparator.comparing(value -> value.requestId().toString()))
                .forEach(value -> canonical.append("|request=").append(value));
        plannedEnergy.forEach((resource, amount) -> canonical.append("|energy=")
                .append(resource).append(':').append(amount));
        plannedFluids.forEach((resource, amount) -> canonical.append("|fluidAmount=")
                .append(resource).append(':').append(amount));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<ResourceId, Long> amounts(Map<ResourceId, Long> source, String name) {
        Objects.requireNonNull(source, name);
        if (source.size() > MAX_RESOURCE_ROWS) throw new IllegalArgumentException(name + " is unbounded");
        LinkedHashMap<ResourceId, Long> ordered = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).forEach(entry -> {
            Objects.requireNonNull(entry.getKey(), name + " resource");
            long amount = Objects.requireNonNull(entry.getValue(), name + " amount");
            if (amount < 1 || amount > 1_000_000_000_000L) {
                throw new IllegalArgumentException(name + " amount is invalid");
            }
            ordered.put(entry.getKey(), amount);
        });
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<FluidIdentity, Long> fluidAmounts(Map<FluidIdentity, Long> source) {
        Objects.requireNonNull(source, "plannedFluids");
        if (source.size() > MAX_RESOURCE_ROWS) {
            throw new IllegalArgumentException("plannedFluids is unbounded");
        }
        LinkedHashMap<FluidIdentity, Long> ordered = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Objects.requireNonNull(entry.getKey(), "planned fluid identity");
            long amount = Objects.requireNonNull(entry.getValue(), "planned fluid amount");
            if (amount < 1 || amount > 1_000_000_000_000L) {
                throw new IllegalArgumentException("planned fluid amount is invalid");
            }
            ordered.put(entry.getKey(), amount);
        });
        return Collections.unmodifiableMap(ordered);
    }

    private static void ensureReportIdentityIsUnambiguous(Map<FluidIdentity, Long> fluids) {
        if (fluids.keySet().stream().map(FluidIdentity::fluidId).distinct().count() != fluids.size()) {
            throw new IllegalArgumentException(
                    "completion report cannot collapse two component identities of one fluid");
        }
    }
}
