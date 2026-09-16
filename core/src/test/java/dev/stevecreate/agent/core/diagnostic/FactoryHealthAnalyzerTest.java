package dev.stevecreate.agent.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactoryHealthAnalyzerTest {
    private static final ResourceId SUBJECT = ResourceId.parse("test:factory");

    @Test
    void sixInjectedFaultsProduceSixStableEvidenceBackedDiagnoses() {
        FactoryHealthReport report = new FactoryHealthAnalyzer().analyze(snapshot(
                FactoryObservationState.FAULT));

        assertThat(report.status()).isEqualTo(FactoryHealthStatus.FAULTED);
        assertThat(report.findings()).extracting(FactoryDiagnosis::code).containsExactly(
                FactoryFaultCode.MATERIAL_SHORTAGE,
                FactoryFaultCode.OUTPUT_CAPACITY_EXHAUSTED,
                FactoryFaultCode.LOGISTICS_ROUTE_BLOCKED,
                FactoryFaultCode.ENERGY_INSUFFICIENT,
                FactoryFaultCode.STRUCTURE_OR_ORIENTATION_MISMATCH,
                FactoryFaultCode.RESERVATION_DRIFT);
        assertThat(report.findings()).allSatisfy(finding -> {
            assertThat(finding.source()).isEqualTo(FactoryEvidenceSource.LIVE_WORLD);
            assertThat(finding.metrics()).containsEntry("observed", 0L)
                    .containsEntry("required", 1L);
        });
        assertThat(report.observations()).hasSize(FactoryHealthCategory.values().length)
                .allMatch(value -> value.state() == FactoryObservationState.FAULT);
        assertThat(report.worldMutations()).isZero();
        assertThat(report.automaticRepairAuthorized()).isFalse();
    }

    @Test
    void missingProbesRemainInconclusiveInsteadOfLookingHealthy() {
        FactoryHealthReport report = new FactoryHealthAnalyzer().analyze(snapshot(
                FactoryObservationState.UNKNOWN));

        assertThat(report.status()).isEqualTo(FactoryHealthStatus.INCONCLUSIVE);
        assertThat(report.findings()).isEmpty();
        assertThat(report.unknownCategories())
                .containsExactlyInAnyOrder(FactoryHealthCategory.values());
    }

    @Test
    void allSixPositiveObservationsAreHealthyButStillReadOnly() {
        FactoryHealthReport report = new FactoryHealthAnalyzer().analyze(snapshot(
                FactoryObservationState.HEALTHY));

        assertThat(report.status()).isEqualTo(FactoryHealthStatus.HEALTHY);
        assertThat(report.findings()).isEmpty();
        assertThat(report.unknownCategories()).isEmpty();
        assertThat(report.worldMutations()).isZero();
        assertThat(report.automaticRepairAuthorized()).isFalse();
    }

    @Test
    void snapshotRejectsMissingOrDuplicatedCategories() {
        List<FactoryHealthObservation> incomplete = new ArrayList<>(
                snapshot(FactoryObservationState.HEALTHY).observations());
        incomplete.remove(incomplete.size() - 1);
        assertThatThrownBy(() -> new FactoryHealthSnapshot(SUBJECT, 1, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all six");

        List<FactoryHealthObservation> duplicate = new ArrayList<>(
                snapshot(FactoryObservationState.HEALTHY).observations());
        duplicate.set(duplicate.size() - 1, duplicate.get(0));
        assertThatThrownBy(() -> new FactoryHealthSnapshot(SUBJECT, 1, duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void aReportCannotClaimRepairOrWorldMutationAuthority() {
        assertThatThrownBy(() -> new FactoryHealthReport(SUBJECT, 1,
                FactoryHealthStatus.HEALTHY, List.of(), Set.of(),
                snapshot(FactoryObservationState.HEALTHY).observations(), 1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutation authority");
        assertThatThrownBy(() -> new FactoryHealthReport(SUBJECT, 1,
                FactoryHealthStatus.HEALTHY, List.of(), Set.of(),
                snapshot(FactoryObservationState.HEALTHY).observations(), 0, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutation authority");
    }

    @Test
    void aReportCannotDetachItsSummaryFromItsSixObservations() {
        assertThatThrownBy(() -> new FactoryHealthReport(SUBJECT, 1,
                FactoryHealthStatus.HEALTHY, List.of(), Set.of(),
                snapshot(FactoryObservationState.UNKNOWN).observations(), 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary and observations");
    }

    private static FactoryHealthSnapshot snapshot(FactoryObservationState state) {
        List<FactoryHealthObservation> observations = new ArrayList<>();
        for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
            observations.add(new FactoryHealthObservation(category, state,
                    state == FactoryObservationState.UNKNOWN
                            ? FactoryEvidenceSource.NO_PROBE : FactoryEvidenceSource.LIVE_WORLD,
                    state == FactoryObservationState.UNKNOWN ? "PROBE_NOT_AVAILABLE" : "FIXTURE",
                    state == FactoryObservationState.UNKNOWN ? Map.of()
                            : Map.of("required", 1L, "observed", state
                                    == FactoryObservationState.HEALTHY ? 1L : 0L),
                    Set.of(ResourceId.parse("test:resource")),
                    state == FactoryObservationState.UNKNOWN
                            ? "No read-only probe is available" : "Fixture observation"));
        }
        return new FactoryHealthSnapshot(SUBJECT, 1, observations);
    }
}
