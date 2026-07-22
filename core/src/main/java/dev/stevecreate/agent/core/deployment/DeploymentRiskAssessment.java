package dev.stevecreate.agent.core.deployment;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public record DeploymentRiskAssessment(
        String previewHash,
        List<DeploymentRiskFinding> findings,
        RiskSeverity highestSeverity,
        boolean approvalBlocked) {
    public DeploymentRiskAssessment {
        Objects.requireNonNull(previewHash, "previewHash");
        if (!previewHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("previewHash must be lowercase SHA-256 hex");
        }
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        EnumSet<DeploymentRiskCategory> categories = EnumSet.noneOf(DeploymentRiskCategory.class);
        DeploymentRiskCategory previous = null;
        RiskSeverity computed = RiskSeverity.INFO;
        boolean blocked = false;
        for (DeploymentRiskFinding finding : findings) {
            Objects.requireNonNull(finding, "finding");
            if (!categories.add(finding.category())) {
                throw new IllegalArgumentException("duplicate risk category " + finding.category());
            }
            if (previous != null && previous.ordinal() >= finding.category().ordinal()) {
                throw new IllegalArgumentException("risk findings are not canonically ordered");
            }
            previous = finding.category();
            if (finding.severity().ordinal() > computed.ordinal()) computed = finding.severity();
            blocked |= finding.blocksApproval();
        }
        Objects.requireNonNull(highestSeverity, "highestSeverity");
        if (highestSeverity != computed || approvalBlocked != blocked) {
            throw new IllegalArgumentException("risk summary disagrees with findings");
        }
    }
}
