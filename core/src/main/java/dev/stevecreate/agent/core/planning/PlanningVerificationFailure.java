package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable typed explanation for one rejected logical candidate. */
public record PlanningVerificationFailure(
        PlanningVerificationFailureCode code,
        PlanningVerificationCheck check,
        ResourceId candidateId,
        ResourceId graphId,
        Optional<ResourceId> nodeId,
        Optional<ResourceId> edgeId,
        Optional<ResourceId> resourceId,
        List<ResourceId> trace,
        String detail) {
    public static final int MAX_TRACE = 70;
    public static final int MAX_DETAIL_LENGTH = 1_024;

    public PlanningVerificationFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(graphId, "graphId");
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        edgeId = Objects.requireNonNull(edgeId, "edgeId");
        resourceId = Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(trace, "trace");
        if (trace.isEmpty() || trace.size() > MAX_TRACE) {
            throw new IllegalArgumentException("trace count violates verification bounds");
        }
        List<ResourceId> traceCopy = new ArrayList<>(trace.size());
        for (ResourceId value : trace) {
            traceCopy.add(Objects.requireNonNull(value, "trace element"));
        }
        trace = List.copyOf(traceCopy);
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail must be nonblank and bounded");
        }
    }
}
