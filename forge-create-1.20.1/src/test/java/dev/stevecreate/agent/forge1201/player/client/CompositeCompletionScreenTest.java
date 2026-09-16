package dev.stevecreate.agent.forge1201.player.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompositeCompletionScreenTest {
    @Test
    void adaptsTheNonOverlappingViewportToGuiScale() {
        assertThat(CompositeCompletionScreen.visibleRowsForPanelHeight(220)).isEqualTo(2);
        assertThat(CompositeCompletionScreen.visibleRowsForPanelHeight(340)).isEqualTo(7);
        assertThat(CompositeCompletionScreen.maximumScroll(13, 2)).isEqualTo(11);
        assertThat(CompositeCompletionScreen.maximumScroll(7, 7)).isZero();
    }
}
