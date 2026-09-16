package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.AnchorSource;
import dev.stevecreate.agent.core.siteprep.ConfirmedSiteSelection;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import dev.stevecreate.agent.core.siteprep.ObstacleFinding;
import dev.stevecreate.agent.core.siteprep.PlacementAnchor;
import dev.stevecreate.agent.core.siteprep.PreparedConstructionSite;
import dev.stevecreate.agent.core.siteprep.SalvageEntry;
import dev.stevecreate.agent.core.siteprep.SalvageLedger;
import dev.stevecreate.agent.core.siteprep.SiteFacing;
import dev.stevecreate.agent.core.siteprep.TerrainMutationEvidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PlayerSitePreparationSavedDataTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Instant PREPARED_AT = Instant.ofEpochSecond(1_787_236_010L, 123_456_789);

    @Test
    void restoresPreparedSiteEvidenceExactlyAsItWasWritten() {
        PlayerSitePreparationSavedData.Entry expected = entry(prepared());
        PlayerSitePreparationSavedData data = new PlayerSitePreparationSavedData();
        data.put(expected);

        CompoundTag encoded = data.save(new CompoundTag());
        PlayerSitePreparationSavedData decoded = PlayerSitePreparationSavedData.load(encoded);

        assertThat(decoded.entry(PLAYER)).contains(expected);
        // A rounded timestamp is a moved freshness boundary, so the exact instant survives.
        assertThat(decoded.entry(PLAYER).orElseThrow().prepared().preparedAt())
                .isEqualTo(PREPARED_AT);
        assertThat(decoded.entry(PLAYER).orElseThrow().prepared().expiresAt())
                .isEqualTo(PREPARED_AT.plusSeconds(300));
    }

    @Test
    void restoresAnExpiredRecordWithoutRenewingIt() {
        PlayerSitePreparationSavedData data = new PlayerSitePreparationSavedData();
        data.put(entry(prepared()));

        var restored = PlayerSitePreparationSavedData
                .load(data.save(new CompoundTag())).entry(PLAYER).orElseThrow();

        assertThat(restored.prepared().expiresAt()).isBefore(Instant.now());
    }

    @Test
    void keepsTheSelectionWhenNoPostClearanceRescanHasMintedAPreparedSite() {
        PlayerSitePreparationSavedData data = new PlayerSitePreparationSavedData();
        data.put(entry(null));

        var restored = PlayerSitePreparationSavedData
                .load(data.save(new CompoundTag())).entry(PLAYER).orElseThrow();

        assertThat(restored.prepared()).isNull();
        assertThat(restored.selection().selectionHash()).isEqualTo("c".repeat(64));
    }

    @Test
    void dropsAMalformedRecordInsteadOfRestoringHalfOfIt() {
        PlayerSitePreparationSavedData data = new PlayerSitePreparationSavedData();
        data.put(entry(prepared()));
        CompoundTag encoded = data.save(new CompoundTag());
        encoded.getList("Sites", 10).getCompound(0)
                .getCompound("Prepared").putString("CleanSiteSnapshotHash", "not-a-hash");

        assertThat(PlayerSitePreparationSavedData.load(encoded).entry(PLAYER)).isEmpty();
    }

    @Test
    void refusesAPreparedSiteThatDoesNotBelongToItsSelection() {
        PreparedConstructionSite foreign = new PreparedConstructionSite(
                "prepared:" + "d".repeat(16), "world-identity",
                ResourceId.parse("minecraft:overworld"),
                new PlacementAnchor("world-identity", ResourceId.parse("minecraft:overworld"),
                        new BlockPos3i(99, 70, 99), "player-identity", PREPARED_AT,
                        AnchorSource.PLAYER_LOOK, "e".repeat(64)),
                SiteFacing.NORTH, "a".repeat(64), "b".repeat(64), "terrain-graph",
                new SalvageLedger("ledger", "destination", List.of()), List.of(), List.of(),
                PREPARED_AT, PREPARED_AT.plusSeconds(300));

        assertThatThrownBy(() -> entry(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prepared site does not match its selection");
    }

    @Test
    void releasingAFinishedProjectLeavesNothingForTheNextOneToRestore() {
        PlayerSitePreparationSavedData data = new PlayerSitePreparationSavedData();
        data.put(entry(prepared()));

        data.removeProject(PROJECT);

        assertThat(data.entry(PLAYER)).isEmpty();
    }

    private static PlayerSitePreparationSavedData.Entry entry(PreparedConstructionSite prepared) {
        return new PlayerSitePreparationSavedData.Entry(PLAYER, PROJECT,
                "player-workflow:" + PROJECT, selection(),
                new PlayerSitePreparationSavedData.SalvageBinding("world-identity",
                        ResourceId.parse("minecraft:overworld"), new BlockPos3i(14, 70, -6),
                        "f".repeat(64), "player-identity", "player-salvage:" + "1".repeat(32)),
                prepared, 1_787_236_010_000L);
    }

    private static ConfirmedSiteSelection selection() {
        return new ConfirmedSiteSelection(anchor(), SiteFacing.NORTH,
                new DeploymentBoundingBox(new BlockPos3i(8, 68, -12), new BlockPos3i(20, 76, 4)),
                "player-workflow:" + PROJECT, PREPARED_AT, PREPARED_AT.plusSeconds(3_600),
                "c".repeat(64));
    }

    private static PlacementAnchor anchor() {
        return new PlacementAnchor("world-identity", ResourceId.parse("minecraft:overworld"),
                new BlockPos3i(12, 70, -9), "player-identity", PREPARED_AT,
                AnchorSource.PLAYER_LOOK, "e".repeat(64));
    }

    private static PreparedConstructionSite prepared() {
        return new PreparedConstructionSite("prepared:" + "d".repeat(16), "world-identity",
                ResourceId.parse("minecraft:overworld"), anchor(), SiteFacing.NORTH,
                "a".repeat(64), "b".repeat(64), "terrain-graph",
                new SalvageLedger("ledger", "destination", List.of(new SalvageEntry(
                        "obstacle", ResourceId.parse("minecraft:oak_log"), 3, 3, 3, "bot"))),
                List.of(new ObstacleFinding("obstacle-1", new BlockPos3i(13, 70, -9),
                        ResourceId.parse("minecraft:bedrock"), "0".repeat(64), false, false,
                        false, false, false, -1.0D, "none", "none", false,
                        ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL, 100,
                        "forge1201:survey", "bedrock is never removed")),
                List.of(new TerrainMutationEvidence(new BlockPos3i(14, 70, -9), "2".repeat(64),
                        "3".repeat(64), "bot-executor", PREPARED_AT)),
                PREPARED_AT, PREPARED_AT.plusSeconds(300));
    }
}
