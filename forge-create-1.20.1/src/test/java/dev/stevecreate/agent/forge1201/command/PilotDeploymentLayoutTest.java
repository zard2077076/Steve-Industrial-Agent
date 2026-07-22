package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import org.junit.jupiter.api.Test;

class PilotDeploymentLayoutTest {
    private static final DeploymentBoundingBox REGION_32 = new DeploymentBoundingBox(
            new BlockPos3i(122, 96, 79), new BlockPos3i(153, 115, 110));

    @Test
    void centersTwoPhysicalModulesAroundTheConfirmedRegionForEveryAcceptedOrientation() {
        assertThat(PilotDeploymentCommand.pilotAnchor(REGION_32, 2, QuarterTurn.ZERO))
                .isEqualTo(new BlockPos3i(129, 98, 94));
        assertThat(PilotDeploymentCommand.pilotAnchor(REGION_32, 2, QuarterTurn.CLOCKWISE_90))
                .isEqualTo(new BlockPos3i(137, 98, 86));
        assertThat(PilotDeploymentCommand.pilotAnchor(REGION_32, 2, QuarterTurn.CLOCKWISE_270))
                .isEqualTo(new BlockPos3i(137, 98, 102));
    }

    @Test
    void leavesSinglePhysicalModuleAtTheConfirmedRegionCenter() {
        assertThat(PilotDeploymentCommand.pilotAnchor(REGION_32, 1, QuarterTurn.ZERO))
                .isEqualTo(new BlockPos3i(137, 98, 94));
    }
}
