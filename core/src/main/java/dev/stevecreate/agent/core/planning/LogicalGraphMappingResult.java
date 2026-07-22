package dev.stevecreate.agent.core.planning;

/** Candidate mapping always returns an explicit logical graph or typed failure. */
public sealed interface LogicalGraphMappingResult
        permits LogicalGraphMappingSuccess, LogicalGraphMappingFailureResult {}
