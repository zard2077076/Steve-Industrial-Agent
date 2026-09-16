package dev.stevecreate.agent.core.execution.fleet;

import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ExecutionContext;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Update;

/** Domain-specific work execution behind the shared assignment/recovery state machine. */
@FunctionalInterface
public interface FleetTaskDispatcher<G, T> {
    Update execute(G graph, T task, Assignment assignment, ExecutionContext context);
}
