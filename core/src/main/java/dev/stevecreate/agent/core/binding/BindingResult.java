package dev.stevecreate.agent.core.binding;

/** Typed result boundary; no missing binding is reported by null, boolean or exception. */
public sealed interface BindingResult permits BindingResult.Success, BindingResult.Failure {
    record Success(VerifiedImplementationBoundPlan plan) implements BindingResult {
        public Success {
            if (plan == null) {
                throw new NullPointerException("plan");
            }
        }
    }

    record Failure(BindingFailure failure) implements BindingResult {
        public Failure {
            if (failure == null) {
                throw new NullPointerException("failure");
            }
        }
    }
}
