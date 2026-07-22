package dev.stevecreate.agent.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.kinetics.ComponentId;
import dev.stevecreate.agent.core.kinetics.KineticNetworkTelemetry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KineticNetworkAnalyzerTest {
    private final KineticNetworkAnalyzer analyzer = new KineticNetworkAnalyzer();

    @Test
    void reportsNoPowerWhenLoadedNetworkIsStopped() {
        DiagnosticReport report = analyzer.analyze(List.of(
                new KineticNetworkTelemetry("mill", Set.of(new ComponentId("millstone")), 0, true, 128, 4)));
        assertThat(report.findings()).extracting(DiagnosticFinding::code).containsExactly(FaultCode.NO_POWER);
    }

    @Test
    void overloadTakesPriorityOverNoPower() {
        DiagnosticReport report = analyzer.analyze(List.of(
                new KineticNetworkTelemetry("press", Set.of(new ComponentId("press")), 0, true, 8, 16)));
        assertThat(report.findings()).extracting(DiagnosticFinding::code)
                .containsExactly(FaultCode.STRESS_OVERLOAD);
        assertThat(report.findings().get(0).evidence()).containsEntry("capacity", 8.0).containsEntry("load", 16.0);
    }

    @Test
    void acceptsHealthyNetwork() {
        DiagnosticReport report = analyzer.analyze(List.of(
                new KineticNetworkTelemetry("waterwheel", Set.of(new ComponentId("wheel")), 16, true, 128, 4)));
        assertThat(report.healthy()).isTrue();
    }

    @Test
    void doesNotInventOverloadWhenStressIsDisabled() {
        DiagnosticReport report = analyzer.analyze(List.of(
                new KineticNetworkTelemetry("disabled", Set.of(new ComponentId("motor")), 16, false, 8, 16)));
        assertThat(report.healthy()).isTrue();
    }
}
