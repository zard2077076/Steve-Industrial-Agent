package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Single-thread deterministic scheduler for hot samples, bounded neighbor expansion and
 * candidate-only deep scans. It never opens a path or parses a chunk.
 */
public final class BoundedSurveyPlanner {
    private static final Comparator<SurveyRegionMetadata> HEAT_ORDER =
            Comparator.comparingLong(SurveyRegionMetadata::activityTimestamp).reversed()
                    .thenComparing(Comparator.comparingInt(SurveyRegionMetadata::infrastructureHintCount).reversed())
                    .thenComparing(Comparator.comparingLong(SurveyRegionMetadata::lastModifiedMillis).reversed())
                    .thenComparing(Comparator.comparingInt(SurveyRegionMetadata::populatedChunks).reversed())
                    .thenComparing(value -> value.dimension().toString())
                    .thenComparingInt(SurveyRegionMetadata::regionX)
                    .thenComparingInt(SurveyRegionMetadata::regionZ)
                    .thenComparing(SurveyRegionMetadata::relativePath);
    private static final Comparator<SurveyCandidateSeed> CANDIDATE_ORDER =
            Comparator.comparingInt(SurveyCandidateSeed::evidenceScore).reversed()
                    .thenComparing(SurveyCandidateSeed::candidateId)
                    .thenComparing(value -> value.dimension().toString())
                    .thenComparingInt(SurveyCandidateSeed::regionX)
                    .thenComparingInt(SurveyCandidateSeed::regionZ);

    private final SurveyPlanContext context;
    private final SurveyBudgetTracker tracker;
    private final List<SurveyRegionWork> work = new ArrayList<>();
    private final Set<String> scheduledRegions = new HashSet<>();
    private SurveyBudgetExhaustion exhaustion;
    private FormalSurveyStage exhaustionStage;
    private String exhaustionDimension = "not-applicable";
    private String exhaustionRegion = "not-applicable";

    public BoundedSurveyPlanner(
            SurveyPlanContext context,
            SurveyBudget budget,
            LongSupplier monotonicMillis) {
        this.context = Objects.requireNonNull(context, "context");
        this.tracker = new SurveyBudgetTracker(budget, monotonicMillis);
    }

    public void scheduleHotSample(List<SurveyRegionMetadata> metadata, int maximumSampleRegions) {
        ensureActive();
        if (maximumSampleRegions < 1 || maximumSampleRegions > tracker.budget().maximumRegions()) {
            throw new IllegalArgumentException("maximumSampleRegions is outside the survey budget");
        }
        validateMetadata(metadata);
        metadata.stream().sorted(HEAT_ORDER).limit(maximumSampleRegions)
                .forEach(region -> schedule(region, SurveyRegionScanPhase.HOT_SAMPLE, Optional.empty(), 0));
    }

    public void scheduleNeighborExpansion(
            List<SurveyRegionMetadata> metadata,
            List<SurveyRegionMetadata> anchors,
            int maximumDistance) {
        ensureActive();
        if (maximumDistance < 1 || maximumDistance > tracker.budget().maximumDeepScanRadius()) {
            throw new IllegalArgumentException("neighbor expansion distance is outside the survey budget");
        }
        validateMetadata(metadata);
        validateMetadata(anchors);
        List<Expansion> expansion = new ArrayList<>();
        for (SurveyRegionMetadata region : metadata) {
            int distance = anchors.stream()
                    .filter(anchor -> anchor.dimension().equals(region.dimension()))
                    .mapToInt(anchor -> chebyshev(anchor.regionX(), anchor.regionZ(), region.regionX(), region.regionZ()))
                    .min().orElse(Integer.MAX_VALUE);
            if (distance >= 1 && distance <= maximumDistance) expansion.add(new Expansion(region, distance));
        }
        expansion.stream().sorted(Comparator.comparingInt(Expansion::distance)
                        .thenComparing(Expansion::metadata, HEAT_ORDER))
                .forEach(item -> schedule(item.metadata(), SurveyRegionScanPhase.NEIGHBOR_EXPANSION,
                        Optional.empty(), item.distance()));
    }

