package dev.stevecreate.agent.adapter.api.create;

import java.util.Comparator;
import java.util.Set;

/** Exact live signals required for success; a fixed sleep can never satisfy this contract. */
public record RuntimeObservationRequirement(
        Set<Signal> signals,
        int maximumObservationTicks,
        boolean fixedSleepForbidden) {
    public enum Signal {
        LIVE_RECIPE,
        KINETIC_SPEED,
        ROTATION_DIRECTION,
        STRESS_STATE,
        AIRFLOW,
        MEDIUM,
        OBSTRUCTION,
        HEAT,
        TOOL_OR_HELD_ITEM,
        MACHINE_STATE,
        BASIN_CONTENTS,
        INPUT_CONSUMED,
        OUTPUT_OBSERVED
    }

    public RuntimeObservationRequirement {
        signals = CapabilityContracts.sortedSet(
                signals, Comparator.comparing(Enum::name), "signals");
        if (signals.isEmpty() || maximumObservationTicks < 1 || maximumObservationTicks > 72_000) {
            throw new IllegalArgumentException("Runtime observation signals or tick bound are invalid");
        }
        if (!signals.contains(Signal.LIVE_RECIPE)
                || !signals.contains(Signal.INPUT_CONSUMED)
                || !signals.contains(Signal.OUTPUT_OBSERVED)
                || !fixedSleepForbidden) {
            throw new IllegalArgumentException(
                    "Completion requires recipe/input/output evidence and forbids fixed sleep");
        }
    }
}
