package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Explicit nonempty success or complete typed failure for runtime recipe discovery. */
public sealed interface RuntimeRecipeCatalogResult
        permits RuntimeRecipeCatalogResult.Success, RuntimeRecipeCatalogResult.Failure {
    record Success(RuntimeRecipeCatalogSnapshot snapshot) implements RuntimeRecipeCatalogResult {
        public Success {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(RuntimeKnowledgeFailure failure) implements RuntimeRecipeCatalogResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
