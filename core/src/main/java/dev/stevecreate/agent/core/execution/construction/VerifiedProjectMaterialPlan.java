package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Complete process, construction, route, power and tool material authority for one project. */
public record VerifiedProjectMaterialPlan(
        ResourceId planId,
        ResourceId projectId,
        ResourceId target,
        long targetQuantity,
        ResourceId verifiedPhysicalPlanId,
        String runtimeFingerprint,
        String planSha256,
        List<ProjectMaterialLine> lines) {
    public static final int MAX_LINES = 512;

    public VerifiedProjectMaterialPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(target, "target");
        if (targetQuantity < 1 || targetQuantity > 1_000_000L) {
            throw new IllegalArgumentException("target quantity is outside its project bound");
        }
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
            throw new IllegalArgumentException("runtime fingerprint is blank or unbounded");
        }
        Objects.requireNonNull(planSha256, "planSha256");
        if (!planSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("project material plan hash is invalid");
        }
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("project material lines are empty or unbounded");
        }
        lines = lines.stream().sorted(Comparator.comparing(value -> value.lineId().toString())).toList();
        if (lines.stream().map(ProjectMaterialLine::lineId).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("duplicate project material line identity");
        }
    }

    /** Compatibility projection consumed by the frozen Phase IV selected-container UI/ledger. */
    public Map<ResourceId, Long> legacyRequirementTotals() {
        return totals(lines);
    }

    public Map<ResourceId, Long> installedTotals() {
        return totals(lines.stream()
                .filter(value -> value.disposition() == ProjectMaterialDisposition.INSTALL).toList());
    }

    public Map<ResourceId, Long> consumedTotals() {
        return totals(lines.stream()
                .filter(value -> value.disposition() == ProjectMaterialDisposition.CONSUME).toList());
    }

    public Map<ResourceId, Long> leasedTotals() {
        return totals(lines.stream()
                .filter(value -> value.disposition() == ProjectMaterialDisposition.LEASE).toList());
    }

    private static Map<ResourceId, Long> totals(List<ProjectMaterialLine> values) {
        TreeMap<ResourceId, Long> sorted = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach(value -> sorted.merge(value.resourceId(), value.quantity(), Math::addExact));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
