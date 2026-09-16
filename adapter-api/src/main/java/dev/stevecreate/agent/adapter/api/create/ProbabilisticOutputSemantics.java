package dev.stevecreate.agent.adapter.api.create;

import java.util.List;

/** Probability evidence parallel to the ordered output pool. */
public record ProbabilisticOutputSemantics(
        List<Integer> probabilityNumerators,
        int denominator,
        boolean deterministicPrimaryRequiredForPhaseI) {
    public ProbabilisticOutputSemantics {
        probabilityNumerators = CapabilityContracts.list(
                probabilityNumerators, "probabilityNumerators");
        if (denominator != CapabilityOutput.PROBABILITY_DENOMINATOR
                || !deterministicPrimaryRequiredForPhaseI) {
            throw new IllegalArgumentException("Probability semantics are incomplete");
        }
        for (Integer numerator : probabilityNumerators) {
            if (numerator < 1 || numerator > denominator) {
                throw new IllegalArgumentException("Probability numerator is outside (0, denominator]");
            }
        }
    }

    public boolean hasProbabilisticOutput() {
        return probabilityNumerators.stream().anyMatch(value -> value != denominator);
    }

    public boolean primaryIsDeterministic() {
        return !probabilityNumerators.isEmpty() && probabilityNumerators.get(0) == denominator;
    }
}
