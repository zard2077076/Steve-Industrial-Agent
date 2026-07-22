package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable typed explanation of an expected logical-graph mapping failure. */
public record LogicalGraphMappingFailure(
        ResourceId candidateId,
        LogicalGraphMappingFailureCode code,
        LogicalGraphMappingStage stage,
        Optional<ResourceId> stepId,
        Optional<ResourceId> nodeId,
        Optional<ResourceId> resourceId,
        List<ResourceId> trace,
        String detail) {
    public static final int MAX_TRACE = 68;
    public static final int MAX_DETAIL_LENGTH = 1_024;

    public LogicalGraphMappingFailure {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        stepId = Objects.requireNonNull(stepId, "stepId");
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        resourceId = Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(trace, "trace");
        if (trace.isEmpty() || trace.size() > MAX_TRACE) {
            throw new IllegalArgumentException("trace count violates mapping failure bounds");
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