    public void scheduleCandidateDeepScan(
            List<SurveyRegionMetadata> metadata,
            List<SurveyCandidateSeed> candidates) {
        ensureActive();
        validateMetadata(metadata);
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() > 256) throw new IllegalArgumentException("candidate input exceeds the hard bound");
        List<SurveyCandidateSeed> ordered = List.copyOf(candidates).stream().sorted(CANDIDATE_ORDER).toList();
        if (ordered.stream().map(SurveyCandidateSeed::candidateId).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("candidate IDs are not unique");
        }
        for (SurveyCandidateSeed candidate : ordered) {
            if (exhaustion != null) break;
            SurveyBudgetDecision candidateDecision = tracker.tryReserve(new SurveyBudgetCharge(
                    0, 0, 0, 0, 1, candidate.requestedDeepScanRadius()));
            if (!candidateDecision.permitted()) {
                recordExhaustion(candidateDecision, FormalSurveyStage.CANDIDATE_SELECTION,
                        candidate.dimension(), candidate.regionX(), candidate.regionZ());
                break;
            }
            metadata.stream()
                    .filter(region -> region.dimension().equals(candidate.dimension()))
                    .map(region -> new Expansion(region, chebyshev(candidate.regionX(), candidate.regionZ(),
                            region.regionX(), region.regionZ())))
                    .filter(item -> item.distance() <= candidate.requestedDeepScanRadius())
                    .sorted(Comparator.comparingInt(Expansion::distance)
                            .thenComparing(Expansion::metadata, HEAT_ORDER))
                    .forEach(item -> schedule(item.metadata(), SurveyRegionScanPhase.CANDIDATE_DEEP_SCAN,
                            Optional.of(candidate.candidateId()), item.distance()));
        }
    }

    public BoundedSurveyPlan snapshot() {
        SurveyBudgetDecision checkpoint = null;
        if (exhaustion == null) {
            checkpoint = tracker.tryReserve(SurveyBudgetCharge.checkpoint());
            if (!checkpoint.permitted()) {
                recordExhaustion(checkpoint, FormalSurveyStage.REGION_METADATA, null, 0, 0);
            }
        }
        SurveyBudgetUsage usage = checkpoint != null ? checkpoint.usage() : tracker.usage();
        if (exhaustion == null) {
            return new BoundedSurveyPlan(SurveyStatus.COMPLETE, tracker.budget(), usage,
                    work, List.of(), List.of());
        }
        List<String> evidence = List.of(
                "dimension=" + exhaustion.dimension(),
                "current=" + exhaustion.current(),
                "requested=" + exhaustion.requested(),
                "maximum=" + exhaustion.maximum(),
                "usage=" + usage.canonical());
        FormalSurveyFailure exhausted = failure(FormalSurveyFailureCode.SURVEY_BUDGET_EXHAUSTED,
                evidence, exhaustion.reason(), "Review the partial report and explicitly increase one bounded limit.");
        FormalSurveyFailure partial = failure(FormalSurveyFailureCode.SURVEY_PARTIAL,
                evidence, "survey stopped at the first exhausted budget dimension",
                "Use only recorded coverage; unscanned regions and chunks remain unknown.");
        SurveyLimitation limitation = new SurveyLimitation("SURVEY_BUDGET_EXHAUSTED",
                exhaustionDimension + "/" + exhaustionRegion,
                exhaustion.reason(), false, true);
        return new BoundedSurveyPlan(SurveyStatus.SURVEY_PARTIAL, tracker.budget(), usage,
                work, List.of(exhausted, partial), List.of(limitation));
    }

    private void schedule(
            SurveyRegionMetadata metadata,
            SurveyRegionScanPhase phase,
            Optional<String> candidateId,
            int distance) {
        if (exhaustion != null || scheduledRegions.contains(metadata.key())) return;
        SurveyBudgetDecision decision = tracker.tryReserve(new SurveyBudgetCharge(
                1, metadata.populatedChunks(), metadata.fileBytes(), metadata.retainedBytesEstimate(), 0, 0));
        if (!decision.permitted()) {
            recordExhaustion(decision, FormalSurveyStage.REGION_METADATA,
                    metadata.dimension(), metadata.regionX(), metadata.regionZ());
            return;
        }
        scheduledRegions.add(metadata.key());
        work.add(new SurveyRegionWork(metadata, phase, candidateId, distance));
    }

    private void recordExhaustion(
            SurveyBudgetDecision decision,
            FormalSurveyStage stage,
            ResourceId dimension,
            int regionX,
            int regionZ) {
        if (exhaustion != null) return;
        exhaustion = decision.exhaustion().orElseThrow();
        exhaustionStage = stage;
        if (dimension != null) {
            exhaustionDimension = dimension.toString();
            exhaustionRegion = "r." + regionX + "." + regionZ;
        }
    }

    private FormalSurveyFailure failure(
            FormalSurveyFailureCode code,
            List<String> evidence,
            String reason,
            String safeNextStep) {
        return new FormalSurveyFailure(code, exhaustionStage, context.worldIdentity(), context.canonicalPath(),
                exhaustionDimension, exhaustionRegion, "not-applicable", context.fingerprint(),
                tracker.budget().canonical(), evidence, reason, safeNextStep);
    }

    private void ensureActive() {
        if (exhaustion != null) return;
        SurveyBudgetDecision checkpoint = tracker.tryReserve(SurveyBudgetCharge.checkpoint());
        if (!checkpoint.permitted()) {
            recordExhaustion(checkpoint, FormalSurveyStage.REGION_METADATA, null, 0, 0);
        }
    }

    private static void validateMetadata(List<SurveyRegionMetadata> metadata) {
        List<SurveyRegionMetadata> copy = List.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (copy.size() > 16_384 || copy.stream().map(SurveyRegionMetadata::key).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("region metadata is too large or contains duplicate coordinates");
        }
    }

    private static int chebyshev(int x1, int z1, int x2, int z2) {
        long dx = Math.abs((long) x1 - x2);
        long dz = Math.abs((long) z1 - z2);
        long distance = Math.max(dx, dz);
        return distance > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) distance;
    }

    private record Expansion(SurveyRegionMetadata metadata, int distance) {}
}
