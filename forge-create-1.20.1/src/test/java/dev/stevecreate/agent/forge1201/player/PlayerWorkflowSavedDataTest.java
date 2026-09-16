package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.core.player.WorkflowStage;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PlayerWorkflowSavedDataTest {
    @Test
    void roundTripsPlacementStateWithoutCreatingExecutionAuthority() {
        UUID player = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        PlayerWorkflowSavedData data = new PlayerWorkflowSavedData();
        var expected = new PlayerWorkflowSavedData.ProjectEntry(
                project, player, ResourceId.parse("create:cogwheel"), 4,
                ResourceId.parse("minecraft:overworld"), WorkflowStage.SITE_SURVEY,
                ProductionMode.ONCE, PlayerExecutionMode.BOTS, LayoutVariant.EXPANDABLE,
                QuarterTurn.CLOCKWISE_180, new BlockPos3i(12, 70, -9),
                91, 100, 120, "SURVEY_READY",
                "a".repeat(64), "b".repeat(64));
        data.put(expected);

        CompoundTag encoded = data.save(new CompoundTag());
        PlayerWorkflowSavedData decoded = PlayerWorkflowSavedData.load(encoded);

        assertThat(decoded.entry(player)).contains(expected);
        assertThat(encoded.toString()).doesNotContain("token", "password", "path");
    }

    @Test
    void schemaOneConstructionPlaceholderMigratesToMaterialSelection() {
        UUID player = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        PlayerWorkflowSavedData data = new PlayerWorkflowSavedData();
        data.put(new PlayerWorkflowSavedData.ProjectEntry(
                project, player, ResourceId.parse("create:cogwheel"), 1,
                ResourceId.parse("minecraft:overworld"), WorkflowStage.CONSTRUCTION,
                ProductionMode.ONCE, PlayerExecutionMode.BOTS, LayoutVariant.STANDARD,
                QuarterTurn.ZERO, new BlockPos3i(1, 64, 1), 9, 1, 2,
                "CONSTRUCTION_MATERIAL_SOURCE_REQUIRED", "a".repeat(64), "b".repeat(64)));
        CompoundTag encoded = data.save(new CompoundTag());
        encoded.putInt("Schema", 1);

        var migrated = PlayerWorkflowSavedData.load(encoded).entry(player).orElseThrow();
        assertThat(migrated.stage()).isEqualTo(WorkflowStage.MATERIAL_SOURCE_SELECTION);
        assertThat(migrated.statusCode()).isEqualTo("MATERIAL_SOURCE_SELECTION_REQUIRED");
    }
}
