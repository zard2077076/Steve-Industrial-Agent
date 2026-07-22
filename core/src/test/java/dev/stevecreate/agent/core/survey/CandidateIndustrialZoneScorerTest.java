package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateIndustrialZoneScorerTest {
    private static final String HASH = "a".repeat(64);
    private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");

    @Test
    void deterministicallyScoresAllRequiredFactorsWithoutApprovingTopCandidate() {
        CandidateZoneObservation strong = observation(0, 40_000, 16, 16, 20, 0, 0, 0,
                CandidatePermissionStatus.VERIFIED_ALLOWED, true, true, List.of());
        CandidateZoneObservation weak = observation(128, 1_000, 4, 16, -1, 5, 2, 4,
                CandidatePermissionStatus.UNKNOWN, false, false,
                List.of(limitation("PLAYER_BUILDING_UNKNOWN", true)));

        List<CandidateIndustrialZone> zones = new CandidateIndustrialZoneScorer().score(List.of(weak, strong));

        assertThat(zones).hasSize(2).isSortedAccordingTo(
                java.util.Comparator.comparingInt(CandidateIndustrialZone::totalScore).reversed()
                        .thenComparing(CandidateIndustrialZone::candidateId));
        assertThat(zones).extracting(CandidateIndustrialZone::status)
                .containsOnly(CandidateZoneStatus.PENDING_USER_SELECTION);
        assertThat(zones.get(0).scoreBreakdown().keySet()).containsExactlyInAnyOrder(
                "available_space", "exploration", "existing_infrastructure", "block_entity_player_risk",
                "chunk_boundary", "claim_permission", "dry_run", "coverage");
        assertThat(zones.get(0).confidence()).isEqualTo(SurveyConfidence.DERIVED_HIGH_CONFIDENCE);
        assertThat(zones.get(1).confidence()).isEqualTo(SurveyConfidence.UNKNOWN);
    }

    @Test
    void unknownPermissionAndPartialCoverageRemainFailClosedAndExplicit() {
        CandidateIndustrialZone zone = new CandidateIndustrialZoneScorer().score(List.of(
                observation(0, 10_000, 8, 16, 64, 0, 0, 1, CandidatePermissionStatus.UNKNOWN,
                        true, false, List.of()))).get(0);

        assertThat(zone.requiredFutureAuthorization()).containsExactly(
                "CLAIM_PERMISSION", "COVERAGE_COMPLETION", "REGION_AUTHORIZATION", "USER_SELECTION");
        assertThat(zone.confidence()).isEqualTo(SurveyConfidence.DERIVED_LOW_CONFIDENCE);
        assertThat(zone.scoreBreakdown().get("claim_permission")).isEqualTo(-25);
        assertThat(zone.status()).isEqualTo(CandidateZoneStatus.PENDING_USER_SELECTION);
    }

    @Test
    void tiesAndInputOrderProduceCanonicalOrderingAndStableIds() {
        CandidateZoneObservation first = observation(0, 10_000, 16, 16, 64, 0, 0, 1,
                CandidatePermissionStatus.VERIFIED_ALLOWED, true, true, List.of());
        CandidateZoneObservation second = observation(128, 10_000, 16, 16, 64, 0, 0, 1,
                CandidatePermissionStatus.VERIFIED_ALLOWED, true, true, List.of());
        CandidateIndustrialZoneScorer scorer = new CandidateIndustrialZoneScorer();

        List<CandidateIndustrialZone> forward = scorer.score(List.of(first, second));
        List<CandidateIndustrialZone> reverse = scorer.score(List.of(second, first));

        assertThat(forward).isEqualTo(reverse);
        assertThat(forward).extracting(CandidateIndustrialZone::candidateId).isSorted();
        assertThat(forward).extracting(CandidateIndustrialZone::totalScore).containsOnly(forward.get(0).totalScore());
    }

    @Test
    void canonicalizesAnchorInfrastructureAndLimitationOrder() {
        CandidateZoneObservation source = observation(0, 10_000, 16, 16, 64, 0, 0, 1,
                CandidatePermissionStatus.VERIFIED_ALLOWED, true, true,
                List.of(limitation("UNKNOWN_SECOND", false), limitation("UNKNOWN_FIRST", false)));
        List<BlockPos3i> reversedAnchors = new ArrayList<>(source.anchorCandidates());
        java.util.Collections.reverse(reversedAnchors);
        CandidateZoneObservation reordered = new CandidateZoneObservation(source.worldIdentity(), source.dimension(),
                source.boundingBox(), reversedAnchors, source.nearbyInfrastructure(),
                List.of(source.protectedAndUnknownFindings().get(1), source.protectedAndUnknownFindings().get(0)),
                source.clearVolume(), source.exploredChunks(), source.totalChunks(),
                source.blockEntityCount(), source.playerBuiltRiskCount(),
                source.chunkBoundaryCrossings(), source.permissionStatus(), source.dryRunFeasible(),
                source.coverageComplete(), source.coverage());

        CandidateIndustrialZone zone = new CandidateIndustrialZoneScorer().score(List.of(reordered)).get(0);

        assertThat(zone.anchorCandidates()).isSortedAccordingTo(java.util.Comparator.comparingInt(BlockPos3i::y)
                .thenComparingInt(BlockPos3i::z).thenComparingInt(BlockPos3i::x));
        assertThat(zone.protectedAndUnknownFindings()).extracting(SurveyLimitation::code)
                .containsExactly("UNKNOWN_FIRST", "UNKNOWN_SECOND");
        assertThat(zone.candidateId()).isEqualTo(
                new CandidateIndustrialZoneScorer().score(List.of(source)).get(0).candidateId());
    }

    @Test
    void rejectsUnboundedDuplicateOrInconsistentObservations() {
        CandidateZoneObservation first = observation(0, 10_000, 16, 16, 64, 0, 0, 1,
                CandidatePermissionStatus.UNKNOWN, true, true, List.of());
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CandidateIndustrialZoneScorer().score(List.of(first, first)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CandidateIndustrialZoneScorer().score(java.util.Collections.nCopies(65, first)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CandidateZoneObservation(first.worldIdentity(), first.dimension(), first.boundingBox(),
                        first.anchorCandidates(), first.nearbyInfrastructure(), first.protectedAndUnknownFindings(),
                        first.boundingBox().volume() + 1, 1, 1, 0, 0, 0,
                        CandidatePermissionStatus.UNKNOWN, true, true, coverage(false)));
    }

    private static CandidateZoneObservation observation(
            int x, long clearVolume, int explored, int total, int distance, int blockEntities,
            int playerRisk, int crossings, CandidatePermissionStatus permission, boolean dryRun,
            boolean complete, List<SurveyLimitation> limitations) {
        DeploymentBoundingBox bounds = new DeploymentBoundingBox(
                new BlockPos3i(x, 48, 0), new BlockPos3i(x + 63, 95, 63));
        List<InfrastructureFinding> infrastructure = distance < 0 ? List.of()
                : List.of(powerFinding(new BlockPos3i(x - distance, 64, 0)));
        return new CandidateZoneObservation("world:" + HASH, OVERWORLD, bounds,
                List.of(new BlockPos3i(x + 16, 64, 16), new BlockPos3i(x + 32, 64, 32)),
                infrastructure, limitations, clearVolume, explored, total, blockEntities, playerRisk,
                crossings, permission, dryRun, complete, coverage(!complete));
    }

    private static InfrastructureFinding powerFinding(BlockPos3i position) {
        int chunkX = Math.floorDiv(position.x(), 16);
        int chunkZ = Math.floorDiv(position.z(), 16);
        SurveyLocation location = new SurveyLocation("world:" + HASH, OVERWORLD,
                Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32), chunkX, chunkZ,
                position, HASH, 1, SurveyEvidenceSource.PERSISTED_BLOCK_STATE, List.of());
        SurveyEvidence evidence = new SurveyEvidence(SurveyEvidenceSource.BLOCK_PALETTE,
                "region/r." + location.regionX() + "." + location.regionZ() + ".mca", HASH, 1,
                SurveyConfidence.OBSERVED, "resource=create:shaft", true);
        return new PowerFinding(location, ResourceId.parse("create:shaft"),
                SurveyFindingCategory.TRANSMISSION, SurveyConfidence.OBSERVED, false, false, List.of(evidence));
    }

    private static SurveyCoverage coverage(boolean exhausted) {
        return new SurveyCoverage(2, 2, 16, 16, 1_024, Duration.ofSeconds(1), exhausted);
    }

    private static SurveyLimitation limitation(String code, boolean blocks) {
        return new SurveyLimitation(code, "minecraft:overworld", "offline evidence requires review", true, blocks);
    }
}
