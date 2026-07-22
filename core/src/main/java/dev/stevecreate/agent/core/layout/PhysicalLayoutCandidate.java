package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Fully assembled but still unverified physical candidate. */
public record PhysicalLayoutCandidate(
        ResourceId id,
        VerifiedImplementationBoundPlan boundPlan,
        List<PhysicalMachinePlacement> placements,
        List<PhysicalRoute> routes,
        UnifiedMachineGraph unifiedGraph,
        String snapshotFingerprint,
        int candidatesExamined,
        int searchOperations,
        List<String> trace) {
    public PhysicalLayoutCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(boundPlan, "boundPlan");
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        Objects.requireNonNull(unifiedGraph, "unifiedGraph");
        Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint");
        if (placements.isEmpty() || candidatesExamined < 1 || searchOperations < 1) {
            throw new IllegalArgumentException("candidate evidence is incomplete");
        }
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
    }
}
