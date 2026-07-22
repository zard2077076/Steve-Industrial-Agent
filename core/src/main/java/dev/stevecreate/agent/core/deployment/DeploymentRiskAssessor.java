package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed rule table for PW-05; severity never comes from an LLM or free text. */
public final class DeploymentRiskAssessor {
    public DeploymentRiskAssessment assess(
            DeploymentPreview preview,
            DeploymentRiskContext context) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(context, "context");
        List<DeploymentRiskFinding> findings = new ArrayList<>();

        if (!preview.plannedPlacements().isEmpty()) {
            add(findings, DeploymentRiskCategory.BLOCK_PLACEMENT_COUNT, RiskSeverity.INFO,
                    Integer.toString(preview.plannedPlacements().size()),
                    "The preview places bounded machine blocks",
                    "Review the exact placement list and affected bounds");
        }
        if (!preview.plannedRemovals().isEmpty()) {
            add(findings, DeploymentRiskCategory.BLOCK_REMOVAL_COUNT, RiskSeverity.LOW,
                    Integer.toString(preview.plannedRemovals().size()),
                    "The preview includes explicit block removals",
                    "Review every removal and require rollback coverage");
        }
        if (context.unknownBlockReplacement()
                || preview.policyViolations().contains("UNKNOWN_BLOCK_REPLACEMENT_FORBIDDEN")) {
            add(findings, DeploymentRiskCategory.UNKNOWN_BLOCK_REPLACEMENT, RiskSeverity.HIGH,
                    Integer.toString(preview.plannedReplacements().size()),
                    "At least one replacement lacks a trusted replaceable-block classification",
                    "Classify the existing blocks or exclude their positions");
        }
        if (context.playerBuildingAtRisk()) {
            add(findings, DeploymentRiskCategory.PLAYER_BUILDING_OVERWRITE, RiskSeverity.CRITICAL,
                    preview.affectedBounds().toString(),
                    "The affected region may overlap player-built structure",
                    "Exclude the structure or obtain exact region and owner authorization");
        }
        if (!preview.blockEntitiesEncountered().isEmpty()) {
            add(findings, DeploymentRiskCategory.BLOCK_ENTITY, RiskSeverity.CRITICAL,
                    Integer.toString(preview.blockEntitiesEncountered().size()),
                    "Block entities are present in the affected region",
                    "Protect them or provide a separately verified state-aware migration plan");
        }
        long containers = preview.blockEntitiesEncountered().stream()
                .filter(DeploymentBlockObservation::containerHasContents).count();
        if (containers > 0) {
            add(findings, DeploymentRiskCategory.CONTAINER_CONTENTS, RiskSeverity.CRITICAL,
                    Long.toString(containers),
                    "Non-empty container state could be lost or duplicated",
                    "Exclude the containers and verify inventory-preserving backup and restore");
        }
        if (!context.fluidPositions().isEmpty()) {
            add(findings, DeploymentRiskCategory.FLUID, RiskSeverity.HIGH,
                    Integer.toString(context.fluidPositions().size()),
                    "Fluid cells intersect or border the affected region",
                    "Contain fluid flow and verify a bounded rollback snapshot");
        }
        if (!context.fireOrLavaPositions().isEmpty()) {
            add(findings, DeploymentRiskCategory.FIRE_OR_LAVA, RiskSeverity.CRITICAL,
                    Integer.toString(context.fireOrLavaPositions().size()),
                    "Fire or lava can cause uncontrolled secondary world changes",
                    "Remove the hazard in a separately authorized operation");
        }
        if (context.explosionCapable()) {
            add(findings, DeploymentRiskCategory.EXPLOSION, RiskSeverity.CRITICAL,
                    "explosion-capable",
                    "The proposed topology or surroundings contain explosion potential",
                    "Use a non-explosive design and repeat the complete dry-run");
        }
        if (context.expectedDroppedEntities() > 0) {
            add(findings, DeploymentRiskCategory.DROPPED_ENTITY, RiskSeverity.MEDIUM,
                    Integer.toString(context.expectedDroppedEntities()),
                    "The change may create dropped item entities",
                    "Provide bounded collection and duplication-safe accounting");
        }
        if (crossesChunkBoundary(preview.affectedBounds())) {
            add(findings, DeploymentRiskCategory.CHUNK_BOUNDARY, RiskSeverity.LOW,
                    preview.affectedBounds().toString(),
                    "The affected bounds cross at least one chunk boundary",
                    "Authorize and keep every intersected chunk loaded only during execution");
        }
        if (!context.dimensionAllowed()) {
            add(findings, DeploymentRiskCategory.DIMENSION, RiskSeverity.CRITICAL,
                    context.dimensionId().toString(),
                    "The target dimension is outside the deployment policy",
                    "Select an allowlisted dimension and regenerate the preview");
        }
        if (preview.stressDemand() > context.availableStressCapacity()) {
            add(findings, DeploymentRiskCategory.POWER_OVERLOAD, RiskSeverity.CRITICAL,
                    preview.stressDemand() + "/" + context.availableStressCapacity(),
                    "Rotational stress demand exceeds observed capacity",
                    "Increase independently verified capacity or reduce demand");
        }
        if (context.logisticsCongestion()) {
            add(findings, DeploymentRiskCategory.LOGISTICS_CONGESTION, RiskSeverity.HIGH,
                    Integer.toString(preview.itemRoutes().size()),
                    "At least one item route has a deterministic congestion signal",
                    "Increase route capacity or reduce required throughput");
        }
        List<String> shortages = shortages(preview.requiredInputResources(), context.availableResources());
        if (!shortages.isEmpty()) {
            add(findings, DeploymentRiskCategory.RESOURCE_SHORTAGE, RiskSeverity.CRITICAL,
                    String.join(",", shortages),
                    "Read-only resource evidence is below the required input quantities",
                    "Provide the exact missing resources without automatic formal-world withdrawal");
        }
        if (preview.rollbackClassification() != RollbackClassification.FULLY_REVERSIBLE) {
            RiskSeverity severity = preview.rollbackClassification()
                    == RollbackClassification.REVERSIBLE_WITH_RESOURCE_LOSS
                    ? RiskSeverity.HIGH : RiskSeverity.CRITICAL;
            add(findings, DeploymentRiskCategory.INCOMPLETE_ROLLBACK, severity,
                    preview.rollbackClassification().name(),
                    "The preview cannot guarantee complete state restoration",
                    "Require a verified backup/restore path or redesign for full reversibility");
        }
        if (!context.reloadRecoverySafe()) {
            add(findings, DeploymentRiskCategory.RELOAD_RECOVERY, RiskSeverity.HIGH,
                    "reload-unsafe",
                    "The preview lacks safe bounded reload recovery evidence",
                    "Prove exact rescan and cursor recovery before readiness");
        }
        if (context.multiNodeResourceHistory()) {
            add(findings, DeploymentRiskCategory.MULTI_NODE_RESOURCE_HISTORY, RiskSeverity.HIGH,
                    "resource-history-present",
                    "Multi-node resource history can make replay or compensation ambiguous",
                    "Use a fresh session boundary with explicit resource accounting");
        }
        if (context.permissionRisk() != DeploymentPermissionRisk.ALLOWED) {
            add(findings, DeploymentRiskCategory.CLAIM_PERMISSION_UNKNOWN, RiskSeverity.CRITICAL,
                    context.permissionRisk().name(),
                    "Claim or permission evidence does not explicitly allow the operation",
                    "Obtain an authoritative ALLOWED result for the exact region and operations");
        }
        if (!context.backupAvailable()) {
            add(findings, DeploymentRiskCategory.BACKUP_UNAVAILABLE, RiskSeverity.CRITICAL,
                    "backup-unavailable",
                    "No verified usable backup is available",
                    "Complete and verify a bounded backup before approval");
        }
        if (!context.snapshotFresh()) {
            add(findings, DeploymentRiskCategory.SNAPSHOT_STALE, RiskSeverity.CRITICAL,
                    preview.worldSnapshotFingerprint(),
                    "The world snapshot is not current",
                    "Capture a fresh authorized read-only snapshot and regenerate the preview");
        }
        if (!preview.runtimeFingerprint().equals(context.currentRuntimeFingerprint())) {
            add(findings, DeploymentRiskCategory.RUNTIME_FINGERPRINT_CHANGED, RiskSeverity.CRITICAL,
                    preview.runtimeFingerprint() + "->" + context.currentRuntimeFingerprint(),
                    "Runtime identity changed after preview generation",
                    "Rebuild the verified plan and preview against the current runtime");
        }

