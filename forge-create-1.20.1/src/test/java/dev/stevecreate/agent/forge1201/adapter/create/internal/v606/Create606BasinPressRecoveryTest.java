package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.plan.BasinPressPlan;
import org.junit.jupiter.api.Test;

class Create606BasinPressRecoveryTest {
    private static final BasinPressPlan PLAN =
            BasinPressPlan.blazeCakeBase(new BlockPos3i(0, 64, 0));

    @Test
    void restoresTheTwoOwnedFlowCellsWithoutReplayingBillablePlacements() {
        int billable = PLAN.placements().size();
        assertEquals(billable,
                Create606BasinPressExecutor.recoveredPlacementCursor(PLAN, billable));
        assertEquals(0,
                Create606BasinPressExecutor.recoveredPilotFlowCursor(PLAN, billable));
        assertEquals(billable,
                Create606BasinPressExecutor.recoveredPlacementCursor(PLAN, billable + 1));
        assertEquals(1,
                Create606BasinPressExecutor.recoveredPilotFlowCursor(PLAN, billable + 1));
        assertEquals(2,
                Create606BasinPressExecutor.recoveredPilotFlowCursor(PLAN, billable + 2));
    }

    @Test
    void refusesARecoveryJournalBeyondTheBoundedWaterChannel() {
        assertThrows(IllegalArgumentException.class,
                () -> Create606BasinPressExecutor.recoveredPlacementCursor(
                        PLAN, PLAN.placements().size() + 3));
        assertThrows(IllegalArgumentException.class,
                () -> Create606BasinPressExecutor.recoveredPilotFlowCursor(PLAN, -1));
    }
}
