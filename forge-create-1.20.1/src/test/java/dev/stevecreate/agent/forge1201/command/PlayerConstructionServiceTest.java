package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import org.junit.jupiter.api.Test;

class PlayerConstructionServiceTest {
    @Test
    void temporaryLogisticsChestsCannotBecomeHorizontalDoubleChests() {
        BlockPos3i staging = new BlockPos3i(10, 64, 10);

        assertThat(PlayerConstructionService.horizontallyAdjacent(
                staging, new BlockPos3i(11, 64, 10))).isTrue();
        assertThat(PlayerConstructionService.horizontallyAdjacent(
                staging, new BlockPos3i(10, 64, 11))).isTrue();
        assertThat(PlayerConstructionService.horizontallyAdjacent(
                staging, new BlockPos3i(12, 64, 10))).isFalse();
        assertThat(PlayerConstructionService.horizontallyAdjacent(
                staging, new BlockPos3i(11, 65, 10))).isFalse();
    }
}
