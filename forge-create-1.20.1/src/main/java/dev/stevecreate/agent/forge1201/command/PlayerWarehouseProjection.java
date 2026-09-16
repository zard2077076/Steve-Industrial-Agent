package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseAllocation;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointType;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative projection of the already selected player containers into the logical
 * warehouse contract. It creates no warehouse block and grants no inventory access beyond the
 * exact source snapshots already accepted by the frozen material-source workflow.
 */
final class PlayerWarehouseProjection {
    private PlayerWarehouseProjection() {}

    static Projection reserve(
            Entry project,
            List<Source> currentSources,
            Collection<Entry> projects,
            String worldIdentity,
            long now) {
        ResourceId owner = resource("owner", project.playerId());
        ResourceId warehouse = resource("warehouse", project.playerId());
        ResourceId projectId = resource("project", project.projectId());
        Map<String, Long> committed = committedByOtherProjects(
                project.projectId(), project.dimension(), projects, now);
        Map<UUID, ResourceId> endpointIds = new LinkedHashMap<>();
        ArrayList<WarehouseEndpointSnapshot> endpoints = new ArrayList<>();
        for (Source source : currentSources.stream()
                .sorted(Comparator.comparingInt(Source::priority)
                        .thenComparing(value -> value.sourceId().toString()))
                .toList()) {
            ResourceId endpoint = resource("endpoint", source.sourceId());
            endpointIds.put(source.sourceId(), endpoint);
            LinkedHashMap<WarehouseResourceKey, Long> contents = new LinkedHashMap<>();
            for (var slot : source.slots()) {
                long available = Math.max(0, slot.quantity() - committed.getOrDefault(
                        physicalSlotKey(source, slot.slot(), slot.identity()), 0L));
                if (available == 0) continue;
                WarehouseResourceKey key = new WarehouseResourceKey(
                        GenericResourceType.ITEM,
                        slot.identity().itemId(),
                        slot.identity().payloadSha256());
                contents.merge(key, available, Math::addExact);
            }
            long stored = contents.values().stream().mapToLong(Long::longValue).sum();
            endpoints.add(new WarehouseEndpointSnapshot(
                    endpoint,
                    warehouse,
                    owner,
                    worldIdentity,
                    project.dimension(),
                    source.position(),
                    Optional.of(Direction6.valueOf(source.accessFace().toUpperCase(
                            java.util.Locale.ROOT))),
                    ResourceId.parse(source.blockEntityType()),
                    WarehouseEndpointType.ITEM_CONTAINER,
                    contents,
                    Math.max(stored, source.maximumWithdrawal()),
                    source.inventoryHash(),
                    0,
                    source.expiresAt(),
                    true));
        }
        GlobalInventoryGraph graph = new GlobalInventoryGraph(
                warehouse, owner, worldIdentity, project.dimension(), endpoints, List.of(), 0);
        List<ResourceId> allowed = currentSources.stream()
                .sorted(Comparator.comparingInt(Source::priority)
                        .thenComparing(value -> value.sourceId().toString()))
                .map(value -> endpointIds.get(value.sourceId()))
                .toList();
        ArrayList<WarehouseReservationRequest> requests = new ArrayList<>();
        int priority = project.requirements().size();
        for (Map.Entry<ResourceId, Long> requirement : project.requirements().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList()) {
            requests.add(new WarehouseReservationRequest(
                    ResourceId.parse("warehouse:request_" + project.projectId().toString()
                            .replace("-", "") + "_" + String.format("%03d", priority)),
                    projectId,
                    owner,
                    new WarehouseResourceKey(
                            GenericResourceType.ITEM,
                            requirement.getKey(),
                            WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                    requirement.getValue(),
                    allowed,
                    now + PlayerMaterialService.SOURCE_TTL_MILLIS,
                    priority--));
        }
        WarehouseReservationSystem system = new WarehouseReservationSystem(graph);
        WarehouseReservationSystem.ReservationResult result =
                system.reserveAtomically(requests, now);
        return new Projection(graph, result.success(), result.failureCode(),
                result.allocations(), system.balance(projectId));
    }

    private static Map<String, Long> committedByOtherProjects(
            UUID projectId,
            ResourceId dimension,
            Collection<Entry> projects,
            long now) {
        LinkedHashMap<String, Long> committed = new LinkedHashMap<>();
        for (Entry entry : projects) {
            if (entry.projectId().equals(projectId) || !entry.dimension().equals(dimension)) {
                continue;
            }
            Map<UUID, Source> sources = entry.sources().stream().collect(
                    java.util.stream.Collectors.toMap(Source::sourceId, value -> value));
            for (Reservation reservation : entry.reservations()) {
                boolean stillPhysical = reservation.expiresAt() > now
                        && entry.transactions().stream()
                                .filter(value -> value.reservationId()
                                        .equals(reservation.reservationId()))
                                .allMatch(value -> value.state()
                                        == MaterialTransactionState.PREPARED);
                Source source = sources.get(reservation.sourceId());
                if (stillPhysical && source != null) {
                    committed.merge(
                            physicalSlotKey(source, reservation.slot(), reservation.identity()),
                            reservation.quantity(),
                            Math::addExact);
                }
            }
        }
        return Map.copyOf(committed);
    }

    private static String physicalSlotKey(
            Source source, int slot, MaterialIdentity identity) {
        return source.position().x() + "," + source.position().y() + ","
                + source.position().z() + "|" + slot + "|" + identity.itemId()
                + "|" + identity.payloadSha256();
    }

    private static ResourceId resource(String kind, UUID value) {
        return ResourceId.parse("warehouse:" + kind + "_"
                + value.toString().replace("-", ""));
    }

    record Projection(
            GlobalInventoryGraph graph,
            boolean success,
            String statusCode,
            List<WarehouseAllocation> allocations,
            WarehouseReservationSystem.WarehouseBalanceReport balance) {
        Projection {
            allocations = List.copyOf(allocations);
        }

        Map<String, Long> endpointResourceTotals() {
            LinkedHashMap<String, Long> result = new LinkedHashMap<>();
            allocations.forEach(value -> result.merge(
                    value.endpointId() + "|" + value.resource().resourceId()
                            + "|" + value.resource().componentSha256(),
                    value.quantity(), Math::addExact));
            return Map.copyOf(result);
        }
    }
}
