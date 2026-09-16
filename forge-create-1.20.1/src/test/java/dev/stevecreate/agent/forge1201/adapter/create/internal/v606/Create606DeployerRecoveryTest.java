package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.plan.DeployerPlan;
import org.junit.jupiter.api.Test;

class Create606DeployerRecoveryTest {
    private static final DeployerPlan PLAN =
            DeployerPlan.cogwheel(new BlockPos3i(0, 64, 0));

    @Test
    void restoresTheTwoOwnedFlowCellsWithoutReplayingBillablePlacements() {
        int billable = PLAN.placements().size();

        assertEquals(billable,
                Create606DeployerExecutor.recoveredPlacementCursor(PLAN, billable));
        assertEquals(0,
                Create606DeployerExecutor.recoveredPilotFlowCursor(PLAN, billable));
        assertEquals(billable,
                Create606DeployerExecutor.recoveredPlacementCursor(PLAN, billable + 1));
        assertEquals(1,
                Create606DeployerExecutor.recoveredPilotFlowCursor(PLAN, billable + 1));
        assertEquals(billable,
                Create606DeployerExecutor.recoveredPlacementCursor(PLAN, billable + 2));
        assertEquals(2,
                Create606DeployerExecutor.recoveredPilotFlowCursor(PLAN, billable + 2));
    }

    @Test
    void refusesARecoveryJournalBeyondTheBoundedWaterChannel() {
        int outside = PLAN.placements().size() + 3;
        assertThrows(IllegalArgumentException.class,
                () -> Create606DeployerExecutor.recoveredPlacementCursor(PLAN, outside));
        assertThrows(IllegalArgumentException.class,
                () -> Create606DeployerExecutor.recoveredPilotFlowCursor(PLAN, -1));
    }
}
