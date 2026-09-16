package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.player.PlayerInteractionProtocolV1;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import org.junit.jupiter.api.Test;

class PlayerInteractionProtocolFreezeTest {
    @Test
    void forgeWireAndSavedDataRemainBoundToTheFrozenPhaseIvContract() {
        assertThat(PlayerWorkflowNetwork.PROTOCOL)
                .isEqualTo(PlayerInteractionProtocolV1.NETWORK_PROTOCOL);
        assertThat(PlayerWorkflowSavedData.SCHEMA)
                .isEqualTo(PlayerInteractionProtocolV1.WORKFLOW_SAVED_DATA_SCHEMA);
        assertThat(PlayerMaterialSavedData.SCHEMA)
                .isEqualTo(PlayerInteractionProtocolV1.MATERIAL_SAVED_DATA_SCHEMA);
        assertThat(PlayerSalvageSavedData.SCHEMA)
                .isEqualTo(PlayerInteractionProtocolV1.SALVAGE_SAVED_DATA_SCHEMA);
        assertThat(dev.stevecreate.agent.forge1201.command.PlayerMaterialService.MAX_SOURCES)
                .isEqualTo(PlayerInteractionProtocolV1.MAX_MATERIAL_SOURCES);
    }
}
