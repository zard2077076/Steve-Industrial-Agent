package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PilotRecoverySavedDataTest {
    @Test
    void roundTripsExactWorldRegionAndCleanupOwnership() {
        UUID player = UUID.fromString("6a17f4d3-9c01-37a1-b62a-2da91c7d9af7");
        ResourceId session = id("steve_industrial:pilot/test/recovery");
        BlockPos3i position = new BlockPos3i(-1000, 151, 2996);
        BlockChange change = new BlockChange(
                id("steve_industrial:change/test"), session,
                id("steve_industrial:step/build"), 42, position,
                snapshot("minecraft:air"), snapshot("create:mechanical_press"));
        WorldChangeJournal journal = new WorldChangeJournal(session, List.of(change));
        PilotRecoverySavedData.RecoveryEntry entry = new PilotRecoverySavedData.RecoveryEntry(
                player, "world:test", "sha256:world", id("minecraft:overworld"),
                new DeploymentBoundingBox(new BlockPos3i(-1009, 150, 2994),
                        new BlockPos3i(-989, 156, 2996)),
                "a".repeat(64), "sha256:region", id("create:iron_sheet"), 2,
                QuarterTurn.ZERO, session, new BlockPos3i(-1001, 151, 2996),
                "sha256:preview", "iwp05-backup", 12, 1_900_000_000_000L,
                PilotRecoverySavedData.Stage.PROCESS,
                PilotRecoverySavedData.RecoveryMode.UNSAFE_RESOURCE_HISTORY,
                new byte[0], List.of(journal), 0, "resource history present", 100);

        PilotRecoverySavedData data = new PilotRecoverySavedData();
        data.put(entry);
        PilotRecoverySavedData.RecoveryEntry decoded = PilotRecoverySavedData
                .load(data.save(new CompoundTag())).entry(player).orElseThrow();

        assertThat(decoded).usingRecursiveComparison().isEqualTo(entry);
        assertThat(decoded.journals().get(0).modifiedPositions()).containsExactly(position);
        assertThat(decoded.journals().get(0).entries().get(0)).isEqualTo(change);
    }

    @Test
    void failsClosedOnTamperedModeAndCheckpointCombination() {
        assertThatThrownBy(() -> new PilotRecoverySavedData.RecoveryEntry(
                UUID.randomUUID(), "world:test", "sha256:world",
                id("minecraft:overworld"),
                new DeploymentBoundingBox(new BlockPos3i(0, 0, 0), new BlockPos3i(1, 1, 1)),
                "b".repeat(64), "sha256:region", id("minecraft:gravel"), 3,
                QuarterTurn.ZERO, id("steve_industrial:pilot/test"),
                new BlockPos3i(0, 1, 0), "sha256:preview", "backup", 16,
                1_900_000_000_000L, PilotRecoverySavedData.Stage.PROCESS,
                PilotRecoverySavedData.RecoveryMode.UNSAFE_RESOURCE_HISTORY,
                new byte[] {1}, List.of(), 0, "tampered", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only safe recovery");
    }

    private static WorldBlockSnapshot snapshot(String block) {
        return new WorldBlockSnapshot(id(block), Map.of(), Optional.empty());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
