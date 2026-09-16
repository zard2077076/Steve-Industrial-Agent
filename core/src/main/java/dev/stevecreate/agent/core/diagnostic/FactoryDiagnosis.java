package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One evidence-backed fault plus advice that carries no execution authority. */
public record FactoryDiagnosis(
        FactoryFaultCode code,
        FactoryHealthCategory category,
        Severity severity,
        FactoryRecommendedAction recommendation,
        FactoryEvidenceSource source,
        String evidenceCode,
        Map<String, Long> metrics,
        Set<ResourceId> resources,
        String detail) {
    public FactoryDiagnosis {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(recommendation, "recommendation");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(evidenceCode, "evidenceCode");
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(metrics, "metrics")));
        LinkedHashSet<ResourceId> orderedResources = new LinkedHashSet<>();
        Objects.requireNonNull(resources, "resources").stream()
                .sorted(Comparator.comparing(ResourceId::toString))
                .forEach(orderedResources::add);
        resources = Collections.unmodifiableSet(orderedResources);
        Objects.requireNonNull(detail, "detail");
    }
}
