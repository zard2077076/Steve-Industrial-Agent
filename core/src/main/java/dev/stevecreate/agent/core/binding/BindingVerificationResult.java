package dev.stevecreate.agent.core.binding;

/** Internal verifier boundary used by the binding service and negative tests. */
public sealed interface BindingVerificationResult permits
        BindingVerificationResult.Success, BindingVerificationResult.Failure {
    record Success(VerifiedImplementationBoundPlan plan) implements BindingVerificationResult {
        public Success {
            if (plan == null) {
                throw new NullPointerException("plan");
            }
        }
    }

    record Failure(BindingFailure failure) implements BindingVerificationResult {
        public Failure {
            if (failure == null) {
                throw new NullPointerException("failure");
            }
        }
    }
}
