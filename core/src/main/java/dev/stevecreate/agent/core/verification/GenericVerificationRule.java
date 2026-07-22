package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable bounded required/optional evidence rule with deterministic typed evaluation. */
public final class GenericVerificationRule {
    public static final int MAX_REQUIRED_EVIDENCE = 64;
    public static final int MAX_OPTIONAL_EVIDENCE = 64;
    public static final int MAX_OBSERVATIONS = 1_024;
    public static final int MAX_TIMEOUT_TICKS = GenericProcessSpec.MAX_WAIT_TICKS;

    private final List<EvidenceRequirement> requiredEvidence;
    private final List<EvidenceRequirement> optionalEvidence;
    private final int timeoutTicks;
    private final boolean adapterSpecificEvidenceAllowed;

    public GenericVerificationRule(
            List<EvidenceRequirement> requiredEvidence,
            List<EvidenceRequirement> optionalEvidence,
            int timeoutTicks,
            boolean adapterSpecificEvidenceAllowed) {
        this.requiredEvidence = copyRequirements(
                requiredEvidence, "requiredEvidence", MAX_REQUIRED_EVIDENCE, true);
        this.optionalEvidence = copyRequirements(
                optionalEvidence, "optionalEvidence", MAX_OPTIONAL_EVIDENCE, false);
        rejectOverlappingIds(this.requiredEvidence, this.optionalEvidence);
        if (timeoutTicks < 1 || timeoutTicks > MAX_TIMEOUT_TICKS) {
            throw new IllegalArgumentException(
                    "timeoutTicks must be between 1 and " + MAX_TIMEOUT_TICKS);
        }
        this.timeoutTicks = timeoutTicks;
        this.adapterSpecificEvidenceAllowed = adapterSpecificEvidenceAllowed;
        if (!adapterSpecificEvidenceAllowed) {
            rejectCustomRequirements(this.requiredEvidence);
            rejectCustomRequirements(this.optionalEvidence);
        }
    }

