package dev.stevecreate.agent.forge1201.player.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalPickerScreenTest {
    @Test
    void acceptsOnlyTheResponseForTheCurrentTerminalAndCurrentQuery() {
        assertThat(GoalPickerScreen.acceptsSearchResponse(
                41, " Concrete ", "concrete", 41, "concrete")).isTrue();

        assertThat(GoalPickerScreen.acceptsSearchResponse(
                41, "concrete", "concrete", 40, "concrete")).isFalse();
        assertThat(GoalPickerScreen.acceptsSearchResponse(
                41, "concrete", "concrete", 41, "copper")).isFalse();
        assertThat(GoalPickerScreen.acceptsSearchResponse(
                41, "copper", "copper", 41, "concrete")).isFalse();
        assertThat(GoalPickerScreen.acceptsSearchResponse(
                41, "", "", 41, "concrete")).isFalse();
    }
}
