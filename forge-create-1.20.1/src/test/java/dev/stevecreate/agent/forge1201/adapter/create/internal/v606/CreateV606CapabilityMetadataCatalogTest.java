package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.adapter.api.create.CapabilityComponentRole;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CreateV606CapabilityMetadataCatalogTest {
    private final CreateV606CapabilityMetadataCatalog catalog =
            new CreateV606CapabilityMetadataCatalog();

    @Test
    void everyExpansionCapabilityHasOneReadOnlyExecutorIndependentBinding() {
        assertEquals(CreateCapabilityId.values().length, catalog.entries().size());
        assertEquals(
                Arrays.stream(CreateCapabilityId.values()).sorted().toList(),
                catalog.entries().stream().map(value -> value.capability()).sorted().toList());
        catalog.entries().forEach(value -> {
            assertTrue(value.serverAuthoritativeReadbackRequired());
            assertFalse(value.executorProvided());
            assertFalse(value.placementAuthority());
            assertEquals("1.20.1", value.minecraftVersion());
            assertEquals("6.0.6", value.createVersion());
            assertTrue(value.components().stream().anyMatch(component ->
                    component.role() == CapabilityComponentRole.PRIMARY_MACHINE
                            && component.required()
                            && component.liveReadbackRequired()));
            assertTrue(value.zones().stream().allMatch(zone -> zone.liveReadbackRequired()));
        });
    }

    @Test
    void capabilitySpecificComponentsRemainExplicit() {
        assertEquals(2, catalog.find(CreateCapabilityId.CRUSHING).orElseThrow().components().stream()
                .filter(value -> value.role() == CapabilityComponentRole.PRIMARY_MACHINE
                        || value.role() == CapabilityComponentRole.SECONDARY_MACHINE)
                .count());
        assertTrue(catalog.find(CreateCapabilityId.FAN_WASHING).orElseThrow().components().stream()
                .anyMatch(value -> value.role() == CapabilityComponentRole.MEDIUM
                        && value.acceptedBlockIds().toString().contains("minecraft:water")));
        assertTrue(catalog.find(CreateCapabilityId.MIXING_PHASE_I).orElseThrow().components().stream()
                .anyMatch(value -> value.role() == CapabilityComponentRole.BASIN));
        assertTrue(catalog.find(CreateCapabilityId.DEPLOYING_PHASE_I).orElseThrow().components().stream()
                .anyMatch(value -> value.role() == CapabilityComponentRole.HELD_ITEM));
    }
}
