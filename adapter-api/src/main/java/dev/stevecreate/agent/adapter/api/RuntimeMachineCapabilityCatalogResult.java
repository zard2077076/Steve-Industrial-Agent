package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Explicit nonempty capability-catalog success or complete typed runtime failure. */
public sealed interface RuntimeMachineCapabilityCatalogResult
        permits RuntimeMachineCapabilityCatalogResult.Success,
        RuntimeMachineCapabilityCatalogResult.Failure {
    record Success(RuntimeMachineCapabilityCatalogSnapshot snapshot)
            implements RuntimeMachineCapabilityCatalogResult {
        public Success {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(RuntimeKnowledgeFailure failure)
            implements RuntimeMachineCapabilityCatalogResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
