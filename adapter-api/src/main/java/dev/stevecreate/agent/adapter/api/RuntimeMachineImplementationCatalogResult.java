package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.binding.BindingFailure;
import java.util.Objects;

/** Explicit success or typed failure for runtime implementation-catalog publication. */
public sealed interface RuntimeMachineImplementationCatalogResult
        permits RuntimeMachineImplementationCatalogResult.Success,
        RuntimeMachineImplementationCatalogResult.Failure {
    record Success(RuntimeMachineImplementationCatalogSnapshot snapshot)
            implements RuntimeMachineImplementationCatalogResult {
        public Success {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(BindingFailure failure) implements RuntimeMachineImplementationCatalogResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
