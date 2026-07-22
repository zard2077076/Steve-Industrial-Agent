package dev.stevecreate.agent.core.survey;

public record SurveyPlanContext(String worldIdentity, String canonicalPath, String fingerprint) {
    public SurveyPlanContext {
        worldIdentity = SurveyModelValues.text(worldIdentity, "worldIdentity", 4_096);
        canonicalPath = SurveyModelValues.text(canonicalPath, "canonicalPath", 16_384);
        fingerprint = SurveyModelValues.hash(fingerprint, "fingerprint");
    }
}
