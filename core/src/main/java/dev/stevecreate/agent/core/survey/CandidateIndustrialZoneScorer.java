package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Pure deterministic scoring; ordering is not selection or approval. */
public final class CandidateIndustrialZoneScorer {
    private static final Comparator<BlockPos3i> POSITION_ORDER = Comparator.comparingInt(BlockPos3i::y)
            .thenComparingInt(BlockPos3i::z).thenComparingInt(BlockPos3i::x);

    public List<CandidateIndustrialZone> score(List<CandidateZoneObservation> input) {
        List<CandidateZoneObservation> observations = List.copyOf(input);
        if (observations.isEmpty() || observations.size() > 64) {
            throw new IllegalArgumentException("candidate observations must contain 1..64 entries");
        }
        List<CandidateIndustrialZone> zones = observations.stream().map(this::scoreOne).toList();
        if (zones.stream().map(CandidateIndustrialZone::candidateId).distinct().count() != zones.size()) {
            throw new IllegalArgumentException("candidate observations contain duplicate zone identities");
        }
        return zones.stream().sorted(Comparator.comparingInt(CandidateIndustrialZone::totalScore).reversed()
                .thenComparing(CandidateIndustrialZone::candidateId)).toList();
    }

    private CandidateIndustrialZone scoreOne(CandidateZoneObservation observation) {
        List<BlockPos3i> anchors = observation.anchorCandidates().stream().sorted(POSITION_ORDER).toList();
        List<InfrastructureFinding> infrastructure = observation.nearbyInfrastructure().stream()
                .sorted(Comparator.comparing((InfrastructureFinding finding) ->
                                finding.location().position(), POSITION_ORDER)
                        .thenComparing(finding -> finding.resourceId().toString()))
                .toList();
        List<SurveyLimitation> limitations = observation.protectedAndUnknownFindings().stream()
                .sorted(Comparator.comparing(SurveyLimitation::code)
                        .thenComparing(SurveyLimitation::scope)
                        .thenComparing(SurveyLimitation::message))
                .toList();
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("available_space", availableSpaceScore(observation.clearVolume()));
        scores.put("exploration", explorationScore(observation));
        scores.put("existing_infrastructure", infrastructureScore(observation));
        scores.put("block_entity_player_risk", riskScore(observation, limitations));
        scores.put("chunk_boundary", observation.chunkBoundaryCrossings() == 0 ? 10
                : observation.chunkBoundaryCrossings() <= 2 ? 5 : 0);
        scores.put("claim_permission", switch (observation.permissionStatus()) {
            case VERIFIED_ALLOWED -> 10;
            case UNKNOWN -> -25;
            case VERIFIED_DENIED -> -100;
        });
        scores.put("dry_run", observation.dryRunFeasible() ? 15 : -25);
        scores.put("coverage", observation.coverageComplete() ? 5 : -10);

        TreeSet<String> authorizations = new TreeSet<>();
        authorizations.add("REGION_AUTHORIZATION");
        authorizations.add("USER_SELECTION");
        if (observation.permissionStatus() != CandidatePermissionStatus.VERIFIED_ALLOWED) {
            authorizations.add("CLAIM_PERMISSION");
        }
        if (!observation.dryRunFeasible()) authorizations.add("DRY_RUN_VALIDATION");
        if (!observation.coverageComplete()) authorizations.add("COVERAGE_COMPLETION");
        if (limitations.stream().anyMatch(SurveyLimitation::blocksCandidateReadiness)) {
            authorizations.add("PROTECTED_OR_UNKNOWN_REVIEW");
        }

        SurveyConfidence confidence = confidence(observation, limitations);
        return new CandidateIndustrialZone(candidateId(observation, anchors), observation.dimension(),
                observation.boundingBox(), anchors, infrastructure, limitations, scores,
                scores.values().stream().mapToInt(Integer::intValue).sum(), confidence,
                List.copyOf(authorizations), observation.coverage(), CandidateZoneStatus.PENDING_USER_SELECTION);
    }

    private static int availableSpaceScore(long clearVolume) {
        if (clearVolume >= 32_768) return 30;
        if (clearVolume >= 8_192) return 20;
        if (clearVolume >= 2_048) return 10;
        return 0;
    }

    private static int explorationScore(CandidateZoneObservation observation) {
        int percent = observation.exploredChunks() * 100 / observation.totalChunks();
        int base = percent == 100 ? 15 : percent >= 75 ? 10 : percent >= 50 ? 5 : 0;
        return observation.coverage().budgetExhausted() ? base - 5 : base;
    }

    private static int infrastructureScore(CandidateZoneObservation observation) {
        long distance = observation.nearbyInfrastructure().stream()
                .map(InfrastructureFinding::location)
                .map(SurveyLocation::position)
                .mapToLong(position -> distanceToBox(position, observation.boundingBox()))
                .min().orElse(-1);
        if (distance < 0) return 0;
        if (distance <= 32) return 20;
        if (distance <= 128) return 15;
        if (distance <= 512) return 8;
        return 2;
    }

    private static long distanceToBox(
            BlockPos3i position, dev.stevecreate.agent.core.deployment.DeploymentBoundingBox box) {
        return axisDistance(position.x(), box.minimum().x(), box.maximum().x())
                + axisDistance(position.y(), box.minimum().y(), box.maximum().y())
                + axisDistance(position.z(), box.minimum().z(), box.maximum().z());
    }

    private static long axisDistance(int value, int minimum, int maximum) {
        if (value < minimum) return (long) minimum - value;
        if (value > maximum) return (long) value - maximum;
        return 0;
    }

    private static int riskScore(
            CandidateZoneObservation observation, List<SurveyLimitation> limitations) {
        int penalty = Math.min(20, observation.blockEntityCount() * 2)
                + Math.min(40, observation.playerBuiltRiskCount() * 10)
                + Math.min(40, (int) limitations.stream()
                        .filter(SurveyLimitation::blocksCandidateReadiness).count() * 10);
        return 10 - penalty;
    }

    private static SurveyConfidence confidence(
            CandidateZoneObservation observation, List<SurveyLimitation> limitations) {
        if (observation.permissionStatus() == CandidatePermissionStatus.VERIFIED_DENIED
                || limitations.stream().anyMatch(SurveyLimitation::blocksCandidateReadiness)) {
            return SurveyConfidence.UNKNOWN;
        }
        if (!observation.coverageComplete()
                || observation.permissionStatus() == CandidatePermissionStatus.UNKNOWN
                || !observation.dryRunFeasible()) {
            return SurveyConfidence.DERIVED_LOW_CONFIDENCE;
        }
        return SurveyConfidence.DERIVED_HIGH_CONFIDENCE;
    }

    private static String candidateId(CandidateZoneObservation observation, List<BlockPos3i> anchors) {
        StringBuilder canonical = new StringBuilder(observation.worldIdentity()).append('|')
                .append(observation.dimension()).append('|')
                .append(observation.boundingBox().minimum()).append('|')
                .append(observation.boundingBox().maximum());
        anchors.forEach(anchor -> canonical.append('|').append(anchor));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder("zone:");
            for (byte value : hash) encoded.append(String.format("%02x", value & 0xff));
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
