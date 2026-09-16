package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PlayerSalvageSavedDataTest {
    @Test
    void roundTripsOnlyExactLocationAuthorityAndNoContents() {
        UUID project = UUID.fromString("00000000-0000-0000-0000-000000000042");
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000043");
        var expected = new PlayerSalvageSavedData.Entry(project, player,
                ResourceId.parse("minecraft:overworld"), new BlockPos3i(12, 70, -9),
                "a".repeat(64), "player-salvage:" + "b".repeat(64), 100);
        PlayerSalvageSavedData data = new PlayerSalvageSavedData();
        data.put(expected);

        CompoundTag encoded = data.save(new CompoundTag());
        PlayerSalvageSavedData decoded = PlayerSalvageSavedData.load(encoded);

        assertThat(decoded.entry(project)).contains(expected);
        assertThat(encoded.toString()).doesNotContain("Items", "Inventory", "Count", "Slot");
    }
}
