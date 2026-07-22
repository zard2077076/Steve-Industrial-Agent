package dev.stevecreate.agent.core.execution.readiness;

import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The only physical-plan value accepted by the new goal-driven execution entry. */
public final class ExecutionReadyPlan {
    private final ResourceId sessionId;
    private final VerifiedPhysicalPlan physicalPlan;
    private final Map<ExecutionReadinessVerificationCheck, ExecutionReadinessEvidence> evidence;
    private final List<String> trace;

    ExecutionReadyPlan(
            ResourceId sessionId,
            VerifiedPhysicalPlan physicalPlan,
            List<ExecutionReadinessEvidence> evidence,
            List<String> trace) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.physicalPlan = Objects.requireNonNull(physicalPlan, "physicalPlan");
        EnumMap<ExecutionReadinessVerificationCheck, ExecutionReadinessEvidence> indexed =
                new EnumMap<>(ExecutionReadinessVerificationCheck.class);
        for (ExecutionReadinessEvidence item : Objects.requireNonNull(evidence, "evidence")) {
            if (indexed.putIfAbsent(item.check(), item) != null) {
                throw new IllegalArgumentException("Duplicate readiness check " + item.check());
            }
        }
        if (indexed.size() != ExecutionReadinessVerificationCheck.values().length) {
            throw new IllegalArgumentException("Execution readiness requires the complete checklist");
        }
        Map<ExecutionReadinessVerificationCheck, ExecutionReadinessEvidence> ordered = new LinkedHashMap<>();
        for (ExecutionReadinessVerificationCheck check : ExecutionReadinessVerificationCheck.values()) {
            ordered.put(check, indexed.get(check));
        }
        this.evidence = Collections.unmodifiableMap(ordered);
        this.trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (this.trace.isEmpty()) throw new IllegalArgumentException("Execution readiness trace is empty");
    }

    public ResourceId sessionId() { return sessionId; }
    public VerifiedPhysicalPlan physicalPlan() { return physicalPlan; }
    public Map<ExecutionReadinessVerificationCheck, ExecutionReadinessEvidence> evidence() { return evidence; }
    public List<String> trace() { return trace; }
}
