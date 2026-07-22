package dev.stevecreate.agent.core.planning;

/** A dependency-planning operation always returns explicit success or typed failure. */
public sealed interface PlanningResult permits PlanningSuccess, PlanningFailureResult {}
