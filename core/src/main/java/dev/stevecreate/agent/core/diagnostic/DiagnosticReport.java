package dev.stevecreate.agent.core.diagnostic;

import java.util.List;
import java.util.Objects;

public record DiagnosticReport(List<DiagnosticFinding> findings) {
    public DiagnosticReport {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
    }

    public boolean healthy() {
        return findings.isEmpty();
    }
}

