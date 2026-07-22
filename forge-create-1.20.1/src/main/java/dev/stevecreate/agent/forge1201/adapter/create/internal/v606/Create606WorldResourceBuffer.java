package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** Explicit repository-test chest used as the real source/sink for goal-driven resources. */
final class Create606WorldResourceBuffer {
    private final ServerLevel level;
    private final BlockPos3i position;

    Create606WorldResourceBuffer(ServerLevel level, BlockPos3i position) {
        this.level = Objects.requireNonNull(level, "level");
        this.position = Objects.requireNonNull(position, "position");
    }

    BlockPos3i position() { return position; }

    AdapterResult<Map<ResourceId, Long>> snapshot() {
        ChestBlockEntity chest = chest();
        if (chest == null) return failure("Resource buffer is not a loaded chest at " + position);
        Map<ResourceId, Long> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            ResourceId id = itemId(stack);
            if (id != null) counts.merge(id, (long) stack.getCount(), Math::addExact);
        }
        return new AdapterResult.Success<>(Map.copyOf(counts));
    }

    AdapterResult<ItemStack> extractExact(ResourceId itemId, int count) {
        Optional<AdapterResult<ItemStack>> guarded = guardFailure();
        if (guarded.isPresent()) return guarded.orElseThrow();
        Objects.requireNonNull(itemId, "itemId");
        if (count < 1 || count > 64) return failure("Requested buffer extraction is outside 1..64");
        ChestBlockEntity chest = chest();
        if (chest == null) return failure("Resource buffer is not a loaded chest at " + position);
        Item item = registeredItem(itemId);
        if (item == null) return failure("Requested buffer item is not registered: " + itemId);
        int available = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) available += stack.getCount();
        }
        if (available < count) return failure(
                "Resource buffer lacks " + itemId + ": required=" + count + " available=" + available);
        int remaining = count;
        for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.is(item)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
            remaining -= taken;
        }
        chest.setChanged();
        return new AdapterResult.Success<>(new ItemStack(item, count));
    }

    AdapterResult<Integer> insertExact(ItemStack offered) {
        Optional<AdapterResult<Integer>> guarded = guardFailure();
        if (guarded.isPresent()) return guarded.orElseThrow();
        Objects.requireNonNull(offered, "offered");
        if (offered.isEmpty()) return failure("Cannot insert an empty resource stack");
        ChestBlockEntity chest = chest();
        if (chest == null) return failure("Resource buffer is not a loaded chest at " + position);
        int capacity = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack present = chest.getItem(slot);
            if (present.isEmpty()) capacity += offered.getMaxStackSize();
            else if (ItemStack.isSameItemSameTags(present, offered)) {
                capacity += Math.max(0, Math.min(present.getMaxStackSize(), chest.getMaxStackSize())
                        - present.getCount());
            }
        }
        if (capacity < offered.getCount()) return failure("Resource buffer lacks output capacity");
        int remaining = offered.getCount();
        for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
            ItemStack present = chest.getItem(slot);
            if (!present.isEmpty() && ItemStack.isSameItemSameTags(present, offered)) {
                int moved = Math.min(remaining, Math.min(present.getMaxStackSize(), chest.getMaxStackSize())
                        - present.getCount());
                if (moved > 0) {
                    present.grow(moved);
                    remaining -= moved;
                }
            }
        }
        for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
            if (!chest.getItem(slot).isEmpty()) continue;
            int moved = Math.min(remaining, Math.min(offered.getMaxStackSize(), chest.getMaxStackSize()));
            ItemStack placed = offered.copy();
            placed.setCount(moved);
            chest.setItem(slot, placed);
            remaining -= moved;
        }
        chest.setChanged();
        return new AdapterResult.Success<>(offered.getCount());
    }

    AdapterResult<Integer> collectMillstoneOutput(WaterWheelMillstonePlan plan) {
        Optional<AdapterResult<Integer>> guarded = guardFailure();
        if (guarded.isPresent()) return guarded.orElseThrow();
        Objects.requireNonNull(plan, "plan");
        BlockPos3i millPosition = plan.placement(WaterWheelMillstoneRole.MILLSTONE).position();
        if (!(level.getBlockEntity(pos(millPosition)) instanceof MillstoneBlockEntity millstone)) {
            return failure("Verified millstone output block entity is unavailable");
        }
        ResourceId expected = plan.process().expectedOutputItem();
        int count = plan.process().minimumOutputCount();
        Item item = registeredItem(expected);
        if (item == null) return failure("Expected millstone output is unregistered: " + expected);
        int available = 0;
        for (int slot = 0; slot < millstone.outputInv.getSlots(); slot++) {
            ItemStack stack = millstone.outputInv.getStackInSlot(slot);
            if (stack.is(item)) available += stack.getCount();
        }
        if (available < count) return failure("Verified millstone output quantity changed before collection");
        if (capacityFor(new ItemStack(item, count)) < count) return failure("Resource buffer lacks output capacity");
        int remaining = count;
        for (int slot = 0; slot < millstone.outputInv.getSlots() && remaining > 0; slot++) {
            ItemStack stack = millstone.outputInv.getStackInSlot(slot);
            if (!stack.is(item)) continue;
            ItemStack extracted = millstone.outputInv.extractItem(slot, remaining, false);
            remaining -= extracted.getCount();
        }
        AdapterResult<Integer> inserted = insertExact(new ItemStack(item, count));
        if (inserted instanceof AdapterResult.Failure<Integer>) {
            millstone.outputInv.insertItem(0, new ItemStack(item, count), false);
            return inserted;
        }
        millstone.setChanged();
        return inserted;
    }

    AdapterResult<Integer> collectPressOutput(BeltPressPlan plan) {
        Optional<AdapterResult<Integer>> guarded = guardFailure();
        if (guarded.isPresent()) return guarded.orElseThrow();
        Objects.requireNonNull(plan, "plan");
        BlockPos3i outputPosition = plan.placement(BeltPressRole.OUTPUT_CHEST).position();
        if (!(level.getBlockEntity(pos(outputPosition)) instanceof ChestBlockEntity output)) {
            return failure("Verified press output chest is unavailable");
        }
        ResourceId expected = plan.process().expectedOutputItem();
        int count = plan.process().minimumOutputCount();
        Item item = registeredItem(expected);
        if (item == null) return failure("Expected press output is unregistered: " + expected);
        int available = 0;
        for (int slot = 0; slot < output.getContainerSize(); slot++) {
            if (output.getItem(slot).is(item)) available += output.getItem(slot).getCount();
        }
        if (available < count) return failure("Verified press output quantity changed before collection");
        if (capacityFor(new ItemStack(item, count)) < count) return failure("Resource buffer lacks output capacity");
        int remaining = count;
        for (int slot = 0; slot < output.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = output.getItem(slot);
            if (!stack.is(item)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) output.setItem(slot, ItemStack.EMPTY);
            remaining -= taken;
        }
        AdapterResult<Integer> inserted = insertExact(new ItemStack(item, count));
        if (inserted instanceof AdapterResult.Failure<Integer>) {
            restoreToChest(output, new ItemStack(item, count));
            return inserted;
        }
        output.setChanged();
        return inserted;
    }

    private ChestBlockEntity chest() {
        if (!level.getServer().isSameThread() || !level.hasChunk(position.x() >> 4, position.z() >> 4)) {
            return null;
        }
        return level.getBlockEntity(new BlockPos(position.x(), position.y(), position.z()))
                instanceof ChestBlockEntity value ? value : null;
    }

    private int capacityFor(ItemStack offered) {
        ChestBlockEntity chest = chest();
        if (chest == null) return 0;
        int capacity = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack present = chest.getItem(slot);
            if (present.isEmpty()) capacity += Math.min(offered.getMaxStackSize(), chest.getMaxStackSize());
            else if (ItemStack.isSameItemSameTags(present, offered)) {
                capacity += Math.max(0, Math.min(present.getMaxStackSize(), chest.getMaxStackSize())
                        - present.getCount());
            }
        }
        return capacity;
    }

    private static void restoreToChest(ChestBlockEntity chest, ItemStack value) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) {
                chest.setItem(slot, value);
                chest.setChanged();
                return;
            }
        }
        throw new IllegalStateException("Prechecked output chest restoration unexpectedly had no slot");
    }

    private static BlockPos pos(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceId itemId(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : new ResourceId(key.getNamespace(), key.getPath());
    }

    private static Item registeredItem(ResourceId itemId) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath(itemId.namespace(), itemId.path());
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item != null && key.equals(ForgeRegistries.ITEMS.getKey(item)) ? item : null;
    }

    private static <T> AdapterResult<T> failure(String detail) {
        return new AdapterResult.Failure<>(AdapterFailureCode.PROCESSING_FAILED, detail);
    }

    private <T> Optional<AdapterResult<T>> guardFailure() {
        var failure = CreateExecutionWorldGuard.mutationFailure(level);
        return failure.map(detail -> new AdapterResult.Failure<T>(
                AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + detail));
    }
}
