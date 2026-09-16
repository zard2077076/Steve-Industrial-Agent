package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ExecutionContext;

/**
 * Dedicated bounded terrain-preparation action boundary; it is not a ConstructionExecutor.
 *
 * <p>The graph-neutral fleet owns assignment, leases and recovery. This executor owns only one
 * already-approved terrain task and must return objective terrain/salvage evidence.</p>
 */
@FunctionalInterface
public interface BotClearingExecutor {
    BotClearingUpdate execute(
            TerrainPreparationPlan plan,
            TerrainPreparationTask task,
            Assignment assignment,
            ExecutionContext context);
}
