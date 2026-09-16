package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.AnchorSource;
import dev.stevecreate.agent.core.siteprep.ConfirmedSiteSelection;
import dev.stevecreate.agent.core.siteprep.PlacementAnchor;
import dev.stevecreate.agent.core.siteprep.SiteFacing;
import dev.stevecreate.agent.forge1201.player.PlayerSitePreparationSavedData;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The decision that lets a restarted server hand a project back its own site evidence. */
class SitePreparationRestoreTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID OTHER_PROJECT =
            UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void restoresEvidenceForTheProjectThePlayerIsActuallyOn() {
        assertThat(SitePreparationCommand.restorable(entry(OVERWORLD), PROJECT, OVERWORLD))
                .isTrue();
    }

    @Test
    void neverRestoresEvidenceThatOutlivedItsProject() {
        assertThat(SitePreparationCommand.restorable(entry(OVERWORLD), OTHER_PROJECT, OVERWORLD))
                .isFalse();
        assertThat(SitePreparationCommand.restorable(entry(OVERWORLD), null, OVERWORLD))
                .isFalse();
        assertThat(SitePreparationCommand.restorable(null, PROJECT, OVERWORLD)).isFalse();
    }

    @Test
    void neverAnswersForADimensionThePlayerIsNotStandingIn() {
        assertThat(SitePreparationCommand.restorable(
                entry(OVERWORLD), PROJECT, "minecraft:the_nether")).isFalse();
        assertThat(SitePreparationCommand.restorable(entry(OVERWORLD), PROJECT, null)).isFalse();
    }

    private static PlayerSitePreparationSavedData.Entry entry(String dimension) {
        ResourceId resolved = ResourceId.parse(dimension);
        PlacementAnchor anchor = new PlacementAnchor("world-identity", resolved,
                new BlockPos3i(12, 70, -9), "player-identity", Instant.EPOCH.plusSeconds(10),
                AnchorSource.PLAYER_LOOK, "e".repeat(64));
        return new PlayerSitePreparationSavedData.Entry(
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"), PROJECT,
                "player-workflow:" + PROJECT,
                new ConfirmedSiteSelection(anchor, SiteFacing.NORTH,
                        new DeploymentBoundingBox(new BlockPos3i(8, 68, -12),
                                new BlockPos3i(20, 76, 4)),
                        "player-workflow:" + PROJECT, Instant.EPOCH.plusSeconds(10),
                        Instant.EPOCH.plusSeconds(3_610), "c".repeat(64)),
                new PlayerSitePreparationSavedData.SalvageBinding("world-identity", resolved,
                        new BlockPos3i(14, 70, -6), "f".repeat(64), "player-identity",
                        "player-salvage:" + "1".repeat(32)),
                null, 10_000L);
    }
}
