package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.plan.FanProcessingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateV606FanMediumRoutingTest {
    @Test
    void dangerousModesAreRejectedBeforeAnyBotsSessionCanBeCreated() {
        for (FanProcessingMode mode : List.of(
                FanProcessingMode.SMOKING,
                FanProcessingMode.HAUNTING,
                FanProcessingMode.BLASTING)) {
            assertThatThrownBy(() ->
                    CreateV606ThreeModeExecution.decideFanMediumRouting(
                            List.of(mode), ExecutionMode.BOTS))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("cannot deploy or enter")
                    .hasMessageContaining("Hybrid");
        }
    }

    @Test
    void dangerousHybridUsesTheDirectSensitiveTaskFallbackWithExactReason() {
        assertThat(CreateV606ThreeModeExecution.decideFanMediumRouting(
                List.of(FanProcessingMode.BLASTING), ExecutionMode.HYBRID))
                .contains("dangerous_medium_deployment_fallback_to_direct");
    }

    @Test
    void washingDoesNotInventADangerousMediumFallback() {
        assertThat(CreateV606ThreeModeExecution.decideFanMediumRouting(
                List.of(FanProcessingMode.WASHING), ExecutionMode.BOTS))
                .isEmpty();
    }
}
