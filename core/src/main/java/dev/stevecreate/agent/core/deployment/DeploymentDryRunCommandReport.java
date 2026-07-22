package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

public record DeploymentDryRunCommandReport(List<String> userLines, String structuredLog) {
    public DeploymentDryRunCommandReport {
        userLines = List.copyOf(Objects.requireNonNull(userLines, "userLines"));
        if (userLines.isEmpty() || userLines.stream().anyMatch(line ->
                line == null || line.isBlank() || line.length() > 16_384)) {
            throw new IllegalArgumentException("userLines are invalid");
        }
        Objects.requireNonNull(structuredLog, "structuredLog");
        if (structuredLog.isBlank() || structuredLog.length() > 65_536) {
            throw new IllegalArgumentException("structuredLog is invalid");
        }
    }
}
