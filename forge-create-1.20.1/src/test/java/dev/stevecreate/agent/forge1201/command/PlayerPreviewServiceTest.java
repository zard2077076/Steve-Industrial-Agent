package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606MachineGeometryCatalog;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import org.junit.jupiter.api.Test;

class PlayerPreviewServiceTest {
    @Test
    void everyPlayerGoalResolvesToReviewedFourDirectionGeometry() {
        var geometries = CreateV606MachineGeometryCatalog.create("runtime:test");
        assertThat(PlayerGoalCatalog.entries()).allSatisfy(goal -> {
            var geometry = geometries.find(PlayerPreviewService.implementation(goal.capability()));
            assertThat(geometry).isPresent();
            assertThat(geometry.orElseThrow().supportedOrientations()).hasSize(4);
            assertThat(PlayerPreviewService.spacing(
                    LayoutVariant.COMPACT, geometry.orElseThrow())).isBetween(2, 15);
            assertThat(PlayerPreviewService.spacing(
                    LayoutVariant.STANDARD, geometry.orElseThrow())).isEqualTo(16);
            assertThat(PlayerPreviewService.spacing(
                    LayoutVariant.EXPANDABLE, geometry.orElseThrow())).isEqualTo(24);
        });
    }
}
