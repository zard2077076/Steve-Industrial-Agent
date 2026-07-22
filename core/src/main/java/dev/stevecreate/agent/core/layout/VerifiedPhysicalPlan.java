package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only physical plan available only after the complete independent verifier passes. */
public final class VerifiedPhysicalPlan {
    private final ResourceId id;
    private final PhysicalLayoutCandidate candidate;
    private final Map<PhysicalizationVerificationCheck, PhysicalizationVerificationEvidence> evidence;

    VerifiedPhysicalPlan(
            PhysicalLayoutCandidate candidate,
            List<PhysicalizationVerificationEvidence> evidence) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.id = ResourceId.parse("layout:verified_" + candidate.id().path());
        EnumMap<PhysicalizationVerificationCheck, PhysicalizationVerificationEvidence> indexed =
                new EnumMap<>(PhysicalizationVerificationCheck.class);
        for (PhysicalizationVerificationEvidence item : Objects.requireNonNull(evidence, "evidence")) {
            if (indexed.putIfAbsent(item.check(), item) != null) {
                throw new IllegalArgumentException("duplicate physicalization check " + item.check());
            }
        }
        if (indexed.size() != PhysicalizationVerificationCheck.values().length) {
            throw new IllegalArgumentException("verified physical plan requires the complete checklist");
        }
        Map<PhysicalizationVerificationCheck, PhysicalizationVerificationEvidence> ordered = new LinkedHashMap<>();
        for (PhysicalizationVerificationCheck check : PhysicalizationVerificationCheck.values()) {
            ordered.put(check, indexed.get(check));
        }
        this.evidence = Collections.unmodifiableMap(ordered);
    }

    public ResourceId id() { return id; }
    public PhysicalLayoutCandidate candidate() { return candidate; }
    public UnifiedMachineGraph unifiedGraph() { return candidate.unifiedGraph(); }
    public List<PhysicalMachinePlacement> placements() { return candidate.placements(); }
    public List<PhysicalRoute> routes() { return candidate.routes(); }
    public Map<PhysicalizationVerificationCheck, PhysicalizationVerificationEvidence> evidence() { return evidence; }
}
