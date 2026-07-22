package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** Explicit typed-failure branch for coordinate-free logical-graph mapping. */
public record LogicalGraphMappingFailureResult(LogicalGraphMappingFailure failure)
        implements LogicalGraphMappingResult {
    public LogicalGraphMappingFailureResult {
        Objects.requireNonNull(failure, "failure");
    }
}
