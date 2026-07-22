package dev.stevecreate.agent.core.survey;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Final survey data is permanently non-executable and carries explicit privacy/write flags. */
public record FormalWorldSurvey(
        FormalWorldIdentity worldIdentity,
        SurveyStatus status,
        String preSurveyFingerprint,
        String postSurveyFingerprint,
        boolean externalMutation,
        boolean formalWorldWrite,
        boolean inventoryContentsRead,
        boolean executionAllowed,
        List<DimensionSurvey> dimensions,
        List<CandidateIndustrialZone> candidateZones,
        SurveyCoverage coverage,
        List<SurveyEvidence> evidence,
        List<SurveyLimitation> limitations) {
    public FormalWorldSurvey {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(status, "status");
        preSurveyFingerprint = SurveyModelValues.hash(preSurveyFingerprint, "preSurveyFingerprint");
        postSurveyFingerprint = SurveyModelValues.hash(postSurveyFingerprint, "postSurveyFingerprint");
        if (externalMutation || formalWorldWrite || inventoryContentsRead || executionAllowed) {
            throw new IllegalArgumentException("formal survey cannot carry mutation, private-content or execution authority");
        }
        if (status == SurveyStatus.COMPLETE && !preSurveyFingerprint.equals(postSurveyFingerprint)) {
            throw new IllegalArgumentException("complete survey requires an unchanged formal fingerprint");
        }
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        candidateZones = List.copyOf(Objects.requireNonNull(candidateZones, "candidateZones"));
        Objects.requireNonNull(coverage, "coverage");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (dimensions.size() > 64 || candidateZones.size() > 16
                || evidence.size() > 16_384 || limitations.size() > 16_384) {
            throw new IllegalArgumentException("formal survey is too large");
        }
        List<DimensionSurvey> sortedDimensions = dimensions.stream()
                .sorted(Comparator.comparing(value -> value.dimension().toString())).toList();
        List<CandidateIndustrialZone> sortedZones = candidateZones.stream()
                .sorted(Comparator.comparingInt(CandidateIndustrialZone::totalScore).reversed()
                        .thenComparing(CandidateIndustrialZone::candidateId)).toList();
        if (!dimensions.equals(sortedDimensions) || !candidateZones.equals(sortedZones)) {
            throw new IllegalArgumentException("survey output is not deterministically ordered");
        }
    }
}
