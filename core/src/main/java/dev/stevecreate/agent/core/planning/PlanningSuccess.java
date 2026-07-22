package dev.stevecreate.agent.core.planning;

import java.util.List;
import java.util.Objects;

/** One or more deterministic satisfiable dependency-graph alternatives. */
public record PlanningSuccess(List<ProcessDependencyGraph> graphs) implements PlanningResult {
    public static final int MAX_GRAPHS = 64;

    public PlanningSuccess {
        Objects.requireNonNull(graphs, "graphs");
        if (graphs.isEmpty() || graphs.size() > MAX_GRAPHS) {
            throw new IllegalArgumentException("Success graph count violates planning bounds");
        }
        graphs = List.copyOf(graphs);
        if (graphs.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("graphs element");
        }
    }
}
