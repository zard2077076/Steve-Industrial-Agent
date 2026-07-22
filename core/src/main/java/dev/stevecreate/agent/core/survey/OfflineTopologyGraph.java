package dev.stevecreate.agent.core.survey;

import java.util.List;

public record OfflineTopologyGraph(
        List<InfrastructureFinding> nodes,
        List<OfflineTopologyEdge> edges,
        boolean coverageComplete,
        List<SurveyLimitation> unscannedBoundaries) {
    public OfflineTopologyGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        unscannedBoundaries = List.copyOf(unscannedBoundaries);
        if (nodes.size() > 4_096 || edges.size() > 32_768 || unscannedBoundaries.size() > 256
                || !coverageComplete && unscannedBoundaries.isEmpty()
                || coverageComplete && !unscannedBoundaries.isEmpty()) {
            throw new IllegalArgumentException("offline topology graph coverage is invalid");
        }
    }
}
