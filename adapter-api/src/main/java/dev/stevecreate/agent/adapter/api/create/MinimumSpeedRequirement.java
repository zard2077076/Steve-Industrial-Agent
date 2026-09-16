package dev.stevecreate.agent.adapter.api.create;

import java.util.Objects;
import java.util.OptionalLong;

/** Minimum absolute RPM when known; dynamic thresholds must be observed from the runtime. */
public record MinimumSpeedRequirement(
        OptionalLong minimumAbsoluteRpm,
        boolean exactRuntimeThresholdRequired) {
    public MinimumSpeedRequirement {
        minimumAbsoluteRpm = Objects.requireNonNull(minimumAbsoluteRpm, "minimumAbsoluteRpm");
        if (minimumAbsoluteRpm.isPresent()
                && (minimumAbsoluteRpm.getAsLong() < 1 || minimumAbsoluteRpm.getAsLong() > 1_000_000)) {
            throw new IllegalArgumentException("minimumAbsoluteRpm is outside the supported bound");
        }
        if (minimumAbsoluteRpm.isEmpty() && !exactRuntimeThresholdRequired) {
            throw new IllegalArgumentException(
                    "An unspecified speed threshold must be resolved from live runtime evidence");
        }
    }
}
