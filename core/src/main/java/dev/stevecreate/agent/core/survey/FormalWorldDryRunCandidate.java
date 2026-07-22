package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.DeploymentDryRunReport;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One reviewed formal-world dry-run artifact; never an execution candidate. */
public record FormalWorldDryRunCandidate(
        CandidateIndustrialZone candidateZone,
        ResourceId dimension,
        FormalDryRunKind kind,
        ResourceId target,
        long quantity,
        DeploymentDryRunReport report,
        boolean inventoryContentsRead,
        boolean resourceOperationPerformed,
        boolean approvalCreated,
        boolean executionAllowed) {
    public FormalWorldDryRunCandidate {
        Objects.requireNonNull(candidateZone, "candidateZone");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(report, "report");
        if (quantity < 1 || !target.equals(report.preview().target()) || quantity != report.preview().quantity()
                || report.preview().environmentClassification() != WorldEnvironmentType.FORMAL_PLAYER_WORLD
                || candidateZone.status() != CandidateZoneStatus.PENDING_USER_SELECTION
                || !candidateZone.dimension().equals(dimension)
                || !candidateZone.boundingBox().contains(report.preview().anchor())
                || !report.preview().hasValidHash()
                || report.formalWorldExecutable() || report.worldMutation() || report.sessionCreated()
                || report.playerItemsConsumed() || report.machineStarted() || report.llmCalled()
                || report.freeTextCoordinatesAccepted() || inventoryContentsRead
                || resourceOperationPerformed || approvalCreated || executionAllowed
                || report.readinessBlockers().stream().noneMatch(
                        "FORMAL_WORLD_EXECUTION_FORBIDDEN"::equals)) {
            throw new IllegalArgumentException("formal dry-run candidate carries invalid or executable authority");
        }
        validateKind(kind, target, quantity, report);
    }

    private static void validateKind(
            FormalDryRunKind kind, ResourceId target, long quantity, DeploymentDryRunReport report) {
        boolean milling = report.preview().recipeIds().stream()
                .anyMatch(id -> id.path().contains("milling"));
        boolean pressing = report.preview().recipeIds().stream()
                .anyMatch(id -> id.path().contains("pressing"));
        switch (kind) {
            case GRAVEL_MILLING -> {
                if (!target.equals(ResourceId.parse("minecraft:gravel")) || quantity != 3 || !milling) {
                    throw new IllegalArgumentException("gravel dry-run binding is invalid");
                }
            }
            case IRON_SHEET_PRESSING -> {
                if (!target.equals(ResourceId.parse("create:iron_sheet")) || quantity != 2 || !pressing) {
                    throw new IllegalArgumentException("iron-sheet dry-run binding is invalid");
                }
            }
            case PACK_CUSTOM_MILLING -> {
                if (!milling || report.preview().recipeIds().contains(
                        ResourceId.parse("create:milling/cobblestone"))) {
                    throw new IllegalArgumentException("pack custom milling recipe is not distinct");
                }
            }
            case PACK_CUSTOM_PRESSING -> {
                if (!pressing || report.preview().recipeIds().contains(
                        ResourceId.parse("create:pressing/iron_ingot"))) {
                    throw new IllegalArgumentException("pack custom pressing recipe is not distinct");
                }
            }
        }
    }
}
