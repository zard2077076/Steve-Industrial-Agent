package dev.stevecreate.agent.core.siteprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TerrainGradingSpecificationTest {
    private static final String AIR = "1".repeat(64);
    private static final String DIRT = "2".repeat(64);
    private static final String STONE = "3".repeat(64);

    @Test
    void normalizesTwoCornersAndBindsExactCutFillAndSurface() {
        BlockPos3i high = new BlockPos3i(11, 65, 20);
        BlockPos3i replace = new BlockPos3i(10, 64, 20);
        BlockPos3i hole = new BlockPos3i(11, 64, 20);
        List<BlockPos3i> expected = List.of(
                new BlockPos3i(10, 64, 20), new BlockPos3i(11, 64, 20));
        Map<BlockPos3i, String> before = new LinkedHashMap<>();
        before.put(high, STONE);
        before.put(replace, DIRT);
        before.put(hole, AIR);
        TerrainGradingSpecification grading = TerrainGradingSpecification.create(
                11, 20, 10, 20, 64, 8, 16,
                ResourceId.parse("minecraft:stone"), STONE,
                List.of(replace, high), List.of(hole, replace), expected, before);

        assertThat(grading.minimumX()).isEqualTo(10);
        assertThat(grading.maximumX()).isEqualTo(11);
        assertThat(grading.minimumZ()).isEqualTo(20);
        assertThat(grading.mutationCount()).isEqualTo(4);
        assertThat(grading.expectedFillPositions()).containsExactlyElementsOf(expected);
        assertThat(grading.specificationHash()).hasSize(64);
    }

    @Test
    void refusesMissingSurfaceOutsideAuthorityDuplicateAndUnboundMutations() {
        BlockPos3i surface = new BlockPos3i(0, 64, 0);
        assertThatIllegalArgumentException().isThrownBy(() ->
                TerrainGradingSpecification.create(0, 0, 1, 0, 64, 8, 16,
                        ResourceId.parse("minecraft:stone"), STONE,
                        List.of(), List.of(surface), List.of(surface), Map.of(surface, AIR)))
                .withMessageContaining("every grading column");
        BlockPos3i outside = new BlockPos3i(2, 64, 0);
        assertThatIllegalArgumentException().isThrownBy(() ->
                TerrainGradingSpecification.create(0, 0, 0, 0, 64, 8, 16,
                        ResourceId.parse("minecraft:stone"), STONE,
                        List.of(), List.of(outside), List.of(surface, outside),
                        Map.of(outside, AIR)))
                .withMessageContaining("outside authority");
        assertThatIllegalArgumentException().isThrownBy(() ->
                TerrainGradingSpecification.create(0, 0, 0, 0, 64, 8, 16,
                        ResourceId.parse("minecraft:stone"), STONE,
                        List.of(), List.of(surface, surface), List.of(surface),
                        Map.of(surface, AIR)))
                .withMessageContaining("duplicates");
        assertThatIllegalArgumentException().isThrownBy(() ->
                TerrainGradingSpecification.create(0, 0, 0, 0, 64, 8, 16,
                        ResourceId.parse("minecraft:stone"), STONE,
                        List.of(), List.of(surface), List.of(surface), Map.of()))
                .withMessageContaining("bind every mutation");
    }

    @Test
    void rejectsUnboundedPoliciesButKeepsConservativeContractStable() {
        assertThat(GroundLevelingPolicy.conservative())
                .isEqualTo(new GroundLevelingPolicy(2, 2, 64, 128));
        assertThat(GroundLevelingPolicy.boundedGrading(8, 16, 256, 512)
                .maximumTotalMutations()).isEqualTo(512);
        assertThatIllegalArgumentException().isThrownBy(() ->
                GroundLevelingPolicy.boundedGrading(33, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                GroundLevelingPolicy.boundedGrading(1, 1, 2, 1));
    }

    @Test
    void forcedRemovalRequiresExactRiskSnapshotPlayerAndWarningHash() {
        BlockPos3i position = new BlockPos3i(0, 64, 0);
        ForcedTerrainRemoval removal = new ForcedTerrainRemoval(position,
                ResourceId.parse("minecraft:chest"), DIRT, true, true,
                false, false, "Permanent container data loss");
        Instant now = Instant.parse("2026-07-28T07:00:00Z");
        ForcedTerrainRemovalAuthorization authorization =
                ForcedTerrainRemovalAuthorization.confirm(STONE, DIRT, "player:test",
                        now, now.plusSeconds(120), List.of(removal));
        assertThat(authorization.removals()).containsExactly(removal);
        assertThat(authorization.warningHash()).hasSize(64);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ForcedTerrainRemoval(position, ResourceId.parse("minecraft:stone"),
                        STONE, false, false, false, false, "no risk"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ForcedTerrainRemovalAuthorization(authorization.authorizationIdentity(),
                        AIR, authorization.gradingSpecificationHash(),
                        authorization.siteSnapshotHash(), authorization.playerIdentity(),
                        authorization.confirmedAt(), authorization.expiresAt(),
                        authorization.removals()))
                .withMessageContaining("warning hash mismatch");
    }
}
