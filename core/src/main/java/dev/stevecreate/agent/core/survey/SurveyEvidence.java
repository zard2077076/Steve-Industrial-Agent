package dev.stevecreate.agent.core.survey;

import java.util.Objects;

public record SurveyEvidence(
        SurveyEvidenceSource source,
        String relativeSourcePath,
        String fileFingerprint,
        long parseGeneration,
        SurveyConfidence confidence,
        String detail,
        boolean offlineObservation) {
    public SurveyEvidence {
        Objects.requireNonNull(source, "source");
        relativeSourcePath = SurveyModelValues.text(relativeSourcePath, "relativeSourcePath", 16_384);
        if (relativeSourcePath.startsWith("/") || relativeSourcePath.contains("..")
                || relativeSourcePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relativeSourcePath is unsafe");
        }
        fileFingerprint = SurveyModelValues.hash(fileFingerprint, "fileFingerprint");
        if (parseGeneration < 0) throw new IllegalArgumentException("parseGeneration is negative");
        Objects.requireNonNull(confidence, "confidence");
        detail = SurveyModelValues.text(detail, "detail", 16_384);
    }
}
