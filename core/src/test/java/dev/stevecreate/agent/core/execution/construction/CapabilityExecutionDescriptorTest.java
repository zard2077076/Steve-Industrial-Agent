package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityExecutionDescriptorTest {
    @Test
    void executionModesHaveExactStableCommandNames() {
        assertThat(ExecutionMode.values())
                .extracting(ExecutionMode::serializedName)
                .containsExactly("direct", "bots", "hybrid");
        assertThat(ExecutionMode.parse("bots")).isEqualTo(ExecutionMode.BOTS);
        assertThatThrownBy(() -> ExecutionMode.parse("bot"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descriptorRequiresHonestDeclarationsForAllThreeModes() {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> incomplete = new EnumMap<>(ExecutionMode.class);
        incomplete.put(ExecutionMode.DIRECT, supported(ExecutionMode.DIRECT));
        incomplete.put(ExecutionMode.BOTS, unsupportedBots());

        assertThatThrownBy(() -> new CapabilityExecutionDescriptor(
                id("capability:test"),
                id("implementation:test"),
                id("adapter:test"),
                "runtime",
                EnumSet.of(TaskKind.PLACE_COMPONENT),
                incomplete,
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Every Direct/Bots/Hybrid mode");
    }

    @Test
    void unsupportedBotsCannotClaimExecutorCapabilitiesOrExecutableSupport() {
        assertThatThrownBy(() -> new ModeCapabilityDeclaration(
                ExecutionMode.BOTS,
                CapabilitySupport.UNSUPPORTED,
                Set.of(id("executor:navigation")),
                "not proven"))
                .isInstanceOf(IllegalArgumentException.class);

        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, supported(ExecutionMode.DIRECT));
        modes.put(ExecutionMode.BOTS, unsupportedBots());
        modes.put(ExecutionMode.HYBRID, supported(ExecutionMode.HYBRID));
        CapabilityExecutionDescriptor descriptor = new CapabilityExecutionDescriptor(
                id("capability:test"),
                id("implementation:test"),
                id("adapter:test"),
                "runtime",
                EnumSet.of(TaskKind.PLACE_COMPONENT),
                modes,
                Map.of());

        assertThat(descriptor.supports(ExecutionMode.DIRECT, TaskKind.PLACE_COMPONENT)).isTrue();
        assertThat(descriptor.supports(ExecutionMode.BOTS, TaskKind.PLACE_COMPONENT)).isFalse();
        assertThat(descriptor.modeCapability(ExecutionMode.BOTS).limitation()).isEqualTo("not proven");
        assertThat(ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED.id().toString())
                .isEqualTo("construction:bot_execution_capability_unsupported");
    }

    private static ModeCapabilityDeclaration supported(ExecutionMode mode) {
        return new ModeCapabilityDeclaration(
                mode,
                CapabilitySupport.SUPPORTED,
                Set.of(id("executor:" + mode.serializedName())),
                "");
    }

    private static ModeCapabilityDeclaration unsupportedBots() {
        return new ModeCapabilityDeclaration(
                ExecutionMode.BOTS,
                CapabilitySupport.UNSUPPORTED,
                Set.of(),
                "not proven");
    }
}
