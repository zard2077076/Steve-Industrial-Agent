package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** Successful coordinate-free logical-graph mapping branch. */
public record LogicalGraphMappingSuccess(LogicalMachineGraph graph)
        implements LogicalGraphMappingResult {
    public LogicalGraphMappingSuccess {
        Objects.requireNonNull(graph, "graph");
    }
}
