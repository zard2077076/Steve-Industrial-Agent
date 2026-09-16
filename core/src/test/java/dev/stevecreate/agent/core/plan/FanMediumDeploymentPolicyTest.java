package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import org.junit.jupiter.api.Test;

class FanMediumDeploymentPolicyTest {
    @Test
    void dangerousMediaRefuseBotsAndForceTypedHybridDirectFallback() {
        for (FanProcessingMode medium : new FanProcessingMode[] {
                FanProcessingMode.SMOKING,
                FanProcessingMode.HAUNTING,
                FanProcessingMode.BLASTING}) {
            assertThatThrownBy(() ->
                    FanMediumDeploymentPolicy.decide(medium, ExecutionMode.BOTS))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("cannot deploy or enter");
            var hybrid = FanMediumDeploymentPolicy.decide(medium, ExecutionMode.HYBRID);
            assertThat(hybrid.executionMode()).isEqualTo(ExecutionMode.DIRECT);
            assertThat(hybrid.fallback()).isTrue();
            assertThat(hybrid.reason()).contains("dangerous_medium");
        }
    }

    @Test
    void waterRetainsTheRequestedModeWithSafeStandoff() {
        for (ExecutionMode mode : ExecutionMode.values()) {
            var decision = FanMediumDeploymentPolicy.decide(
                    FanProcessingMode.WASHING, mode);
            assertThat(decision.executionMode()).isEqualTo(mode);
            assertThat(decision.fallback()).isFalse();
        }
    }
}
