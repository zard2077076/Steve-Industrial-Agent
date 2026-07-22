package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.kinetics.KineticNetworkTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic first-pass diagnosis over adapter-provided Create telemetry. */
public final class KineticNetworkAnalyzer {
    private static final double STOPPED_EPSILON_RPM = 0.0001;

    public DiagnosticReport analyze(List<KineticNetworkTelemetry> networks) {
        List<DiagnosticFinding> findings = new ArrayList<>();
        for (KineticNetworkTelemetry network : List.copyOf(networks)) {
            if (network.stressEnabled() && network.stressLoad() > network.stressCapacity()) {
                findings.add(new DiagnosticFinding(
                        FaultCode.STRESS_OVERLOAD,
                        Severity.ERROR,
                        network.members(),
                        Map.of("capacity", network.stressCapacity(), "load", network.stressLoad()),
                        "Kinetic network " + network.networkId() + " is overstressed"));
            } else if (network.stressLoad() > 0 && Math.abs(network.speedRpm()) < STOPPED_EPSILON_RPM) {
                findings.add(new DiagnosticFinding(
                        FaultCode.NO_POWER,
                        Severity.ERROR,
                        network.members(),
                        Map.of("speedRpm", network.speedRpm(), "load", network.stressLoad()),
                        "Kinetic network " + network.networkId() + " has load but no speed"));
            }
        }
        return new DiagnosticReport(findings);
    }
}
