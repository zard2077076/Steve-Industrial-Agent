package dev.stevecreate.agent.core.survey;

import java.util.Comparator;
import java.util.List;

public record ClassifiedChunkInfrastructure(
        int chunkX,
        int chunkZ,
        List<InfrastructureFinding> findings,
        List<SurveyLimitation> limitations) {
    private static final Comparator<InfrastructureFinding> ORDER =
            Comparator.comparingInt((InfrastructureFinding finding) -> finding.location().position().y())
                    .thenComparingInt(finding -> finding.location().position().z())
                    .thenComparingInt(finding -> finding.location().position().x())
                    .thenComparing(finding -> finding.resourceId().toString())
                    .thenComparing(finding -> finding.category().name());

    public ClassifiedChunkInfrastructure {
        findings = List.copyOf(findings);
        limitations = List.copyOf(limitations);
        if (findings.size() > 4_096 || limitations.size() > 256 || !findings.equals(findings.stream().sorted(ORDER).toList())) {
            throw new IllegalArgumentException("classified chunk output is invalid or unsorted");
        }
    }

    static Comparator<InfrastructureFinding> order() {
        return ORDER;
    }
}
