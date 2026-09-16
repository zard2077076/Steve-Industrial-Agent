package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact game-owned action binding used only by the isolated Bot GameTest. */
record TestOnlyBotTaskSpec(
        ResourceId taskId,
        BlockPos navigationTarget,
        Optional<BlockPos> blockTarget,
        Optional<BlockState> expectedBlockState,
        Optional<MaterialBinding> material) {
    TestOnlyBotTaskSpec {
        Objects.requireNonNull(taskId, "taskId");
        navigationTarget = immutable(navigationTarget, "navigationTarget");
        blockTarget = Objects.requireNonNull(blockTarget, "blockTarget")
                .map(value -> immutable(value, "blockTarget value"));
        expectedBlockState = Objects.requireNonNull(expectedBlockState, "expectedBlockState");
        material = Objects.requireNonNull(material, "material");
        if (blockTarget.isPresent() != expectedBlockState.isPresent()) {
            throw new IllegalArgumentException(
                    "Bot task block target and expected state must be both present or both absent");
        }
    }

    record MaterialBinding(
            ResourceId sourceId,
            ResourceId reservationId,
            ResourceId deliveryId,
            ResourceId resourceId,
            BlockPos sourcePosition,
            Item item,
            int quantity) {
        MaterialBinding {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(resourceId, "resourceId");
            sourcePosition = immutable(sourcePosition, "sourcePosition");
            Objects.requireNonNull(item, "item");
            if (quantity < 1 || quantity > 64) {
                throw new IllegalArgumentException("Bot test material quantity must be between 1 and 64");
            }
            var registryId = ForgeRegistries.ITEMS.getKey(item);
            if (registryId == null || !resourceId.toString().equals(registryId.toString())) {
                throw new IllegalArgumentException(
                        "Bot test material ResourceId does not match the registered item");
            }
        }
    }

    private static BlockPos immutable(BlockPos position, String name) {
        return Objects.requireNonNull(position, name).immutable();
    }
}
