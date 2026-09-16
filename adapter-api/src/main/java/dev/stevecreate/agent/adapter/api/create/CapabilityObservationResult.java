package dev.stevecreate.agent.adapter.api.create;

import java.util.Objects;

/** Read-only observer success or complete typed failure. */
public sealed interface CapabilityObservationResult
        permits CapabilityObservationResult.Success, CapabilityObservationResult.Failure {
    record Success(CapabilityRuntimeObservation observation) implements CapabilityObservationResult {
        public Success {
            Objects.requireNonNull(observation, "observation");
        }
    }

    record Failure(CapabilityObservationFailure failure) implements CapabilityObservationResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
