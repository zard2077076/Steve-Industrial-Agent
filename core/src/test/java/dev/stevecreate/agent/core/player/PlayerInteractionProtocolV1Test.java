package dev.stevecreate.agent.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerInteractionProtocolV1Test {
    @Test
    void freezesTheEstablishedPhaseIvPlayerAndItemMaterialVocabulary() {
        assertThat(PlayerInteractionProtocolV1.NETWORK_PROTOCOL)
                .isEqualTo("phase-iv-player-workflow-v3");
        assertThat(PlayerInteractionProtocolV1.WORKFLOW_SAVED_DATA_SCHEMA).isEqualTo(2);
        assertThat(PlayerInteractionProtocolV1.MATERIAL_SAVED_DATA_SCHEMA).isEqualTo(3);
        assertThat(PlayerInteractionProtocolV1.SALVAGE_SAVED_DATA_SCHEMA).isEqualTo(1);
        assertThat(PlayerInteractionProtocolV1.MAX_MATERIAL_SOURCES).isEqualTo(8);

        assertThat(List.of(WorkflowStage.values()))
                .containsExactlyElementsOf(PlayerInteractionProtocolV1.WORKFLOW_STAGES);
        assertThat(List.of(PlayerExecutionMode.values()))
                .containsExactlyElementsOf(PlayerInteractionProtocolV1.EXECUTION_MODES);
        assertThat(List.of(MaterialTransactionState.values()))
                .containsExactlyElementsOf(PlayerInteractionProtocolV1.ITEM_TRANSACTION_STATES);
        assertThat(List.of(MaterialExecutorKind.values()))
                .containsExactlyElementsOf(PlayerInteractionProtocolV1.MATERIAL_EXECUTORS);
        assertThat(List.of(ProductionMode.values()))
                .containsExactly(ProductionMode.ONCE, ProductionMode.MAINTAIN_STOCK_UNAVAILABLE);
    }
}