    public static GenericVerificationRule forProcess(
            GenericProcessSpec processSpec,
            List<EvidenceRequirement> requiredEvidence,
            List<EvidenceRequirement> optionalEvidence,
            boolean adapterSpecificEvidenceAllowed) {
        Objects.requireNonNull(processSpec, "processSpec");
        GenericVerificationRule rule = new GenericVerificationRule(
                requiredEvidence,
                optionalEvidence,
                processSpec.maximumWaitTicks(),
                adapterSpecificEvidenceAllowed);
        Set<ResourceId> declared = rule.requiredEvidence.stream()
                .map(EvidenceRequirement::requirementId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<ResourceId> missing = new LinkedHashSet<>(
                processSpec.requiredCompletionEvidence());
        missing.removeAll(declared);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "requiredEvidence omits process completion requirements: " + missing);
        }
        return rule;
    }

    public List<EvidenceRequirement> requiredEvidence() {
        return requiredEvidence;
    }

    public List<EvidenceRequirement> optionalEvidence() {
        return optionalEvidence;
    }

    public int timeoutTicks() {
        return timeoutTicks;
    }

    public boolean adapterSpecificEvidenceAllowed() {
        return adapterSpecificEvidenceAllowed;
    }

    public GenericVerificationResult evaluate(
            List<VerificationEvidence> evidence,
            ResourceId sourceStepId,
            long startedTick,
            long currentTick) {
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        if (startedTick < 0 || currentTick < startedTick) {
            throw new IllegalArgumentException(
                    "Evaluation ticks must be non-negative and monotonic");
        }
        List<VerificationEvidence> observations = copyObservations(evidence);
        Map<ResourceId, List<VerificationEvidence>> byRequirement = latestByRequirement(
                observations, sourceStepId, startedTick, currentTick);

        LinkedHashSet<ResourceId> satisfied = new LinkedHashSet<>();
        LinkedHashSet<ResourceId> missing = new LinkedHashSet<>();
        LinkedHashSet<ResourceId> observedOptional = new LinkedHashSet<>();
        List<VerificationEvidence> considered = new ArrayList<>();

        for (EvidenceRequirement requirement : requiredEvidence) {
            List<VerificationEvidence> matches = byRequirement.get(requirement.requirementId());
            if (matches == null) {
                missing.add(requirement.requirementId());
                continue;
            }
            considered.addAll(matches);
            Optional<VerificationRuleFailure> failure = validateMatches(requirement, matches);
            if (failure.isPresent()) {
                return result(
                        GenericVerificationStatus.FAILED,
                        considered,
                        satisfied,
                        missing,
                        observedOptional,
                        failure,
                        currentTick);
            }
            satisfied.add(requirement.requirementId());
        }

        for (EvidenceRequirement requirement : optionalEvidence) {
            List<VerificationEvidence> matches = byRequirement.get(requirement.requirementId());
            if (matches == null) {
                continue;
            }
            considered.addAll(matches);
            observedOptional.add(requirement.requirementId());
            // Optional observations are retained for diagnostics but never substitute for required proof.
        }

        if (!missing.isEmpty()) {
            if (currentTick - startedTick >= timeoutTicks) {
                VerificationRuleFailure failure = new VerificationRuleFailure(
                        VerificationRuleFailureCode.REQUIRED_EVIDENCE_TIMEOUT,
                        Optional.of(missing.iterator().next()),
                        "Required evidence was still missing after " + timeoutTicks
                                + " ticks: " + missing);
                return result(
                        GenericVerificationStatus.TIMED_OUT,
                        considered,
                        satisfied,
                        missing,
                        observedOptional,
                        Optional.of(failure),
                        currentTick);
            }
            return result(
                    GenericVerificationStatus.PENDING,
                    considered,
                    satisfied,
                    missing,
                    observedOptional,
                    Optional.empty(),
                    currentTick);
        }

        return result(
                GenericVerificationStatus.PASSED,
                considered,
                satisfied,
                missing,
                observedOptional,
                Optional.empty(),
                currentTick);
    }

    private Optional<VerificationRuleFailure> validateMatches(
            EvidenceRequirement requirement,
            List<VerificationEvidence> matches) {
        for (VerificationEvidence value : matches) {
            if (value.kind() == VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE
                    && !adapterSpecificEvidenceAllowed) {
                return failure(
                        VerificationRuleFailureCode.CUSTOM_ADAPTER_EVIDENCE_NOT_ALLOWED,
                        requirement,
                        "Custom adapter evidence is disabled for " + requirement.requirementId());
            }
            if (value.kind() != requirement.kind()) {
                return failure(
                        VerificationRuleFailureCode.EVIDENCE_KIND_MISMATCH,
                        requirement,
                        "Expected " + requirement.kind() + " but observed " + value.kind());
            }
            if (requirement.requiredSourceId().isPresent()
                    && !requirement.requiredSourceId().orElseThrow().equals(value.sourceId())) {
                return failure(
                        VerificationRuleFailureCode.EVIDENCE_SOURCE_MISMATCH,
                        requirement,
                        "Expected source " + requirement.requiredSourceId().orElseThrow()
                                + " but observed " + value.sourceId());
            }
            if (requirement.requiredTargetId().isPresent()
                    && !requirement.requiredTargetId().orElseThrow().equals(value.targetId())) {
                return failure(
                        VerificationRuleFailureCode.EVIDENCE_TARGET_MISMATCH,
                        requirement,
                        "Expected target " + requirement.requiredTargetId().orElseThrow()
                                + " but observed " + value.targetId());
            }
            if (!value.passed()) {
                String diagnostic = value.diagnostic()
                        .map(EvidenceDiagnostic::detail)
                        .orElse("Observed value did not satisfy the expected value");
                return failure(
                        VerificationRuleFailureCode.EVIDENCE_REJECTED,
                        requirement,
                        diagnostic);
            }
        }
        return Optional.empty();
    }

    private static Optional<VerificationRuleFailure> failure(
            VerificationRuleFailureCode code,
            EvidenceRequirement requirement,
            String detail) {
        return Optional.of(new VerificationRuleFailure(
                code, Optional.of(requirement.requirementId()), detail));
    }

    private static GenericVerificationResult result(
            GenericVerificationStatus status,
            List<VerificationEvidence> considered,
            Set<ResourceId> satisfied,
            Set<ResourceId> missing,
            Set<ResourceId> optional,
            Optional<VerificationRuleFailure> failure,
            long currentTick) {
        return new GenericVerificationResult(
                status,
                considered,
                satisfied,
                missing,
                optional,
                failure,
                currentTick);
    }

    private static List<EvidenceRequirement> copyRequirements(
            List<EvidenceRequirement> values,
            String name,
            int maximum,
            boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty()) || values.size() > maximum) {
            throw new IllegalArgumentException(
                    name + " count must be between " + (requireNonEmpty ? 1 : 0)
                            + " and " + maximum);
        }
        List<EvidenceRequirement> copy = new ArrayList<>(values.size());
        Set<ResourceId> identities = new HashSet<>();
        for (EvidenceRequirement value : values) {
            EvidenceRequirement requirement = Objects.requireNonNull(value, name + " element");
            if (!identities.add(requirement.requirementId())) {
                throw new IllegalArgumentException(
                        name + " contains duplicate requirement: " + requirement.requirementId());
            }
            copy.add(requirement);
        }
        return Collections.unmodifiableList(copy);
    }

    private static void rejectOverlappingIds(
            List<EvidenceRequirement> required,
            List<EvidenceRequirement> optional) {
        Set<ResourceId> requiredIds = new HashSet<>();
        required.forEach(value -> requiredIds.add(value.requirementId()));
        for (EvidenceRequirement value : optional) {
            if (requiredIds.contains(value.requirementId())) {
                throw new IllegalArgumentException(
                        "optionalEvidence overlaps required evidence: " + value.requirementId());
            }
        }
    }

    private static void rejectCustomRequirements(List<EvidenceRequirement> requirements) {
        for (EvidenceRequirement requirement : requirements) {
            if (requirement.kind() == VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE) {
                throw new IllegalArgumentException(
                        "Custom adapter evidence requires adapterSpecificEvidenceAllowed=true: "
                                + requirement.requirementId());
            }
        }
    }

    private static List<VerificationEvidence> copyObservations(List<VerificationEvidence> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.size() > MAX_OBSERVATIONS) {
            throw new IllegalArgumentException(
                    "evidence count exceeds " + MAX_OBSERVATIONS);
        }
        List<VerificationEvidence> copy = new ArrayList<>(values.size());
        Set<ResourceId> evidenceIds = new HashSet<>();
        for (VerificationEvidence value : values) {
            VerificationEvidence evidence = Objects.requireNonNull(value, "evidence element");
            if (!evidenceIds.add(evidence.evidenceId())) {
                throw new IllegalArgumentException(
                        "evidence contains duplicate identity: " + evidence.evidenceId());
            }
            copy.add(evidence);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<ResourceId, List<VerificationEvidence>> latestByRequirement(
            List<VerificationEvidence> observations,
            ResourceId sourceStepId,
            long startedTick,
            long currentTick) {
        Map<ResourceId, Long> latestTicks = new LinkedHashMap<>();
        Map<ResourceId, List<VerificationEvidence>> latest = new LinkedHashMap<>();
        for (VerificationEvidence value : observations) {
            if (!value.sourceStepId().equals(sourceStepId)
                    || value.observedTick() < startedTick
                    || value.observedTick() > currentTick) {
                continue;
            }
            long previous = latestTicks.getOrDefault(value.requirementId(), Long.MIN_VALUE);
            if (value.observedTick() > previous) {
                latestTicks.put(value.requirementId(), value.observedTick());
                latest.put(value.requirementId(), new ArrayList<>(List.of(value)));
            } else if (value.observedTick() == previous) {
                latest.get(value.requirementId()).add(value);
            }
        }
        Comparator<VerificationEvidence> order = Comparator.comparing(
                value -> value.evidenceId().toString());
        for (Map.Entry<ResourceId, List<VerificationEvidence>> entry : latest.entrySet()) {
            entry.getValue().sort(order);
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return latest;
    }
}
