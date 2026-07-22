package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Auditable deterministic score; lower totals rank first. */
public record PlanScore(
        ResourceId candidateId,
        int stepCount,
        int machineKindCount,
        int rawMaterialKindCount,
        int unavailableCapabilityCount,
        long modPreferencePenaltyUnits,
        int ownedUtilizationBasisPoints,
        OptionalLong estimatedProcessingTicks,
        BigInteger stepContribution,
        BigInteger machineContribution,
        BigInteger rawMaterialContribution,
        BigInteger unavailableCapabilityContribution,
        BigInteger modPreferenceContribution,
        BigInteger unownedResourceContribution,
        BigInteger processingTimeContribution,
        BigInteger total) {
    public PlanScore {
        Objects.requireNonNull(candidateId, "candidateId");
        if (stepCount < 0
                || machineKindCount < 0
                || rawMaterialKindCount < 0
                || unavailableCapabilityCount < 0
                || modPreferencePenaltyUnits < 0
                || ownedUtilizationBasisPoints < 0
                || ownedUtilizationBasisPoints > 10_000) {
            throw new IllegalArgumentException("Score counts and utilization must be nonnegative and bounded");
        }
        estimatedProcessingTicks = Objects.requireNonNull(
                estimatedProcessingTicks, "estimatedProcessingTicks");
        List<BigInteger> contributions = List.of(
                requireNonnegative(stepContribution, "stepContribution"),
                requireNonnegative(machineContribution, "machineContribution"),
                requireNonnegative(rawMaterialContribution, "rawMaterialContribution"),
                requireNonnegative(
                        unavailableCapabilityContribution,
                        "unavailableCapabilityContribution"),
                requireNonnegative(modPreferenceContribution, "modPreferenceContribution"),
                requireNonnegative(unownedResourceContribution, "unownedResourceContribution"),
                requireNonnegative(processingTimeContribution, "processingTimeContribution"));
        total = requireNonnegative(total, "total");
        BigInteger calculated = contributions.stream().reduce(BigInteger.ZERO, BigInteger::add);
        if (!calculated.equals(total)) {
            throw new IllegalArgumentException("Score total does not equal its explicit contributions");
        }
    }

    private static BigInteger requireNonnegative(BigInteger value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }
}
