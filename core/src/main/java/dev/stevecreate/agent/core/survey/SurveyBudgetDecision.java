package dev.stevecreate.agent.core.survey;

import java.util.Objects;
import java.util.Optional;

public record SurveyBudgetDecision(
        boolean permitted,
        SurveyBudgetUsage usage,
        Optional<SurveyBudgetExhaustion> exhaustion) {
    public SurveyBudgetDecision {
        Objects.requireNonNull(usage, "usage");
        exhaustion = Objects.requireNonNull(exhaustion, "exhaustion");
        if (permitted == exhaustion.isPresent()) {
            throw new IllegalArgumentException("budget decision state is inconsistent");
        }
    }
}
