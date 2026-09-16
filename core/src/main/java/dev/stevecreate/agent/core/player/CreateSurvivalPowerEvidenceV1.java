package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Complete, loader-neutral evidence gate for a survival kinetic source.
 *
 * <p>This contract deliberately separates evidence completeness from capability
 * approval. A future candidate may prove every field here and
 * still remain unavailable while its mapping entry is {@code REVIEW_REQUIRED}.
 * No world, executor, inventory or mutation authority is carried by this type.</p>
 */
public final class CreateSurvivalPowerEvidenceV1 {
    private CreateSurvivalPowerEvidenceV1() {}

    public static Validation validate(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        CreateSurvivalPowerMappingV1.Entry mapping = candidate.mapping();
        if (!mapping.powerResource().equals(candidate.sourceResource())) {
            return Refused.of("POWER_SOURCE_MISMATCH",
                    "expected=" + mapping.powerResource()
                            + ",actual=" + candidate.sourceResource());
        }
        if (candidate.topologyFingerprint().isBlank()) {
            return Refused.of("POWER_TOPOLOGY_EVIDENCE_REQUIRED", "topology fingerprint is blank");
        }

        Set<String> expected = Set.copyOf(mapping.roles());
        Set<String> actual = candidate.roleObservations().keySet();
        if (!expected.equals(actual)) {
            TreeSet<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            TreeSet<String> unexpected = new TreeSet<>(actual);
            unexpected.removeAll(expected);
            return Refused.of("POWER_ROLE_EVIDENCE_MISMATCH",
                    "missing=" + String.join(",", missing)
                            + ",unexpected=" + String.join(",", unexpected));
        }
        for (String role : mapping.roles()) {
            RoleObservation observation = candidate.roleObservations().get(role);
            ResourceId expectedBlock = mapping.roleBlockIds().get(role);
            if (!observation.blockId().equals(expectedBlock)) {
                return Refused.of("POWER_ROLE_SOURCE_MISMATCH",
                        role + " expected=" + expectedBlock
                                + ",actual=" + observation.blockId());
            }
            if (observation.observedTick() < 0 || observation.networkId().isBlank()
                    || !Double.isFinite(observation.speedRpm()) || observation.speedRpm() <= 0) {
                return Refused.of("POWER_LIVE_OBSERVATION_REQUIRED", role);
            }
            if (!Double.isFinite(observation.stressCapacity())
                    || observation.stressCapacity() < 0
                    || !Double.isFinite(observation.stressLoad())
                    || observation.stressLoad() < 0) {
                return Refused.of("POWER_STRESS_OBSERVATION_INVALID", role);
            }
            if (observation.overstressed()
                    || (observation.stressEnabled()
                    && observation.stressLoad() > observation.stressCapacity())) {
                return Refused.of("POWER_OVERSTRESSED", role);
            }
        }
        if (!candidate.materialPlanBound() || candidate.materialPlanHash().isBlank()) {
            return Refused.of("POWER_MATERIAL_PLAN_REQUIRED", "material plan is not bound");
        }
        if (!candidate.ledgerBalanced()) {
            return Refused.of("POWER_MATERIAL_LEDGER_UNBALANCED", "ledger is not balanced");
        }
        if (!candidate.rollbackVerified()) {
            return Refused.of("POWER_ROLLBACK_EVIDENCE_REQUIRED", "rollback evidence is absent");
        }
        if (!candidate.baselineRestored()) {
            return Refused.of("POWER_BASELINE_NOT_RESTORED", "baseline restoration is absent");
        }
        return new Accepted(candidate);
    }

    public sealed interface Validation permits Accepted, Refused {
        boolean evidenceComplete();
    }

    public record Accepted(Candidate candidate) implements Validation {
        public Accepted {
            Objects.requireNonNull(candidate, "candidate");
        }

        @Override
        public boolean evidenceComplete() { return true; }

        /** Evidence does not override the reviewed capability mapping status. */
        public boolean ordinaryPlayerEligible() {
            return candidate.mapping().ordinaryPlayerReady();
        }
    }

    public record Refused(String code, String detail) implements Validation {
        public Refused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            if (code.isBlank() || detail.isBlank()) {
                throw new IllegalArgumentException("power evidence refusal is blank");
            }
        }

        static Refused of(String code, String detail) { return new Refused(code, detail); }

        @Override
        public boolean evidenceComplete() { return false; }
    }

    public record Candidate(
            CreateSurvivalPowerMappingV1.Entry mapping,
            ResourceId sourceResource,
            String topologyFingerprint,
            Map<String, RoleObservation> roleObservations,
            String materialPlanHash,
            boolean materialPlanBound,
            boolean ledgerBalanced,
            boolean rollbackVerified,
            boolean baselineRestored) {
        public Candidate {
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(sourceResource, "sourceResource");
            Objects.requireNonNull(topologyFingerprint, "topologyFingerprint");
            Objects.requireNonNull(roleObservations, "roleObservations");
            Objects.requireNonNull(materialPlanHash, "materialPlanHash");
            if (topologyFingerprint.length() > 4_096 || materialPlanHash.length() > 4_096) {
                throw new IllegalArgumentException("power evidence fingerprint exceeds its bound");
            }
            LinkedHashMap<String, RoleObservation> copy = new LinkedHashMap<>();
            roleObservations.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (entry.getKey() == null || entry.getKey().isBlank()) {
                            throw new IllegalArgumentException("power role is blank");
                        }
                        copy.put(entry.getKey(), Objects.requireNonNull(
                                entry.getValue(), "power role observation"));
                    });
            roleObservations = Collections.unmodifiableMap(copy);
        }
    }

    public record RoleObservation(
            ResourceId blockId,
            String networkId,
            long observedTick,
            double speedRpm,
            boolean stressEnabled,
            double stressCapacity,
            double stressLoad,
            boolean overstressed) {
        public RoleObservation {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(networkId, "networkId");
            if (networkId.length() > 512) {
                throw new IllegalArgumentException("power network id exceeds its bound");
            }
            if (observedTick < -1) {
                throw new IllegalArgumentException("observed tick is outside its bound");
            }
            if (!Double.isFinite(speedRpm()) || speedRpm() < 0) {
                throw new IllegalArgumentException("power speed is outside its bound");
            }
            if (!Double.isFinite(stressCapacity()) || stressCapacity() < 0
                    || !Double.isFinite(stressLoad()) || stressLoad() < 0) {
                throw new IllegalArgumentException("power stress is outside its bound");
            }
        }
    }
}
