package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.kinetics.ComponentId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Machine-readable evidence used by recovery code and separately rendered for players. */
public record DiagnosticFinding(
        FaultCode code,
        Severity severity,
        Set<ComponentId> affected,
        Map<String, Double> evidence,
        String message) {
    public DiagnosticFinding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        affected = Set.copyOf(Objects.requireNonNull(affected, "affected"));
        evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(message, "message");
    }
}

