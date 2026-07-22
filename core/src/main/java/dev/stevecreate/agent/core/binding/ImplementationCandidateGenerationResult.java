package dev.stevecreate.agent.core.binding;

import java.util.List;

/** Typed candidate-generation result for one logical process node. */
public sealed interface ImplementationCandidateGenerationResult permits
        ImplementationCandidateGenerationResult.Success,
        ImplementationCandidateGenerationResult.Failure {
    record Success(List<ImplementationSelectionCandidate> candidates)
            implements ImplementationCandidateGenerationResult {
        public Success {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("Successful generation requires candidates");
            }
        }
    }

    record Failure(BindingFailure failure) implements ImplementationCandidateGenerationResult {
        public Failure {
            if (failure == null) {
                throw new NullPointerException("failure");
            }
        }
    }
}
