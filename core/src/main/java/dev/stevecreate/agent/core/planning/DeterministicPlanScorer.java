package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit lower-is-better candidate scoring with stable identity tie-breaking. */
public final class DeterministicPlanScorer {
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);

    public PlanScore score(
            CandidatePlan candidate,
            PlanScoringWeights weights,
            PlanScoringContext context) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(context, "context");

        int stepCount = candidate.processingOrder().size();
        int machineKinds = candidate.requiredMachineCapabilities().size();
        int rawKinds = candidate.rawMaterials().size();
        int unavailable = (int) candidate.requiredMachineCapabilities().stream()
                .filter(value -> !context.availableCapabilityIds().contains(value))
                .count();
        long modPenaltyUnits = modPenaltyUnits(candidate, context.preferredModIds());
        int ownedBasisPoints = ownedUtilizationBasisPoints(candidate);

        BigInteger stepContribution = contribution(
                stepCount,
                effectiveWeight(
                        weights.stepCountWeight(),
                        PlanningStrategyPreference.MINIMIZE_STEPS,
                        candidate,
                        weights));
        BigInteger machineContribution = contribution(
                machineKinds,
                effectiveWeight(
                        weights.machineKindWeight(),
                        PlanningStrategyPreference.MINIMIZE_MACHINE_TYPES,
                        candidate,
                        weights));
        BigInteger rawContribution = contribution(
                rawKinds,
                effectiveWeight(
                        weights.rawMaterialKindWeight(),
                        PlanningStrategyPreference.MINIMIZE_RAW_MATERIAL_TYPES,
                        candidate,
                        weights));
        BigInteger unavailableContribution = contribution(
                unavailable, weights.unavailableCapabilityWeight());
        BigInteger modContribution = contribution(
                modPenaltyUnits, weights.modPreferenceRankWeight());
        BigInteger unownedContribution = contribution(
                10_000L - ownedBasisPoints,
                effectiveWeight(
                        weights.unownedBasisPointWeight(),
                        PlanningStrategyPreference.PREFER_OWNED_RESOURCES,
                        candidate,
                        weights));
        BigInteger timeContribution;
        if (candidate.estimatedProcessingTicks().isPresent()) {
            timeContribution = contribution(
                    candidate.estimatedProcessingTicks().getAsLong(),
                    effectiveWeight(
                            weights.processingTickWeight(),
                            PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME,
                            candidate,
                            weights));
        } else {
            timeContribution = contribution(
                    weights.unknownProcessingTimePenalty(),
                    preferenceMultiplier(
                            PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME,
                            candidate,
                            weights));
        }
        BigInteger total = List.of(
                        stepContribution,
                        machineContribution,
                        rawContribution,
                        unavailableContribution,
                        modContribution,
                        unownedContribution,
                        timeContribution)
                .stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
        return new PlanScore(
                candidate.candidateId(),
                stepCount,
                machineKinds,
                rawKinds,
                unavailable,
                modPenaltyUnits,
                ownedBasisPoints,
                candidate.estimatedProcessingTicks(),
                stepContribution,
                machineContribution,
                rawContribution,
                unavailableContribution,
                modContribution,
                unownedContribution,
                timeContribution,
                total);
    }

    public List<ScoredCandidate> rank(
            List<CandidatePlan> candidates,
            PlanScoringWeights weights,
            PlanScoringContext context) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || candidates.size() > PlanningSuccess.MAX_GRAPHS) {
            throw new IllegalArgumentException("Candidate count violates scoring bounds");
        }
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        Set<ResourceId> identities = new HashSet<>();
        for (CandidatePlan candidate : candidates) {
            CandidatePlan value = Objects.requireNonNull(candidate, "candidates element");
            if (!identities.add(value.candidateId())) {
                throw new IllegalArgumentException("Duplicate candidate ID: " + value.candidateId());
            }
            scored.add(new ScoredCandidate(value, score(value, weights, context)));
        }
        scored.sort(Comparator
                .comparing((ScoredCandidate value) -> value.score().total())
                .thenComparing(value -> value.candidate().candidateId().toString()));
        return List.copyOf(scored);
    }

    public CandidatePlan select(
            List<CandidatePlan> candidates,
            PlanScoringWeights weights,
            PlanScoringContext context) {
        return rank(candidates, weights, context).get(0).candidate();
    }

    private static long effectiveWeight(
            long base,
            PlanningStrategyPreference preference,
            CandidatePlan candidate,
            PlanScoringWeights weights) {
        return Math.multiplyExact(base, preferenceMultiplier(preference, candidate, weights));
    }

    private static long preferenceMultiplier(
            PlanningStrategyPreference preference,
            CandidatePlan candidate,
            PlanScoringWeights weights) {
        return candidate.goal().strategyPreferences().contains(preference)
                ? weights.goalPreferenceMultiplier()
                : 1L;
    }

    private static BigInteger contribution(long units, long weight) {
        if (units < 0 || weight < 0) {
            throw new IllegalArgumentException("Scoring units and weights cannot be negative");
        }
        return BigInteger.valueOf(units).multiply(BigInteger.valueOf(weight));
    }

    private static int ownedUtilizationBasisPoints(CandidatePlan candidate) {
        BigInteger raw = sumAmounts(candidate.rawMaterials());
        BigInteger owned = sumAmounts(candidate.ownedResourcesUsed());
        BigInteger total = raw.add(owned);
        if (total.signum() == 0) {
            return 10_000;
        }
        return owned.multiply(BASIS_POINTS).divide(total).intValueExact();
    }

    private static BigInteger sumAmounts(List<ProcessResource> values) {
        return values.stream()
                .map(value -> BigInteger.valueOf(value.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static long modPenaltyUnits(CandidatePlan candidate, List<String> preferences) {
        if (preferences.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (CatalogRecipe recipe : candidate.selectedRecipes()) {
            int index = preferences.indexOf(recipe.source().sourceModId());
            total = Math.addExact(total, index >= 0 ? index : preferences.size() + 1L);
        }
        return total;
    }
}
