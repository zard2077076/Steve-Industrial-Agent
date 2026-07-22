package dev.stevecreate.agent.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RotationTest {
    @Test
    void rotatesPositionAndDirectionAroundY() {
        assertThat(new BlockPos3i(2, 5, -3).rotateY(QuarterTurn.CLOCKWISE_90))
                .isEqualTo(new BlockPos3i(3, 5, 2));
        assertThat(Direction6.NORTH.rotateY(QuarterTurn.CLOCKWISE_90)).isEqualTo(Direction6.EAST);
        assertThat(Direction6.UP.rotateY(QuarterTurn.CLOCKWISE_270)).isEqualTo(Direction6.UP);
    }
}

