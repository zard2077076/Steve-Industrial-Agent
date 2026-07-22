package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Unforgeable read-only PW-11 result. It is deliberately not an execution capability. */
public final class DeploymentReadyPlan {
    private final VerifiedPhysicalPlan physicalPlan;
    private final DeploymentPreview preview;
    private final String environmentId;
    private final Map<DeploymentReadinessCheck, DeploymentReadinessEvidence> evidence;
    private final List<String> trace;

    DeploymentReadyPlan(
            VerifiedPhysicalPlan physicalPlan,
            DeploymentPreview preview,
            String environmentId,
            List<DeploymentReadinessEvidence> evidence,
            List<String> trace) {
        this.physicalPlan = Objects.requireNonNull(physicalPlan, "physicalPlan");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
        EnumMap<DeploymentReadinessCheck, DeploymentReadinessEvidence> indexed =
                new EnumMap<>(DeploymentReadinessCheck.class);
        for (DeploymentReadinessEvidence item : Objects.requireNonNull(evidence, "evidence")) {
            if (indexed.putIfAbsent(item.check(), item) != null) {
                throw new IllegalArgumentException("duplicate deployment readiness check " + item.check());
            }
        }
        if (indexed.size() != DeploymentReadinessCheck.values().length) {
            throw new IllegalArgumentException("deployment readiness requires all 25 checks");
        }
        Map<DeploymentReadinessCheck, DeploymentReadinessEvidence> ordered = new LinkedHashMap<>();
        for (DeploymentReadinessCheck check : DeploymentReadinessCheck.values()) {
            ordered.put(check, indexed.get(check));
        }
        this.evidence = Collections.unmodifiableMap(ordered);
        this.trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (this.trace.isEmpty()) throw new IllegalArgumentException("readiness trace is empty");
    }

    public VerifiedPhysicalPlan physicalPlan() { return physicalPlan; }
    public DeploymentPreview preview() { return preview; }
    public String environmentId() { return environmentId; }
    public Map<DeploymentReadinessCheck, DeploymentReadinessEvidence> evidence() { return evidence; }
    public List<String> trace() { return trace; }
}
