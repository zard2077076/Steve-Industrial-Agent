package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A read-only typed planning result with complete verification evidence.
 *
 * <p>The package-private constructor keeps external callers from bypassing
 * {@link PlanningVerifier}. This value carries no execution or physical-layout authority.</p>
 */
public final class VerifiedLogicalPlan {
    private final ResourceId id;
    private final CandidatePlan candidate;
    private final LogicalMachineGraph logicalGraph;
    private final Map<PlanningVerificationCheck, PlanningVerificationEvidence> evidence;

    VerifiedLogicalPlan(
            CandidatePlan candidate,
            LogicalMachineGraph logicalGraph,
            List<PlanningVerificationEvidence> evidence) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.logicalGraph = Objects.requireNonNull(logicalGraph, "logicalGraph");
        this.id = ResourceId.parse("planning:verified_" + candidate.candidateId().path());
        Objects.requireNonNull(evidence, "evidence");
        EnumMap<PlanningVerificationCheck, PlanningVerificationEvidence> indexed =
                new EnumMap<>(PlanningVerificationCheck.class);
        for (PlanningVerificationEvidence value : evidence) {
            PlanningVerificationEvidence item = Objects.requireNonNull(
                    value, "evidence element");
            if (indexed.putIfAbsent(item.check(), item) != null) {
                throw new IllegalArgumentException(
                        "Duplicate planning verification check " + item.check());
            }
        }
        if (indexed.size() != PlanningVerificationCheck.values().length) {
            throw new IllegalArgumentException(
                    "A verified logical plan requires the complete verification checklist");
        }
        Map<PlanningVerificationCheck, PlanningVerificationEvidence> ordered =
                new LinkedHashMap<>();
        for (PlanningVerificationCheck check : PlanningVerificationCheck.values()) {
            ordered.put(check, indexed.get(check));
        }
        this.evidence = Collections.unmodifiableMap(ordered);
    }

    public ResourceId id() {
        return id;
    }

    public CandidatePlan candidate() {
        return candidate;
    }

    public LogicalMachineGraph logicalGraph() {
        return logicalGraph;
    }

    public Map<PlanningVerificationCheck, PlanningVerificationEvidence> evidence() {
        return evidence;
    }
}
