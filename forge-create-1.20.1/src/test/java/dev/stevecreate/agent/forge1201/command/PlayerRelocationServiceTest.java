package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.player.LayoutVariant;
import org.junit.jupiter.api.Test;

class PlayerRelocationServiceTest {
    @Test
    void searchSpaceIsExactlyNineOffsetsByTwoHeightsByFourTurnsByThreeVariants() {
        var keys = PlayerRelocationService.candidateKeysForTest(
                new BlockPos3i(20, 70, 30), QuarterTurn.CLOCKWISE_90,
                LayoutVariant.EXPANDABLE);

        assertThat(keys).hasSize(216).doesNotHaveDuplicates();
        assertThat(keys.get(0)).isEqualTo(
                "BlockPos3i[x=20, y=70, z=30]|CLOCKWISE_90|EXPANDABLE");
        assertThat(keys).anyMatch(value -> value.contains("CLOCKWISE_180"));
        assertThat(keys).anyMatch(value -> value.contains("y=71"));
        assertThat(keys).allMatch(value -> value.contains("x=") && value.contains("z="));
    }
}
