package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.industrial.MetalPressCompletionReport;
import dev.stevecreate.agent.core.industrial.MetalPressDurableEffect;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.industrial.MetalPressProductionOrder;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.BaselineBlock;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.StoredOrder;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class MetalPressOrderSavedDataTest {
    @Test
    void roundTripsInterruptedOrderEffectsReportAndExactBaseline() {
        UUID orderId = UUID.randomUUID();
        MetalPressCompletionReport report = new MetalPressCompletionReport(
                20, 20, 5, 15, 2_400, 1, 0, 0, 0, 0,
                0, 0, true, false, "b".repeat(64));
        MetalPressProductionOrder order = new MetalPressProductionOrder(orderId,
                UUID.randomUUID(), UUID.randomUUID(), id("minecraft:overworld"),
                new BlockPos3i(8, 64, 8), new BlockPos3i(0, 64, 0),
                id("immersiveengineering:metalpress/plate_iron"), "a".repeat(64),
                "ie-1.20.1-10.2.0-183", "b".repeat(64), 2_400,
                MetalPressOrderStage.REPORT_GENERATED,
                Set.of(MetalPressDurableEffect.MATERIAL_WITHDRAWAL,
                        MetalPressDurableEffect.INPUT_ADMISSION,
                        MetalPressDurableEffect.ENERGY_SETTLEMENT,
                        MetalPressDurableEffect.OUTPUT_CLAIM,
                        MetalPressDurableEffect.MATERIAL_RETURN,
                        MetalPressDurableEffect.COMPLETION_REPORT),
                2_422, 2_400, 1, "COMPLETION_REPORT_GENERATED", 1, 20, 14,
                Optional.of(report));
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        StoredOrder stored = new StoredOrder(order,
                java.util.List.of(new BaselineBlock(new BlockPos3i(8, 64, 8), air)));
        MetalPressOrderSavedData data = new MetalPressOrderSavedData();
        data.put(stored);

        MetalPressOrderSavedData restored = MetalPressOrderSavedData.load(
                data.save(new CompoundTag()));

        assertThat(restored.orders()).containsOnlyKeys(orderId);
        assertThat(restored.order(orderId)).contains(stored);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
