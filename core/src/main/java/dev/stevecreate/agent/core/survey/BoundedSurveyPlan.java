package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

public record BoundedSurveyPlan(
        SurveyStatus status,
        SurveyBudget budget,
        SurveyBudgetUsage usage,
        List<SurveyRegionWork> work,
        List<FormalSurveyFailure> failures,
        List<SurveyLimitation> limitations) {
    public BoundedSurveyPlan {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(usage, "usage");
        work = List.copyOf(work);
        failures = List.copyOf(failures);
        limitations = List.copyOf(limitations);
        if (work.size() > budget.maximumRegions()
                || (status == SurveyStatus.SURVEY_PARTIAL
                    && (failures.stream().noneMatch(f -> f.code() == FormalSurveyFailureCode.SURVEY_BUDGET_EXHAUSTED)
                        || failures.stream().noneMatch(f -> f.code() == FormalSurveyFailureCode.SURVEY_PARTIAL)))
                || (status == SurveyStatus.COMPLETE && (!failures.isEmpty() || !limitations.isEmpty()))) {
            throw new IllegalArgumentException("bounded survey plan is inconsistent");
        }
    }
}
