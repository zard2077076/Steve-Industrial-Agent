package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record DimensionSurvey(
        ResourceId dimension,
        SurveyStatus status,
        List<RegionSurvey> regions,
        List<SurveyEvidence> evidence,
        List<SurveyLimitation> limitations) {
    public DimensionSurvey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(status, "status");
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (regions.size() > 16_384 || evidence.size() > 4_096 || limitations.size() > 4_096) {
            throw new IllegalArgumentException("dimension survey is too large");
        }
        List<RegionSurvey> sorted = regions.stream().sorted(Comparator.comparingInt(RegionSurvey::regionX)
                .thenComparingInt(RegionSurvey::regionZ)).toList();
        if (!regions.equals(sorted)) throw new IllegalArgumentException("regions are not sorted");
    }
}
