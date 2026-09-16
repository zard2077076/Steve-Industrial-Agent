package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import org.junit.jupiter.api.Test;

class Create606BasinMixerRecoveryTest {
    private static final BasinMixerPlan PLAN =
            BasinMixerPlan.andesiteAlloy(new BlockPos3i(0, 64, 0));

    @Test
    void restoresTheTwoOwnedFlowCellsWithoutReplayingBillablePlacements() {
        int billable = PLAN.placements().size();

        assertEquals(billable,
                Create606BasinMixerExecutor.recoveredPlacementCursor(PLAN, billable));
        assertEquals(0,
                Create606BasinMixerExecutor.recoveredPilotFlowCursor(PLAN, billable));
        assertEquals(billable,
                Create606BasinMixerExecutor.recoveredPlacementCursor(PLAN, billable + 1));
        assertEquals(1,
                Create606BasinMixerExecutor.recoveredPilotFlowCursor(PLAN, billable + 1));
        assertEquals(billable,
                Create606BasinMixerExecutor.recoveredPlacementCursor(PLAN, billable + 2));
        assertEquals(2,
                Create606BasinMixerExecutor.recoveredPilotFlowCursor(PLAN, billable + 2));
    }

    @Test
    void refusesARecoveryJournalBeyondTheBoundedWaterChannel() {
        int outside = PLAN.placements().size() + 3;
        assertThrows(IllegalArgumentException.class,
                () -> Create606BasinMixerExecutor.recoveredPlacementCursor(PLAN, outside));
        assertThrows(IllegalArgumentException.class,
                () -> Create606BasinMixerExecutor.recoveredPilotFlowCursor(PLAN, -1));
    }
}
