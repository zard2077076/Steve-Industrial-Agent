package dev.stevecreate.agent.forge1201.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BoundedBotNavigationTest {
    @Test
    void acceptsOneBlockUpAndDownAsPartOfTheSameHorizontalRoute() {
        BlockPos middle = new BlockPos(10, 124, 10);

        assertThat(BoundedBotNavigation.isAdjacentStep(middle,
                middle.east().above())).isTrue();
        assertThat(BoundedBotNavigation.isAdjacentStep(middle,
                middle.west().below())).isTrue();
        assertThat(BoundedBotNavigation.isAdjacentStep(middle,
                middle.above())).isFalse();
        assertThat(BoundedBotNavigation.isAdjacentStep(middle,
                middle.east().above().north())).isFalse();
        assertThat(BoundedBotNavigation.isAdjacentStep(middle,
                middle.east().above(2))).isFalse();
    }
}