        RiskSeverity highest = findings.stream().map(DeploymentRiskFinding::severity)
                .max(java.util.Comparator.comparingInt(Enum::ordinal)).orElse(RiskSeverity.INFO);
        boolean blocked = findings.stream().anyMatch(DeploymentRiskFinding::blocksApproval);
        return new DeploymentRiskAssessment(preview.previewHash(), findings, highest, blocked);
    }

    private static void add(
            List<DeploymentRiskFinding> findings,
            DeploymentRiskCategory category,
            RiskSeverity severity,
            String subject,
            String reason,
            String mitigation) {
        findings.add(new DeploymentRiskFinding(
                category, severity, subject, reason, mitigation, severity == RiskSeverity.CRITICAL));
    }

    private static List<String> shortages(
            Map<ResourceId, Long> required,
            Map<ResourceId, Long> available) {
        List<String> values = new ArrayList<>();
        required.entrySet().stream().sorted(Map.Entry.comparingByKey(
                java.util.Comparator.comparing(ResourceId::toString))).forEach(entry -> {
            long found = available.getOrDefault(entry.getKey(), 0L);
            if (found < entry.getValue()) {
                values.add(entry.getKey() + "=" + found + "/" + entry.getValue());
            }
        });
        return List.copyOf(values);
    }

    private static boolean crossesChunkBoundary(DeploymentBoundingBox bounds) {
        return Math.floorDiv(bounds.minimum().x(), 16) != Math.floorDiv(bounds.maximum().x(), 16)
                || Math.floorDiv(bounds.minimum().z(), 16) != Math.floorDiv(bounds.maximum().z(), 16);
    }
}
