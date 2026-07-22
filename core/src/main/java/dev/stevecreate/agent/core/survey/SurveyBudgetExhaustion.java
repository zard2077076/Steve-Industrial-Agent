package dev.stevecreate.agent.core.survey;

import java.util.Objects;

public record SurveyBudgetExhaustion(
        SurveyBudgetDimension dimension,
        long current,
        long requested,
        long maximum,
        String reason) {
    public SurveyBudgetExhaustion {
        Objects.requireNonNull(dimension, "dimension");
        if (current < 0 || requested < 0 || maximum < 0) {
            throw new IllegalArgumentException("exhaustion values are negative");
        }
        reason = SurveyModelValues.text(reason, "reason", 4_096);
    }
}
