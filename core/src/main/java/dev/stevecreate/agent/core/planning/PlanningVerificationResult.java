package dev.stevecreate.agent.core.planning;

/** Read-only planning verification returns a typed plan or an explicit typed failure. */
public sealed interface PlanningVerificationResult
        permits PlanningVerificationSuccess, PlanningVerificationFailureResult {}
