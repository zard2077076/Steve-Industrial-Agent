package dev.stevecreate.agent.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stevecreate.agent.core.model.ResourceId;
import org.junit.jupiter.api.Test;

class PlayerFleetSizingPolicyTest {
    @Test
    void freezesC01ThroughC10AndCompositeFleetSizes() {
        assertEquals(0, PlayerFleetSizingPolicy.discoveryWorkers("C-01"));
        assertEquals(0, PlayerFleetSizingPolicy.discoveryWorkers("C-02"));
        assertEquals(2, PlayerFleetSizingPolicy.constructionWorkers(id("create:milling")));
        assertEquals(2, PlayerFleetSizingPolicy.constructionWorkers(id("create:pressing")));
        assertEquals(2, PlayerFleetSizingPolicy.constructionWorkers(id("create:crushing")));
        assertEquals(2, PlayerFleetSizingPolicy.constructionWorkers(id("create:splashing")));
        assertEquals(2, PlayerFleetSizingPolicy.constructionWorkers(id("create:cutting")));
        assertEquals(3, PlayerFleetSizingPolicy.constructionWorkers(id("create:mixing")));
        assertEquals(3, PlayerFleetSizingPolicy.constructionWorkers(id("create:compacting")));
        assertEquals(3, PlayerFleetSizingPolicy.constructionWorkers(id("create:deploying")));
        assertEquals(3, PlayerFleetSizingPolicy.compositeWorkers("Composite-01"));
        assertEquals(5, PlayerFleetSizingPolicy.compositeWorkers("Composite-02"));
        assertEquals(5, PlayerFleetSizingPolicy.compositeWorkers("Composite-03"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerFleetSizingPolicy.discoveryWorkers("C-03"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerFleetSizingPolicy.compositeWorkers("Composite-04"));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
