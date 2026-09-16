package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stable output contract. A report is deliberately incapable of authorizing repair. */
public record FactoryHealthReport(
        ResourceId subjectId,
        long observedTick,
        FactoryHealthStatus status,
        List<FactoryDiagnosis> findings,
        Set<FactoryHealthCategory> unknownCategories,
        List<FactoryHealthObservation> observations,
        long worldMutations,
        boolean automaticRepairAuthorized) {
    public FactoryHealthReport {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(status, "status");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        Set<FactoryHealthCategory> requestedUnknown = Objects.requireNonNull(
                unknownCategories, "unknownCategories");
        EnumSet<FactoryHealthCategory> orderedUnknown = requestedUnknown.isEmpty()
                ? EnumSet.noneOf(FactoryHealthCategory.class)
                : EnumSet.copyOf(requestedUnknown);
        unknownCategories = Collections.unmodifiableSet(orderedUnknown);
        observations = Objects.requireNonNull(observations, "observations").stream()
                .sorted(java.util.Comparator.comparing(FactoryHealthObservation::category))
                .toList();
        if (observations.size() != FactoryHealthCategory.values().length
                || observations.stream().map(FactoryHealthObservation::category).distinct().count()
                        != FactoryHealthCategory.values().length) {
            throw new IllegalArgumentException("diagnostic report observation coverage is incomplete");
        }
        EnumSet<FactoryHealthCategory> observedUnknown = EnumSet.noneOf(
                FactoryHealthCategory.class);
        EnumSet<FactoryHealthCategory> observedFaults = EnumSet.noneOf(
                FactoryHealthCategory.class);
        observations.forEach(observation -> {
            if (observation.state() == FactoryObservationState.UNKNOWN) {
                observedUnknown.add(observation.category());
            } else if (observation.state() == FactoryObservationState.FAULT) {
                observedFaults.add(observation.category());
            }
        });
        EnumSet<FactoryHealthCategory> findingCategories = EnumSet.noneOf(
                FactoryHealthCategory.class);
        findings.forEach(finding -> findingCategories.add(finding.category()));
        if (!observedUnknown.equals(orderedUnknown)
                || !observedFaults.equals(findingCategories)
                || findings.size() != findingCategories.size()) {
            throw new IllegalArgumentException("diagnostic report summary and observations disagree");
        }
        if (observedTick < 0 || worldMutations != 0 || automaticRepairAuthorized) {
            throw new IllegalArgumentException("diagnostic report attempted mutation authority");
        }
        if ((status == FactoryHealthStatus.FAULTED) != !findings.isEmpty()) {
            throw new IllegalArgumentException("diagnostic status and findings disagree");
        }
        if (status == FactoryHealthStatus.HEALTHY && !unknownCategories.isEmpty()) {
            throw new IllegalArgumentException("healthy report still has unknown categories");
        }
        if (status == FactoryHealthStatus.INCONCLUSIVE
                && unknownCategories.isEmpty()) {
            throw new IllegalArgumentException("inconclusive report has no unknown categories");
        }
    }
}
