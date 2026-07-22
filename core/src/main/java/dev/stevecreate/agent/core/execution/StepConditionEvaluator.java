package dev.stevecreate.agent.core.execution;

/** Registered bounded condition evaluator; implementations must never block the calling tick. */
@FunctionalInterface
public interface StepConditionEvaluator {
    ConditionEvaluation evaluate(StepCondition condition, StepRunnerContext context);
}
