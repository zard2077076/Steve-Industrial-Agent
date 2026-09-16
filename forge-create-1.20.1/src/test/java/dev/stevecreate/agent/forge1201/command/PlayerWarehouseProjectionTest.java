package dev.stevecreate.agent.forge1201.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Slot;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerWarehouseProjectionTest {
    private static final String EMPTY = MaterialIdentity.EMPTY_PAYLOAD_SHA256;
    private static final String HASH = "a".repeat(64);
    private static final ResourceId IRON = ResourceId.parse("minecraft:iron_ingot");
    private static final ResourceId DIMENSION = ResourceId.parse("minecraft:overworld");

    @Test
    void subtractsAnotherProjectsExactPhysicalReservationBeforeAtomicAllocation() {
        long now = 1_000;
        UUID player = UUID.randomUUID();
        UUID currentId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        BlockPos3i position = new BlockPos3i(4, 70, 9);
        MaterialIdentity identity = new MaterialIdentity(IRON, EMPTY);
        Source currentSource = source(UUID.randomUUID(), position, identity, 10, now);
        Source otherSource = source(UUID.randomUUID(), position, identity, 10, now);
        Reservation otherReservation = new Reservation(
                UUID.randomUUID(), IRON, otherSource.sourceId(), 0, identity, 3, now + 10_000);
        Entry other = entry(otherId, player, Map.of(IRON, 3L), List.of(otherSource),
                List.of(otherReservation), now);
        Entry current = entry(currentId, player, Map.of(IRON, 4L), List.of(currentSource),
                List.of(), now);

        PlayerWarehouseProjection.Projection projection = PlayerWarehouseProjection.reserve(
                current, List.of(currentSource), List.of(other, current),
                "isolated-world", now);

        assertTrue(projection.success());
        assertEquals(4, projection.allocations().stream()
                .mapToLong(value -> value.quantity()).sum());
        assertTrue(projection.balance().balanced());

        Entry overcommitted = entry(currentId, player, Map.of(IRON, 8L),
                List.of(currentSource), List.of(), now);
        PlayerWarehouseProjection.Projection refused = PlayerWarehouseProjection.reserve(
                overcommitted, List.of(currentSource), List.of(other, overcommitted),
                "isolated-world", now);
        assertFalse(refused.success());
        assertEquals("WAREHOUSE_MATERIALS_INSUFFICIENT", refused.statusCode());
    }

    private static Source source(
            UUID id,
            BlockPos3i position,
            MaterialIdentity identity,
            long quantity,
            long now) {
        return new Source(id, position, "up", "minecraft:chest", HASH, HASH, 0,
                quantity, now, now + 10_000, false,
                List.of(new Slot(0, identity, quantity)));
    }

    private static Entry entry(
            UUID project,
            UUID player,
            Map<ResourceId, Long> requirements,
            List<Source> sources,
            List<Reservation> reservations,
            long now) {
        return new Entry(project, player, DIMENSION, requirements, HASH, "test-runtime",
                sources, reservations, List.of(), List.of(), null, false,
                now, now, "TEST", null);
    }
}
