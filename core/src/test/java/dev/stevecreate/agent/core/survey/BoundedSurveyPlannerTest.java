package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedSurveyPlannerTest {
    private static final String HASH = "a".repeat(64);
    private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");
    private static final ResourceId NETHER = ResourceId.parse("minecraft:the_nether");

    @Test
    void budgetIsExplicitSingleThreadedAndReservationsAreAtomic() {
        SurveyBudget budget = new SurveyBudget(1, 2, 30, 100, 40, 1, 1, 1);
        SurveyBudgetTracker regions = new SurveyBudgetTracker(budget, () -> 0);
        assertThat(regions.tryReserve(new SurveyBudgetCharge(1, 0, 0, 0, 0, 0)).permitted()).isTrue();
        SurveyBudgetDecision regionFailure = regions.tryReserve(new SurveyBudgetCharge(1, 0, 0, 0, 0, 0));
        assertThat(regionFailure.permitted()).isFalse();
        assertThat(regionFailure.exhaustion().orElseThrow().dimension()).isEqualTo(SurveyBudgetDimension.REGIONS);
        assertThat(regionFailure.usage().regions()).isEqualTo(1);

        assertExhaustion(budget, new SurveyBudgetCharge(0, 3, 0, 0, 0, 0), SurveyBudgetDimension.CHUNKS);
        assertExhaustion(budget, new SurveyBudgetCharge(0, 0, 31, 0, 0, 0), SurveyBudgetDimension.READ_BYTES);
        assertExhaustion(budget, new SurveyBudgetCharge(0, 0, 0, 41, 0, 0), SurveyBudgetDimension.RETAINED_BYTES);
        assertExhaustion(budget, new SurveyBudgetCharge(0, 0, 0, 0, 2, 0), SurveyBudgetDimension.CANDIDATE_ZONES);
        assertExhaustion(budget, new SurveyBudgetCharge(0, 0, 0, 0, 0, 2), SurveyBudgetDimension.DEEP_SCAN_RADIUS);
        assertThat(budget.canonical()).contains("regions=1", "parallelism=1");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new SurveyBudget(1, 1, 1, 1, 1, 1, 0, 2));
        List<SurveyCandidateSeed> oversized = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            oversized.add(new SurveyCandidateSeed("zone-" + i, OVERWORLD, i, 0, 0, i));
        }
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                planner(new SurveyBudget(1, 1, 1, 1, 1, 1, 0, 1))
                        .scheduleCandidateDeepScan(List.of(), oversized));
    }

    @Test
    void durationAndWrongThreadFailClosedWithoutChangingCounters() throws InterruptedException {
        AtomicLong clock = new AtomicLong(10);
        SurveyBudget budget = new SurveyBudget(2, 2, 100, 5, 100, 2, 2, 1);
        SurveyBudgetTracker timed = new SurveyBudgetTracker(budget, clock::get);
        clock.set(16);
        SurveyBudgetDecision duration = timed.tryReserve(SurveyBudgetCharge.checkpoint());
        assertThat(duration.exhaustion().orElseThrow().dimension()).isEqualTo(SurveyBudgetDimension.DURATION);
        assertThat(duration.usage().regions()).isZero();

        SurveyBudgetTracker owned = new SurveyBudgetTracker(budget, () -> 10);
        AtomicReference<SurveyBudgetDecision> crossThread = new AtomicReference<>();
        Thread thread = new Thread(() -> crossThread.set(owned.tryReserve(
                new SurveyBudgetCharge(1, 0, 0, 0, 0, 0))), "survey-budget-test");
        thread.start();
        thread.join();
        assertThat(crossThread.get().exhaustion().orElseThrow().dimension())
                .isEqualTo(SurveyBudgetDimension.SINGLE_THREAD);
        assertThat(owned.usage().regions()).isZero();
    }

    @Test
    void metadataHeatExpansionAndCandidateDeepScanOrderIsDeterministic() {
        SurveyBudget budget = new SurveyBudget(8, 64, 10_000, 1_000, 10_000, 4, 2, 1);
        List<SurveyRegionMetadata> metadata = List.of(
                region(OVERWORLD, 0, 0, 100, 0, 10),
                region(OVERWORLD, 1, 0, 200, 0, 10),
                region(OVERWORLD, 2, 0, 200, 5, 10),
                region(NETHER, 5, 5, 50, 0, 10));
        SurveyCandidateSeed seed = new SurveyCandidateSeed("zone-nether", NETHER, 5, 5, 0, 20);

        BoundedSurveyPlan first = planComplete(budget, metadata, seed);
        BoundedSurveyPlan second = planComplete(budget, metadata, seed);

        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(SurveyStatus.COMPLETE);
        assertThat(first.work()).extracting(work -> work.metadata().regionX())
                .containsExactly(2, 1, 0, 5);
        assertThat(first.work()).extracting(SurveyRegionWork::phase)
                .containsExactly(SurveyRegionScanPhase.HOT_SAMPLE, SurveyRegionScanPhase.HOT_SAMPLE,
                        SurveyRegionScanPhase.NEIGHBOR_EXPANSION, SurveyRegionScanPhase.CANDIDATE_DEEP_SCAN);
        assertThat(first.work().stream().filter(work -> work.phase() == SurveyRegionScanPhase.CANDIDATE_DEEP_SCAN))
                .allSatisfy(work -> {
                    assertThat(work.candidateId()).contains("zone-nether");
                    assertThat(work.metadata().dimension()).isEqualTo(NETHER);
                });
        assertThat(first.usage().candidateZones()).isEqualTo(1);
        assertThat(first.usage().maximumDeepScanRadiusObserved()).isZero();
    }

    @Test
    void firstExceededRegionReturnsTypedPartialAndStopsAdditionalScheduling() {
        SurveyBudget budget = new SurveyBudget(2, 20, 10_000, 1_000, 10_000, 2, 2, 1);
        List<SurveyRegionMetadata> metadata = List.of(
                region(OVERWORLD, 0, 0, 300, 0, 5),
                region(OVERWORLD, 1, 0, 200, 0, 5),
                region(OVERWORLD, 2, 0, 100, 0, 5));
        BoundedSurveyPlanner planner = planner(budget);
        planner.scheduleHotSample(metadata, 2);
        planner.scheduleCandidateDeepScan(metadata,
                List.of(new SurveyCandidateSeed("zone", OVERWORLD, 2, 0, 0, 10)));
        BoundedSurveyPlan partial = planner.snapshot();

        assertThat(partial.status()).isEqualTo(SurveyStatus.SURVEY_PARTIAL);
        assertThat(partial.work()).hasSize(2);
        assertThat(partial.usage().regions()).isEqualTo(2);
        assertThat(partial.failures()).extracting(FormalSurveyFailure::code)
                .containsExactly(FormalSurveyFailureCode.SURVEY_BUDGET_EXHAUSTED,
                        FormalSurveyFailureCode.SURVEY_PARTIAL);
        assertThat(partial.failures()).allSatisfy(failure -> {
            assertThat(failure.worldIdentity()).isEqualTo("world:" + HASH);
            assertThat(failure.fingerprint()).isEqualTo(HASH);
            assertThat(failure.budget()).isEqualTo(budget.canonical());
            assertThat(failure.safeNextStep()).isNotBlank();
        });
        assertThat(partial.limitations()).extracting(SurveyLimitation::code)
                .containsExactly("SURVEY_BUDGET_EXHAUSTED");

        planner.scheduleHotSample(metadata, 1);
        assertThat(planner.snapshot().work()).hasSize(2);
    }

    @Test
    void candidateCountRadiusBytesChunksAndMemoryCannotBeSilentlyClamped() {
        SurveyBudget radiusBudget = new SurveyBudget(4, 10, 1_000, 1_000, 1_000, 1, 1, 1);
        BoundedSurveyPlanner radiusPlanner = planner(radiusBudget);
        radiusPlanner.scheduleCandidateDeepScan(List.of(region(OVERWORLD, 0, 0, 1, 0, 1)),
                List.of(new SurveyCandidateSeed("wide", OVERWORLD, 0, 0, 2, 1)));
        BoundedSurveyPlan radiusPartial = radiusPlanner.snapshot();
        assertThat(radiusPartial.status()).isEqualTo(SurveyStatus.SURVEY_PARTIAL);
        assertThat(radiusPartial.usage().candidateZones()).isZero();
        assertThat(radiusPartial.work()).isEmpty();
        assertThat(radiusPartial.failures().get(0).evidence()).contains("dimension=DEEP_SCAN_RADIUS");

        List<SurveyRegionMetadata> expensive = List.of(
                new SurveyRegionMetadata(OVERWORLD, 0, 0, "region/r.0.0.mca", 60, 5, 80, 1, 1, 0));
        SurveyBudget resourceBudget = new SurveyBudget(4, 4, 50, 1_000, 70, 1, 1, 1);
        BoundedSurveyPlanner resourcePlanner = planner(resourceBudget);
        resourcePlanner.scheduleHotSample(expensive, 1);
        BoundedSurveyPlan resourcePartial = resourcePlanner.snapshot();
        assertThat(resourcePartial.status()).isEqualTo(SurveyStatus.SURVEY_PARTIAL);
        assertThat(resourcePartial.usage()).isEqualTo(new SurveyBudgetUsage(0, 0, 0, 0, 0, 0, 0));
        assertThat(resourcePartial.failures().get(0).evidence()).contains("dimension=CHUNKS");
    }

    @Test
    void largeInputStopsAtTheBoundAndNeverSchedulesDuplicates() {
        List<SurveyRegionMetadata> metadata = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) metadata.add(region(OVERWORLD, i, 0, 1_000 - i, 0, 1));
        SurveyBudget budget = new SurveyBudget(3, 3, 300, 1_000, 300, 1, 1, 1);
        BoundedSurveyPlanner planner = planner(budget);
        planner.scheduleHotSample(metadata, 3);
        planner.scheduleNeighborExpansion(metadata, List.of(metadata.get(0)), 1);
        planner.scheduleCandidateDeepScan(metadata,
                List.of(new SurveyCandidateSeed("zone", OVERWORLD, 0, 0, 1, 1)));
        BoundedSurveyPlan result = planner.snapshot();

        assertThat(result.work()).hasSize(3);
        assertThat(result.work()).extracting(work -> work.metadata().key()).doesNotHaveDuplicates();
        assertThat(result.usage().regions()).isLessThanOrEqualTo(budget.maximumRegions());
        assertThat(result.usage().chunks()).isLessThanOrEqualTo(budget.maximumChunks());
        assertThat(result.usage().readBytes()).isLessThanOrEqualTo(budget.maximumReadBytes());
    }

    private static BoundedSurveyPlan planComplete(
            SurveyBudget budget,
            List<SurveyRegionMetadata> metadata,
            SurveyCandidateSeed seed) {
        BoundedSurveyPlanner planner = planner(budget);
        planner.scheduleHotSample(metadata, 2);
        planner.scheduleNeighborExpansion(metadata,
                List.of(metadata.stream().filter(region -> region.regionX() == 1).findFirst().orElseThrow()), 1);
        planner.scheduleCandidateDeepScan(metadata, List.of(seed));
        return planner.snapshot();
    }

    private static BoundedSurveyPlanner planner(SurveyBudget budget) {
        return new BoundedSurveyPlanner(new SurveyPlanContext(
                "world:" + HASH, "C:/formal/save", HASH), budget, () -> 0);
    }

    private static SurveyRegionMetadata region(
            ResourceId dimension,
            int x,
            int z,
            long activity,
            int hints,
            int chunks) {
        return new SurveyRegionMetadata(dimension, x, z,
                "region/r." + x + "." + z + ".mca", 100, chunks, 100,
                activity, activity, hints);
    }

    private static void assertExhaustion(
            SurveyBudget budget,
            SurveyBudgetCharge charge,
            SurveyBudgetDimension dimension) {
        SurveyBudgetDecision decision = new SurveyBudgetTracker(budget, () -> 0).tryReserve(charge);
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.exhaustion().orElseThrow().dimension()).isEqualTo(dimension);
        assertThat(decision.usage()).isEqualTo(new SurveyBudgetUsage(0, 0, 0, 0, 0, 0, 0));
    }
}
