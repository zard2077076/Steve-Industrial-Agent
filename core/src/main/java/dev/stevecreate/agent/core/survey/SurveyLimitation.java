package dev.stevecreate.agent.core.survey;

public record SurveyLimitation(
        String code,
        String scope,
        String message,
        boolean runtimeConfirmationRequired,
        boolean blocksCandidateReadiness) {
    public SurveyLimitation {
        code = SurveyModelValues.text(code, "code", 256);
        if (!code.matches("[A-Z0-9_]+")) throw new IllegalArgumentException("code is not stable uppercase data");
        scope = SurveyModelValues.text(scope, "scope", 4_096);
        message = SurveyModelValues.text(message, "message", 16_384);
    }
}
