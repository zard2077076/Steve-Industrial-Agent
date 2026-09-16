package dev.stevecreate.agent.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateCapabilityContractV1Test {
    @Test
    void freezesAllTenCapabilitiesWithoutInventingMaterialsForDiscovery() {
        assertEquals(List.of("C-01", "C-02", "C-03", "C-04", "C-05", "C-06",
                        "C-07", "C-08", "C-09", "C-10"),
                CreateCapabilityContractV1.ordered().stream()
                        .map(CreateCapabilityContractV1::capabilityId).toList());
        assertFalse(CreateCapabilityContractV1.C01.materialBearing());
        assertFalse(CreateCapabilityContractV1.C02.materialBearing());
        assertTrue(CreateCapabilityContractV1.ordered().subList(2, 10).stream()
                .allMatch(CreateCapabilityContractV1::materialBearing));
        assertEquals(CreateCapabilityContractV1.C06,
                CreateCapabilityContractV1.forProcessCapability(id("create:splashing")).orElseThrow());
        assertEquals(CreateCapabilityContractV1.C06,
                CreateCapabilityContractV1.forProcessCapability(id("create:blasting")).orElseThrow());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
