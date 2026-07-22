package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Explicit nonempty exact-catalog success or complete typed ingredient-resolution failure. */
public sealed interface RuntimeRecipeCatalogResolutionResult
        permits RuntimeRecipeCatalogResolutionResult.Success,
        RuntimeRecipeCatalogResolutionResult.Failure {
    record Success(ResolvedRuntimeRecipeCatalog resolved)
            implements RuntimeRecipeCatalogResolutionResult {
        public Success {
            Objects.requireNonNull(resolved, "resolved");
        }
    }

    record Failure(RuntimeKnowledgeFailure failure)
            implements RuntimeRecipeCatalogResolutionResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
