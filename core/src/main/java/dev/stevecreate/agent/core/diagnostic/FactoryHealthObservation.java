package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** One bounded, immutable answer with enough provenance to audit the diagnosis. */
public record FactoryHealthObservation(
        FactoryHealthCategory category,
        FactoryObservationState state,
        FactoryEvidenceSource source,
        String evidenceCode,
        Map<String, Long> metrics,
        Set<ResourceId> resources,
        String detail) {
    public static final int MAX_METRICS = 16;
    public static final int MAX_RESOURCES = 32;
    public static final int MAX_DETAIL_LENGTH = 512;

    public FactoryHealthObservation {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(evidenceCode, "evidenceCode");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(detail, "detail");
        if (!evidenceCode.matches("[A-Z0-9_:.\\-/]{1,128}")) {
            throw new IllegalArgumentException("factory evidence code is invalid");
        }
        if (metrics.size() > MAX_METRICS || resources.size() > MAX_RESOURCES
                || detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("factory observation is blank or unbounded");
        }
        if (state == FactoryObservationState.UNKNOWN
                && source != FactoryEvidenceSource.NO_PROBE
                && source != FactoryEvidenceSource.LIVE_WORLD_UNAVAILABLE
                && source != FactoryEvidenceSource.DURABLE_RUNTIME_STATUS) {
            throw new IllegalArgumentException("unknown observation claims conclusive evidence");
        }
        if (state == FactoryObservationState.FAULT
                && source == FactoryEvidenceSource.NO_PROBE) {
            throw new IllegalArgumentException("factory fault has no evidence source");
        }
        TreeMap<String, Long> orderedMetrics = new TreeMap<>();
        metrics.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z][a-zA-Z0-9_.-]{0,63}") || value == null) {
                throw new IllegalArgumentException("factory metric is invalid");
            }
            orderedMetrics.put(key, value);
        });
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(orderedMetrics));
        LinkedHashSet<ResourceId> orderedResources = new LinkedHashSet<>();
        resources.stream().sorted(Comparator.comparing(ResourceId::toString))
                .forEach(orderedResources::add);
        resources = Collections.unmodifiableSet(orderedResources);
    }
}
