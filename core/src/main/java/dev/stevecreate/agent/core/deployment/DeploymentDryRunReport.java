package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.QuarterTurn;
import java.util.List;
import java.util.Objects;

/** Immutable PW-12 command artifact. It records a dry-run and no execution capability. */
public record DeploymentDryRunReport(
        DeploymentPreview preview,
        DeploymentRiskAssessment riskAssessment,
        DeploymentBudget budget,
        QuarterTurn orientation,
        List<String> missingAuthorization,
        List<String> missingBackup,
        List<String> readinessBlockers,
        boolean dryRun,
        boolean formalWorldExecutable,
        boolean worldMutation,
        boolean sessionCreated,
        boolean playerItemsConsumed,
        boolean machineStarted,
        boolean llmCalled,
        boolean freeTextCoordinatesAccepted) {
    public DeploymentDryRunReport {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(riskAssessment, "riskAssessment");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(orientation, "orientation");
        missingAuthorization = values(missingAuthorization, "missingAuthorization");
        missingBackup = values(missingBackup, "missingBackup");
        readinessBlockers = values(readinessBlockers, "readinessBlockers");
        if (!preview.previewHash().equals(riskAssessment.previewHash())
                || !preview.previewHash().equals(budget.previewHash())) {
            throw new IllegalArgumentException("dry-run artifacts do not share one preview hash");
        }
        if (!preview.orientations().contains(orientation)) {
            throw new IllegalArgumentException("orientation is absent from physical preview");
        }
        if (!dryRun || formalWorldExecutable || worldMutation || sessionCreated
                || playerItemsConsumed || machineStarted || llmCalled
                || freeTextCoordinatesAccepted) {
            throw new IllegalArgumentException("PW-12 report cannot carry execution authority");
        }
        if (missingAuthorization.isEmpty() || missingBackup.isEmpty()
                || readinessBlockers.isEmpty()) {
            throw new IllegalArgumentException("read-only readiness must expose missing gates");
        }
    }

    private static List<String> values(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        List<String> copy = List.copyOf(values);
        if (copy.size() > 64 || copy.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 1_024)
                || copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return copy;
    }
}
