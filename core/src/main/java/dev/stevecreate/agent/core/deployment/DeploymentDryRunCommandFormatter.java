package dev.stevecreate.agent.core.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deterministic bounded presentation for the four PW-12 read-only command views. */
public final class DeploymentDryRunCommandFormatter {
    public DeploymentDryRunCommandReport format(
            DeploymentDryRunSection section,
            DeploymentDryRunReport report) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(report, "report");
        DeploymentPreview preview = report.preview();
        List<String> lines = new ArrayList<>();
        lines.add("Deploy " + section.name().toLowerCase()
                + " target=" + preview.target() + " quantity=" + preview.quantity()
                + " previewHash=" + preview.previewHash()
                + " orientation=" + report.orientation()
                + " environment=" + preview.environmentClassification()
                + " dryRun=true readiness=BLOCKED formalWorldExecutable=false");
        switch (section) {
            case PREVIEW -> {
                lines.add("bounds=" + bounds(preview.affectedBounds())
                        + " affectedBlocks=" + preview.journalEstimate()
                        + " materials=" + map(preview.materialBillOfMaterials()));
                lines.add("powerDemand=" + report.budget().rotationalStressDemand()
                        + " powerMargin=" + report.budget().powerCapacityMargin()
                        + " risk=" + report.riskAssessment().highestSeverity()
                        + " missingAuthorization=" + report.missingAuthorization()
                        + " missingBackup=" + report.missingBackup());
            }
            case RISKS -> lines.add("risk=" + report.riskAssessment().highestSeverity()
                    + " approvalBlocked=" + report.riskAssessment().approvalBlocked()
                    + " findings=" + report.riskAssessment().findings());
            case BUDGET -> {
                lines.add("materials raw=" + map(report.budget().rawMaterialRequirements())
                        + " intermediate=" + map(report.budget().intermediateProductRequirements())
                        + " construction=" + map(report.budget().constructionBlockRequirements())
                        + " output=" + map(report.budget().expectedOutput()));
                lines.add("power demand=" + report.budget().rotationalStressDemand()
                        + " margin=" + report.budget().powerCapacityMargin()
                        + " policyViolations=" + report.budget().policyViolations()
                        + " resourceReservation=false");
            }
            case READINESS -> lines.add("missingAuthorization=" + report.missingAuthorization()
                    + " missingBackup=" + report.missingBackup()
                    + " blockers=" + report.readinessBlockers()
                    + " deploymentReadyPlanCreated=false");
        }
        lines.add("sideEffects worldMutation=false sessionCreated=false playerItemsConsumed=false"
                + " machineStarted=false llmCalled=false freeTextCoordinatesAccepted=false");
        String structured = "deploymentDryRun={status=PASS,section=" + section
                + ",target=" + preview.target() + ",quantity=" + preview.quantity()
                + ",previewHash=" + preview.previewHash()
                + ",orientation=" + report.orientation()
                + ",environment=" + preview.environmentClassification()
                + ",bounds=" + bounds(preview.affectedBounds())
                + ",materials=" + map(preview.materialBillOfMaterials())
                + ",powerDemand=" + report.budget().rotationalStressDemand()
                + ",powerMargin=" + report.budget().powerCapacityMargin()
                + ",risk=" + report.riskAssessment().highestSeverity()
                + ",missingAuthorization=" + report.missingAuthorization()
                + ",missingBackup=" + report.missingBackup()
                + ",readiness=BLOCKED,dryRun=true,formalWorldExecutable=false"
                + ",worldMutation=false,sessionCreated=false,playerItemsConsumed=false"
                + ",machineStarted=false,llmCalled=false,freeTextCoordinatesAccepted=false}";
        return new DeploymentDryRunCommandReport(lines, structured);
    }

    private static String bounds(DeploymentBoundingBox bounds) {
        return bounds.minimum().x() + "," + bounds.minimum().y() + "," + bounds.minimum().z()
                + ".." + bounds.maximum().x() + "," + bounds.maximum().y() + ","
                + bounds.maximum().z();
    }

    private static String map(Map<?, ?> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) ->
                        left.toString().compareTo(right.toString())))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
