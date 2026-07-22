package dev.stevecreate.agent.core.survey;

/** One atomic budget reservation. No partial counter update is permitted. */
public record SurveyBudgetCharge(
        int regions,
        int chunks,
        long readBytes,
        long retainedBytes,
        int candidateZones,
        int deepScanRadius) {
    public SurveyBudgetCharge {
        if (regions < 0 || chunks < 0 || readBytes < 0 || retainedBytes < 0
                || candidateZones < 0 || deepScanRadius < 0) {
            throw new IllegalArgumentException("survey budget charge is negative");
        }
    }

    public static SurveyBudgetCharge checkpoint() {
        return new SurveyBudgetCharge(0, 0, 0, 0, 0, 0);
    }
}
