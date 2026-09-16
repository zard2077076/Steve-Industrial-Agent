package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.core.process.ProcessResource;
import net.minecraft.world.item.ItemStack;

/** Exact session-owned access to fuel already reserved from the plan resource buffer. */
interface CreateV606ReservedFuelAccess {
    AdapterResult<ItemStack> extract(ProcessResource fuel, long currentTick);

    AdapterResult<Integer> restore(ItemStack fuel, long currentTick);

    void markConsumed(ProcessResource fuel, long currentTick);
}
