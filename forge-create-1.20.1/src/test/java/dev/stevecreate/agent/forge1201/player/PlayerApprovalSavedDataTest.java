package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.siteprep.ApprovedObstacle;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalContext;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalState;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalToken;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PlayerApprovalSavedDataTest {
    @Test
    void roundTripsEveryExactAuthorityField() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        DemolitionApprovalContext context = DemolitionApprovalContext.create(
                projectId.toString(), ResourceId.parse("create:cogwheel"), 4,
                new BlockPos3i(12, 70, -9), QuarterTurn.CLOCKWISE_180,
                LayoutVariant.EXPANDABLE, "1".repeat(64), "light-natural-only/v1",
                PlayerExecutionMode.BOTS);
        DemolitionApprovalToken expected = new DemolitionApprovalToken(
                "demolition-approval:test", "world:test",
                ResourceId.parse("minecraft:overworld"), "2".repeat(64), "3".repeat(64),
                "4".repeat(64), "player:test", context,
                List.of(new ApprovedObstacle("obstacle:test", new BlockPos3i(13, 70, -9),
                        "5".repeat(64), ObstacleClassification.SAFE_NATURAL_CLEARABLE)),
                Instant.parse("2026-07-31T01:00:00Z"),
                Instant.parse("2026-07-31T01:02:00Z"), 1,
                DemolitionApprovalState.ACTIVE);
        PlayerApprovalSavedData data = new PlayerApprovalSavedData();
        data.put(projectId, expected);

        PlayerApprovalSavedData decoded = PlayerApprovalSavedData.load(
                data.save(new CompoundTag()));

        assertThat(decoded.token(projectId)).contains(expected);
    }
}
