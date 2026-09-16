package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.BaselineBlock;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.StoredOrder;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class AlloySmelterOrderSavedDataTest {
    @Test
    void roundTripsExactPhysicalBindingWithoutDuplicatingLifecycleState() {
        UUID orderId = UUID.randomUUID();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        StoredOrder stored = new StoredOrder(orderId, UUID.randomUUID(),
                id("minecraft:overworld"), new BlockPos3i(10, 64, 10),
                new BlockPos3i(0, 64, 0), new BlockPos3i(7, 64, 8),
                id("immersiveengineering:alloysmelter/brass"),
                id("minecraft:copper_ingot"), 3, id("create:zinc_ingot"), 1,
                id("minecraft:coal"), 1, id("create:brass_ingot"), 2,
                "a".repeat(64), "10.2.0-183", "b".repeat(64),
                List.of(new BaselineBlock(new BlockPos3i(10, 64, 10), air)));
        AlloySmelterOrderSavedData data = new AlloySmelterOrderSavedData();
        data.put(stored);

        StoredOrder restored = AlloySmelterOrderSavedData.load(
                data.save(new CompoundTag())).order(orderId).orElseThrow();

        assertThat(restored).isEqualTo(stored);
        assertThat(restored.output()).isEqualTo(id("create:brass_ingot"));
        assertThat(restored.fuelCount()).isEqualTo(1);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
