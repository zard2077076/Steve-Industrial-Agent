package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

/** Runtime-backed planning returns either one verified logical result or a complete typed failure. */
public sealed interface RuntimePlanningResult
        permits RuntimePlanningResult.Success, RuntimePlanningResult.Failure {
    record Success(RuntimeVerifiedPlanningResult result) implements RuntimePlanningResult {
        public Success {
            Objects.requireNonNull(result, "result");
        }
    }

    record Failure(RuntimeKnowledgeFailure failure) implements RuntimePlanningResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
