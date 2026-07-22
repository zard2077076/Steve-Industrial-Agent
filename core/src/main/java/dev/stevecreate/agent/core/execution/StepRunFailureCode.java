package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;

/** Stable typed failures produced by the bounded generic runner itself. */
public enum StepRunFailureCode {
    PRECONDITION_FAILED("precondition_failed"),
    ACTION_FAILED("action_failed"),
    VERIFICATION_FAILED("verification_failed"),
    TIMEOUT("timeout"),
    MISSING_HANDLER("missing_handler"),
    MISSING_EVALUATOR("missing_evaluator"),
    RETRY_EXHAUSTED("retry_exhausted"),
    CANCELLATION_REJECTED("cancellation_rejected");

    private final ResourceId resourceId;

    StepRunFailureCode(String path) {
        this.resourceId = ResourceId.parse("steve_industrial:runner/" + path);
    }

    public ResourceId resourceId() {
        return resourceId;
    }
}
