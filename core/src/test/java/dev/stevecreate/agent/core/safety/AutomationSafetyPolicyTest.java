package dev.stevecreate.agent.core.safety;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutomationSafetyPolicyTest {
    private static final ResourceId SCAN = ResourceId.parse("steve_industrial:scan");
    private static final ResourceId REACTOR = ResourceId.parse("steve_industrial:build_fission_reactor");

    @Test
    void allowsOnlyExplicitlyRegisteredRoutineOperations() {
        AutomationSafetyPolicy policy = new AutomationSafetyPolicy(Set.of(SCAN));
        assertThat(policy.evaluate(SCAN, SafetyCategory.READ_ONLY).allowed()).isTrue();
    }

    @Test
    void deniesUnknownOperations() {
        AutomationSafetyPolicy policy = new AutomationSafetyPolicy(Set.of(SCAN));
        assertThat(policy.evaluate(ResourceId.parse("steve_industrial:place_block"),
                SafetyCategory.ROUTINE_WORLD_MUTATION).reasonCode()).isEqualTo("OPERATION_NOT_ALLOWLISTED");
    }

    @Test
    void nuclearOperationsRemainHardDisabledEvenIfMistakenlyAllowlisted() {
        AutomationSafetyPolicy policy = new AutomationSafetyPolicy(Set.of(REACTOR));
        SafetyDecision decision = policy.evaluate(REACTOR, SafetyCategory.NUCLEAR_OR_RADIATION);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("NUCLEAR_AUTOMATION_DISABLED");
    }
}

