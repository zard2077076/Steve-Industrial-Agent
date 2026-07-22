package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BindingFailureTest {
    @Test
    void definesTheCompleteStableTypedBindingFailureSet() {
        assertThat(BindingFailureCode.values()).containsExactly(
                BindingFailureCode.IMPLEMENTATION_CATALOG_MISSING,
                BindingFailureCode.IMPLEMENTATION_NOT_FOUND,
                BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH,
                BindingFailureCode.IMPLEMENTATION_RECIPE_TYPE_MISMATCH,
                BindingFailureCode.IMPLEMENTATION_ADAPTER_UNAVAILABLE,
                BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                BindingFailureCode.IMPLEMENTATION_PORT_CONTRACT_INVALID,
                BindingFailureCode.IMPLEMENTATION_POWER_CONTRACT_INVALID,
                BindingFailureCode.IMPLEMENTATION_EXECUTION_UNVERIFIED,
                BindingFailureCode.IMPLEMENTATION_FORBIDDEN,
                BindingFailureCode.MULTIPLE_IMPLEMENTATIONS_AMBIGUOUS,
                BindingFailureCode.LOGICAL_NODE_UNBOUND,
                BindingFailureCode.BINDING_GRAPH_INVALID,
                BindingFailureCode.BINDING_NOT_LAYOUT_READY,
                BindingFailureCode.PHYSICAL_PORTS_NOT_RESOLVED,
                BindingFailureCode.LAYOUT_REQUIRED,
                BindingFailureCode.IMPLEMENTATION_RELOAD_IN_PROGRESS,
                BindingFailureCode.WRONG_THREAD,
                BindingFailureCode.WORLD_CONTEXT_NOT_REQUIRED);
        assertThat(Arrays.stream(BindingFailureCode.values()).map(Enum::name))
                .doesNotHaveDuplicates();
    }

    @Test
    void failureCarriesCompleteBoundedImmutableContext() {
        BindingFailure failure = new BindingFailure(
                BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                BindingStage.CATALOG_VALIDATION,
                Optional.of(id("planning:machine_0001")),
                Optional.of(id("create:milling")),
                Optional.of(id("create:milling/cobblestone")),
                List.of(id("virtual:millstone"), id("create:mechanical_millstone")),
                Optional.of(id("steve_industrial:create_runtime_1_20_1_6_0_6")),
                "sha256:runtime",
                "runtime_fingerprint=sha256:runtime",
                List.of("catalog:fingerprint", "candidate:create:mechanical_millstone"),
                "Implementation snapshot does not match the verified plan runtime",
                false,
                "Rebuild the implementation catalog from the current recipe snapshot");

        assertThat(failure.candidateImplementationIds())
                .containsExactly(id("create:mechanical_millstone"), id("virtual:millstone"));
        assertThat(failure.trace()).containsExactly(
                "catalog:fingerprint", "candidate:create:mechanical_millstone");
        assertThatThrownBy(() -> failure.candidateImplementationIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(failure.userInterventionRequired()).isFalse();
        assertThat(failure.safeNextStep()).contains("Rebuild");
    }

    @Test
    void failureCannotHideItsConstraintTraceOrSafeNextStep() {
        assertThatThrownBy(() -> new BindingFailure(
                BindingFailureCode.IMPLEMENTATION_NOT_FOUND,
                BindingStage.CANDIDATE_GENERATION,
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                "runtime:unknown", "", List.of(), "missing", true, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
