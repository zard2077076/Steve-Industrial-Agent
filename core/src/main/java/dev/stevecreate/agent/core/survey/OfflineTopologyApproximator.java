package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.resource.GenericResourceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic adjacency approximation; never a runtime connectivity claim. */
public final class OfflineTopologyApproximator {
    private static final Comparator<InfrastructureFinding> NODE_ORDER =
            Comparator.comparing((InfrastructureFinding finding) -> finding.location().dimension().toString())
                    .thenComparingInt(finding -> finding.location().position().y())
                    .thenComparingInt(finding -> finding.location().position().z())
                    .thenComparingInt(finding -> finding.location().position().x())
                    .thenComparing(finding -> finding.resourceId().toString());

    public OfflineTopologyGraph approximate(
            List<InfrastructureFinding> input,
            boolean coverageComplete,
            List<SurveyLimitation> unscannedBoundaries) {
        List<InfrastructureFinding> nodes = List.copyOf(Objects.requireNonNull(input, "input"))
                .stream().sorted(NODE_ORDER).toList();
        if (nodes.size() > 4_096 || nodes.stream().map(InfrastructureFinding::location).distinct().count() != nodes.size()) {
            throw new IllegalArgumentException("topology input is too large or has duplicate positions");
        }
        List<OfflineTopologyEdge> edges = new ArrayList<>();
        for (int left = 0; left < nodes.size(); left++) {
            for (int right = left + 1; right < nodes.size(); right++) {
                InfrastructureFinding first = nodes.get(left);
                InfrastructureFinding second = nodes.get(right);
                if (!first.location().dimension().equals(second.location().dimension())
                        || manhattan(first.location().position(), second.location().position()) != 1) continue;
                resource(first, second).ifPresent(type -> edges.add(edge(first, second, type)));
            }
        }
        edges.sort(Comparator.comparing((OfflineTopologyEdge edge) -> key(edge.first()))
                .thenComparing(edge -> key(edge.second()))
                .thenComparing(edge -> edge.resourceType().name()));
        return new OfflineTopologyGraph(nodes, edges, coverageComplete, unscannedBoundaries);
    }

    private static Optional<GenericResourceType> resource(
            InfrastructureFinding first, InfrastructureFinding second) {
        if (first instanceof PowerFinding || second instanceof PowerFinding) {
            InfrastructureFinding other = first instanceof PowerFinding ? second : first;
            if (other instanceof PowerFinding || other.category() == SurveyFindingCategory.PROCESSING) {
                return Optional.of(GenericResourceType.ROTATIONAL_POWER);
            }
        }
        if (itemNode(first) && itemTarget(second) || itemNode(second) && itemTarget(first)) {
            return Optional.of(GenericResourceType.ITEM);
        }
        if (fluidNode(first) && fluidTarget(second) || fluidNode(second) && fluidTarget(first)) {
            return Optional.of(GenericResourceType.FLUID);
        }
        return Optional.empty();
    }

    private static boolean itemNode(InfrastructureFinding finding) {
        return finding instanceof LogisticsFinding logistics
                && logistics.transportType() == GenericResourceType.ITEM;
    }

    private static boolean fluidNode(InfrastructureFinding finding) {
        return finding instanceof LogisticsFinding logistics
                && logistics.transportType() == GenericResourceType.FLUID;
    }

    private static boolean itemTarget(InfrastructureFinding finding) {
        return itemNode(finding) || finding instanceof StorageFinding
                || finding.category() == SurveyFindingCategory.PROCESSING;
    }

    private static boolean fluidTarget(InfrastructureFinding finding) {
        return fluidNode(finding)
                || finding instanceof StorageFinding && finding.resourceId().path().contains("tank")
                || finding.category() == SurveyFindingCategory.PROCESSING;
    }

    private static OfflineTopologyEdge edge(
            InfrastructureFinding first,
            InfrastructureFinding second,
            GenericResourceType type) {
        boolean likeToLike = type == GenericResourceType.ROTATIONAL_POWER
                ? first instanceof PowerFinding && second instanceof PowerFinding
                : type == GenericResourceType.ITEM ? itemNode(first) && itemNode(second)
                : fluidNode(first) && fluidNode(second);
        Optional<SurveyEvidence> orientationBasis = compatibleOrientationEvidence(first, second);
        boolean orientation = orientationBasis.isPresent();
        SurveyConfidence confidence = likeToLike && orientation
                ? SurveyConfidence.DERIVED_HIGH_CONFIDENCE : SurveyConfidence.DERIVED_LOW_CONFIDENCE;
        SurveyEvidence firstBasis = first.evidence().get(0);
        SurveyEvidence secondBasis = second.evidence().get(0);
        List<SurveyEvidence> evidence = new ArrayList<>();
        evidence.add(adjacencyEvidence(firstBasis, first, second, type, confidence));
        if (!samePersistedSource(firstBasis, secondBasis)) {
            evidence.add(adjacencyEvidence(secondBasis, first, second, type, confidence));
        }
        if (orientation) {
            SurveyEvidence basis = orientationBasis.orElseThrow();
            evidence.add(new SurveyEvidence(SurveyEvidenceSource.ORIENTATION_PROPERTY, basis.relativeSourcePath(),
                    basis.fileFingerprint(), basis.parseGeneration(), confidence,
                    "persisted orientation is compatible with the adjacent axis", true));
        }
        return new OfflineTopologyEdge(first.location(), second.location(), type, confidence, false, evidence);
    }

    private static SurveyEvidence adjacencyEvidence(
            SurveyEvidence basis,
            InfrastructureFinding first,
            InfrastructureFinding second,
            GenericResourceType type,
            SurveyConfidence confidence) {
        return new SurveyEvidence(SurveyEvidenceSource.ADJACENCY, basis.relativeSourcePath(),
                basis.fileFingerprint(), basis.parseGeneration(), confidence,
                "adjacent=" + key(first.location()) + "<->" + key(second.location()) + ",resource=" + type, true);
    }

    private static boolean samePersistedSource(SurveyEvidence first, SurveyEvidence second) {
        return first.relativeSourcePath().equals(second.relativeSourcePath())
                && first.fileFingerprint().equals(second.fileFingerprint())
                && first.parseGeneration() == second.parseGeneration();
    }

    private static Optional<SurveyEvidence> compatibleOrientationEvidence(
            InfrastructureFinding first,
            InfrastructureFinding second) {
        BlockPos3i left = first.location().position();
        BlockPos3i right = second.location().position();
        return List.of(first, second).stream()
                .flatMap(finding -> finding.evidence().stream())
                .filter(evidence -> evidence.source() == SurveyEvidenceSource.ORIENTATION_PROPERTY)
                .filter(evidence -> axis(evidence).filter(value ->
                        value.equals("x") && left.x() != right.x()
                                || value.equals("y") && left.y() != right.y()
                                || value.equals("z") && left.z() != right.z()).isPresent())
                .findFirst();
    }

    private static Optional<String> axis(SurveyEvidence evidence) {
        return List.of(evidence.detail().split(",")).stream()
                .filter(value -> value.startsWith("axis="))
                .map(value -> value.substring("axis=".length()))
                .findFirst();
    }

    private static long manhattan(BlockPos3i first, BlockPos3i second) {
        return Math.abs((long) first.x() - second.x())
                + Math.abs((long) first.y() - second.y())
                + Math.abs((long) first.z() - second.z());
    }

    private static String key(SurveyLocation location) {
        BlockPos3i position = location.position();
        return location.dimension() + ":" + position.x() + "," + position.y() + "," + position.z();
    }
}
