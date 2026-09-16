package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Complete six-category input to the deterministic analyzer. */
public record FactoryHealthSnapshot(
        ResourceId subjectId,
        long observedTick,
        List<FactoryHealthObservation> observations) {
    public FactoryHealthSnapshot {
        Objects.requireNonNull(subjectId, "subjectId");
        if (observedTick < 0) throw new IllegalArgumentException("observed tick is negative");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (observations.size() != FactoryHealthCategory.values().length) {
            throw new IllegalArgumentException("factory snapshot must answer all six categories");
        }
        EnumSet<FactoryHealthCategory> seen = EnumSet.noneOf(FactoryHealthCategory.class);
        observations.forEach(value -> {
            if (!seen.add(value.category())) {
                throw new IllegalArgumentException("duplicate factory health category");
            }
        });
        if (!seen.equals(EnumSet.allOf(FactoryHealthCategory.class))) {
            throw new IllegalArgumentException("factory snapshot category coverage is incomplete");
        }
        observations = observations.stream()
                .sorted(java.util.Comparator.comparing(FactoryHealthObservation::category))
                .toList();
    }
}
