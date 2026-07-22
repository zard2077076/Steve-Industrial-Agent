package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

/** Complete typed refusal context. Placeholder identities are explicit rather than null. */
public record FormalSurveyFailure(
        FormalSurveyFailureCode code,
        FormalSurveyStage stage,
        String worldIdentity,
        String canonicalPath,
        String dimension,
        String region,
        String chunk,
        String fingerprint,
        String budget,
        List<String> evidence,
        String reason,
        String safeNextStep) {
    public FormalSurveyFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        worldIdentity = text(worldIdentity, "worldIdentity");
        canonicalPath = text(canonicalPath, "canonicalPath");
        dimension = text(dimension, "dimension");
        region = text(region, "region");
        chunk = text(chunk, "chunk");
        fingerprint = text(fingerprint, "fingerprint");
        budget = text(budget, "budget");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 128 || evidence.stream().anyMatch(item ->
                item == null || item.isBlank() || item.length() > 4_096)) {
            throw new IllegalArgumentException("evidence is empty or invalid");
        }
        reason = text(reason, "reason");
        safeNextStep = text(safeNextStep, "safeNextStep");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
