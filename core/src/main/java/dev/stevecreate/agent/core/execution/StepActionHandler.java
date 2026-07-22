package dev.stevecreate.agent.core.execution;

/**
 * Registered operation handler. One invocation may perform at most one bounded world action and
 * must return immediately; blocking, sleeping, networking, and unbounded scans violate the contract.
 */
@FunctionalInterface
public interface StepActionHandler {
    ActionHandlerResult invoke(StepActionDescriptor action, StepRunnerContext context);
}
