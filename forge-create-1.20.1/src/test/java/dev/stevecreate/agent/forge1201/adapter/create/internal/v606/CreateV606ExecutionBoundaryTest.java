package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CreateV606ExecutionBoundaryTest {
    @Test
    void preparedPlayerSiteDoesNotDependOnGameTestProperty() {
        assertThat(CreateV606ThreeModeExecution.tickBoundaryAccepted(
                true,
                CreateV606ThreeModeExecution.ExecutionBoundary.PREPARED_PLAYER_SITE,
                false)).isTrue();
    }

    @Test
    void isolatedTestStillRequiresExplicitProperty() {
        assertThat(CreateV606ThreeModeExecution.tickBoundaryAccepted(
                true,
                CreateV606ThreeModeExecution.ExecutionBoundary.ISOLATED_TEST,
                false)).isFalse();
        assertThat(CreateV606ThreeModeExecution.tickBoundaryAccepted(
                true,
                CreateV606ThreeModeExecution.ExecutionBoundary.ISOLATED_TEST,
                true)).isTrue();
    }

    @Test
    void neitherBoundaryCanTickOffTheServerThread() {
        assertThat(CreateV606ThreeModeExecution.tickBoundaryAccepted(
                false,
                CreateV606ThreeModeExecution.ExecutionBoundary.PREPARED_PLAYER_SITE,
                true)).isFalse();
        assertThat(CreateV606ThreeModeExecution.tickBoundaryAccepted(
                false,
                CreateV606ThreeModeExecution.ExecutionBoundary.ISOLATED_TEST,
                true)).isFalse();
    }
}
