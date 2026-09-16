package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.industrial.IndustrialCompletionReportV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class IndustrialPlayerOrderSavedDataTest {
    @Test
    void roundTripsCommonOrderAndTypedCompletionEvidence() {
        UUID id = UUID.randomUUID();
        ResourceId units = id("steve_industrial:material_units");
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                Map.of(units, 4L), Map.of(units, 4L), Map.of(units, 3L), Map.of(units, 1L),
                Map.of(id("immersiveengineering:fe"), 2_400L), Map.of(),
                Map.of(id("immersiveengineering:plate_iron"), 1L), 0, 0, 0, 0, 0, 0, 0,
                true, "b".repeat(64));
        IndustrialPlayerOrderV1 order = new IndustrialPlayerOrderV1(id, id, UUID.randomUUID(),
                "isolated-world", id("minecraft:overworld"), id("steve_industrial:metal_press"),
                id("immersiveengineering:plate_iron"), id("immersiveengineering:metalpress/plate_iron"),
                new BlockPos3i(1, 64, 1), "a".repeat(64), "ie-runtime", "b".repeat(64),
                IndustrialLifecyclePhase.COMPLETED, "REPORT_GENERATED",
                Set.of(id("steve_industrial:materials_withdrawn")), 1, 2, 3, Optional.of(report));
        IndustrialPlayerOrderSavedData data = new IndustrialPlayerOrderSavedData();
        data.put(order);
        CompoundTag encoded = data.save(new CompoundTag());
        IndustrialPlayerOrderV1 restored = IndustrialPlayerOrderSavedData.load(encoded).order(id).orElseThrow();
        assertThat(restored).isEqualTo(order);
        assertThat(restored.report().orElseThrow().accepted(
                id("immersiveengineering:fe"), 2_400,
                id("immersiveengineering:plate_iron"), 1)).isTrue();
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
