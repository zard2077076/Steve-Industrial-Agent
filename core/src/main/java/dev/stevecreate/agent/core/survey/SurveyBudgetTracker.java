package dev.stevecreate.agent.core.survey;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Mutable only within one owning thread; output snapshots are immutable. */
public final class SurveyBudgetTracker {
    private final SurveyBudget budget;
    private final LongSupplier monotonicMillis;
    private final long ownerThreadId;
    private final long startedAt;
    private int regions;
    private int chunks;
    private long readBytes;
    private long retainedBytes;
    private int candidateZones;
    private int maximumDeepScanRadiusObserved;

    public SurveyBudgetTracker(SurveyBudget budget, LongSupplier monotonicMillis) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.monotonicMillis = Objects.requireNonNull(monotonicMillis, "monotonicMillis");
        this.ownerThreadId = Thread.currentThread().getId();
        this.startedAt = monotonicMillis.getAsLong();
        if (startedAt < 0) throw new IllegalArgumentException("monotonic clock is negative");
    }

    public SurveyBudget budget() {
        return budget;
    }

    public SurveyBudgetDecision tryReserve(SurveyBudgetCharge charge) {
        Objects.requireNonNull(charge, "charge");
        long now = monotonicMillis.getAsLong();
        long elapsed = elapsed(now);
        SurveyBudgetExhaustion exhaustion = firstExhaustion(charge, elapsed);
        if (exhaustion != null) {
            return new SurveyBudgetDecision(false, usage(elapsed), Optional.of(exhaustion));
        }
        regions += charge.regions();
        chunks += charge.chunks();
        readBytes += charge.readBytes();
        retainedBytes += charge.retainedBytes();
        candidateZones += charge.candidateZones();
        maximumDeepScanRadiusObserved = Math.max(maximumDeepScanRadiusObserved, charge.deepScanRadius());
        return new SurveyBudgetDecision(true, usage(elapsed), Optional.empty());
    }

    public SurveyBudgetUsage usage() {
        return usage(elapsed(monotonicMillis.getAsLong()));
    }

    private long elapsed(long now) {
        if (now < startedAt) return Long.MAX_VALUE;
        return now - startedAt;
    }

    private SurveyBudgetExhaustion firstExhaustion(SurveyBudgetCharge charge, long elapsed) {
        if (Thread.currentThread().getId() != ownerThreadId) {
            return exhaustion(SurveyBudgetDimension.SINGLE_THREAD, 1, 1, 1,
                    "survey budget tracker was accessed outside its owning thread");
        }
        if (elapsed == Long.MAX_VALUE || elapsed > budget.maximumDurationMillis()) {
            long reported = elapsed == Long.MAX_VALUE ? budget.maximumDurationMillis() : elapsed;
            return exhaustion(SurveyBudgetDimension.DURATION, reported, 0, budget.maximumDurationMillis(),
                    "survey duration or monotonic-clock guarantee was exhausted");
        }
        if (exceeds(regions, charge.regions(), budget.maximumRegions())) {
            return exhaustion(SurveyBudgetDimension.REGIONS, regions, charge.regions(), budget.maximumRegions(),
                    "maximum region count would be exceeded");
        }
        if (exceeds(chunks, charge.chunks(), budget.maximumChunks())) {
            return exhaustion(SurveyBudgetDimension.CHUNKS, chunks, charge.chunks(), budget.maximumChunks(),
                    "maximum chunk count would be exceeded");
        }
        if (exceeds(readBytes, charge.readBytes(), budget.maximumReadBytes())) {
            return exhaustion(SurveyBudgetDimension.READ_BYTES, readBytes, charge.readBytes(), budget.maximumReadBytes(),
                    "maximum source bytes would be exceeded");
        }
        if (exceeds(retainedBytes, charge.retainedBytes(), budget.maximumRetainedBytes())) {
            return exhaustion(SurveyBudgetDimension.RETAINED_BYTES, retainedBytes, charge.retainedBytes(),
                    budget.maximumRetainedBytes(), "maximum retained-memory estimate would be exceeded");
        }
        if (exceeds(candidateZones, charge.candidateZones(), budget.maximumCandidateZones())) {
            return exhaustion(SurveyBudgetDimension.CANDIDATE_ZONES, candidateZones, charge.candidateZones(),
                    budget.maximumCandidateZones(), "maximum candidate-zone count would be exceeded");
        }
        if (charge.deepScanRadius() > budget.maximumDeepScanRadius()) {
            return exhaustion(SurveyBudgetDimension.DEEP_SCAN_RADIUS, maximumDeepScanRadiusObserved,
                    charge.deepScanRadius(), budget.maximumDeepScanRadius(), "candidate deep-scan radius is too large");
        }
        return null;
    }

    private SurveyBudgetExhaustion exhaustion(
            SurveyBudgetDimension dimension, long current, long requested, long maximum, String reason) {
        return new SurveyBudgetExhaustion(dimension, current, requested, maximum, reason);
    }

    private SurveyBudgetUsage usage(long elapsed) {
        long reportedElapsed = elapsed == Long.MAX_VALUE ? budget.maximumDurationMillis() : elapsed;
        return new SurveyBudgetUsage(regions, chunks, readBytes, reportedElapsed, retainedBytes,
                candidateZones, maximumDeepScanRadiusObserved);
    }

    private static boolean exceeds(long current, long requested, long maximum) {
        return requested > maximum - current;
    }
}
