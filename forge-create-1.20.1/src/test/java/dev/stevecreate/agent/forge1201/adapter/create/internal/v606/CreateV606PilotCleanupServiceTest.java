package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreateV606PilotCleanupServiceTest {
    @Test
    void acceptsOnlyTheRuntimeManagedBeltPartTransition() {
        WorldBlockSnapshot expected = snapshot("create:belt", Map.of(
                "facing", "east", "part", "middle", "slope", "horizontal"));
        WorldBlockSnapshot current = snapshot("create:belt", Map.of(
                "facing", "east", "part", "start", "slope", "horizontal"));

        assertThat(CreateV606PilotCleanupService.matchesOwnedAfter(current, expected)).isTrue();
    }

    @Test
    void refusesDifferentBeltGeometryOrNonBeltPropertyDrift() {
        WorldBlockSnapshot expectedBelt = snapshot("create:belt", Map.of(
                "facing", "east", "part", "middle", "slope", "horizontal"));
        WorldBlockSnapshot changedBelt = snapshot("create:belt", Map.of(
                "facing", "west", "part", "start", "slope", "horizontal"));
        WorldBlockSnapshot expectedMotor = snapshot("create:creative_motor", Map.of("facing", "north"));
        WorldBlockSnapshot changedMotor = snapshot("create:creative_motor", Map.of("facing", "south"));

        assertThat(CreateV606PilotCleanupService.matchesOwnedAfter(changedBelt, expectedBelt)).isFalse();
        assertThat(CreateV606PilotCleanupService.matchesOwnedAfter(changedMotor, expectedMotor)).isFalse();
    }

    private static WorldBlockSnapshot snapshot(String block, Map<String, String> properties) {
        return new WorldBlockSnapshot(ResourceId.parse(block), properties, Optional.empty());
    }
}
