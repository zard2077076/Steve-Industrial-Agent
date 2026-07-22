package dev.stevecreate.agent.core.safety;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Set;

/**
 * Deny-by-default gate in front of every future action primitive.
 * Nuclear and radiation operations are hard-disabled during the current project phase.
 */
public final class AutomationSafetyPolicy {
    private final Set<ResourceId> allowedOperations;

    public AutomationSafetyPolicy(Set<ResourceId> allowedOperations) {
        this.allowedOperations = Set.copyOf(Objects.requireNonNull(allowedOperations, "allowedOperations"));
    }

    public SafetyDecision evaluate(ResourceId operationId, SafetyCategory category) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(category, "category");
        if (category == SafetyCategory.NUCLEAR_OR_RADIATION) {
            return SafetyDecision.deny("NUCLEAR_AUTOMATION_DISABLED");
        }
        if (!allowedOperations.contains(operationId)) {
            return SafetyDecision.deny("OPERATION_NOT_ALLOWLISTED");
        }
        return SafetyDecision.allow();
    }
}

