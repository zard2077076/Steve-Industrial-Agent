package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure arithmetic projection; it cannot reserve or withdraw resources. */
public final class DeploymentBudgetService {
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public DeploymentBudget calculate(
            DeploymentPreview preview,
            DeploymentPolicy policy,
            DeploymentBudgetContext context) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        Map<ResourceId, Long> raw = sorted(preview.requiredInputResources());
        Map<ResourceId, Long> intermediate = sorted(context.intermediateProductRequirements());
        Map<ResourceId, Long> construction = sorted(preview.materialBillOfMaterials());
        Map<ResourceId, Long> power = sorted(context.powerComponentRequirements());
        Map<ResourceId, Long> logistics = sorted(context.logisticsComponentRequirements());
        Map<ResourceId, Long> inventory = merge(raw, intermediate);
        Map<ResourceId, Long> output = sorted(preview.expectedOutput());
        long margin = Math.subtractExact(context.availablePowerCapacity(), preview.stressDemand());
        long journalBytes = Math.multiplyExact(
                preview.journalEstimate(), context.journalBytesPerAffectedBlock());

        List<DeploymentBudgetViolation> violations = new ArrayList<>();
        if (preview.journalEstimate() > policy.maximumAffectedBlocks()) {
            violations.add(DeploymentBudgetViolation.AFFECTED_BLOCK_LIMIT_EXCEEDED);
        }
        if (preview.journalEstimate() > policy.worldMutationBudget()) {
            violations.add(DeploymentBudgetViolation.MUTATION_BUDGET_EXCEEDED);
        }
        if (sum(construction) > policy.maximumMaterialCost()) {
            violations.add(DeploymentBudgetViolation.MATERIAL_BUDGET_EXCEEDED);
        }
        if (margin < 0) {
            violations.add(DeploymentBudgetViolation.POWER_CAPACITY_INSUFFICIENT);
        }
        if (preview.stressDemand() > policy.maximumRotationalStressDemand()) {
            violations.add(DeploymentBudgetViolation.STRESS_BUDGET_EXCEEDED);
        }
        if (!containsQuantities(construction, power)) {
            violations.add(DeploymentBudgetViolation.POWER_COMPONENT_NOT_IN_BOM);
        }
        if (!containsQuantities(construction, logistics)) {
            violations.add(DeploymentBudgetViolation.LOGISTICS_COMPONENT_NOT_IN_BOM);
        }
        if (context.resourceSourcePolicy() != policy.resourceSourcePolicy()) {
            violations.add(DeploymentBudgetViolation.RESOURCE_SOURCE_POLICY_MISMATCH);
        }
        if (context.resourceSourcePolicy() == ResourceSourcePolicy.UNSUPPORTED_SOURCE) {
            violations.add(DeploymentBudgetViolation.RESOURCE_SOURCE_UNSUPPORTED);
        }
        if (!context.resourceEvidenceReadOnly()) {
            violations.add(DeploymentBudgetViolation.RESOURCE_SOURCE_NOT_READ_ONLY);
        }
        if (preview.environmentClassification() == WorldEnvironmentType.FORMAL_PLAYER_WORLD
                && !formalReadOnlySource(context.resourceSourcePolicy())) {
            violations.add(DeploymentBudgetViolation.FORMAL_WORLD_AUTO_WITHDRAW_FORBIDDEN);
        }

        return new DeploymentBudget(
                preview.previewHash(), raw, intermediate, construction, power, logistics, inventory,
                output, preview.stressDemand(), margin, preview.estimatedTicks(),
                context.maximumConstructionTicks(), policy.maximumAffectedBlocks(),
                context.rollbackExtraSpaceBytes(), journalBytes, context.resourceSourcePolicy(),
                violations, violations.isEmpty());
    }

    private static boolean formalReadOnlySource(ResourceSourcePolicy value) {
        return value == ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN
                || value == ResourceSourcePolicy.PLAYER_PROVIDED_READ_ONLY_SNAPSHOT
                || value == ResourceSourcePolicy.EXISTING_NETWORK_READ_ONLY;
    }

    private static boolean containsQuantities(
            Map<ResourceId, Long> all,
            Map<ResourceId, Long> subset) {
        return subset.entrySet().stream()
                .allMatch(entry -> all.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    private static long sum(Map<ResourceId, Long> values) {
        long total = 0;
        for (long value : values.values()) total = Math.addExact(total, value);
        return total;
    }

    private static Map<ResourceId, Long> merge(
            Map<ResourceId, Long> first,
            Map<ResourceId, Long> second) {
        TreeMap<ResourceId, Long> values = new TreeMap<>(ID_ORDER);
        values.putAll(first);
        second.forEach((resource, quantity) -> values.merge(resource, quantity, Math::addExact));
        return new LinkedHashMap<>(values);
    }

    private static Map<ResourceId, Long> sorted(Map<ResourceId, Long> source) {
        TreeMap<ResourceId, Long> values = new TreeMap<>(ID_ORDER);
        values.putAll(source);
        return new LinkedHashMap<>(values);
    }
}
