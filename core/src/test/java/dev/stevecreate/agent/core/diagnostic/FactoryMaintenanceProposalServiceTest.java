package dev.stevecreate.agent.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactoryMaintenanceProposalServiceTest {
    private static final ResourceId SUBJECT = ResourceId.parse("test:factory");
    private final FactoryMaintenanceProposalService service =
            new FactoryMaintenanceProposalService();

    @Test
    void oneConclusiveFaultProducesOnlyAnApprovalPendingDeterministicProposal() {
        FactoryHealthReport report = reportWith(FactoryHealthCategory.ENERGY_SUPPLY, false);

        var first = service.propose(report, 3_000);
        var replay = service.propose(report, 3_000);

        assertThat(first.proposed()).isTrue();
        assertThat(replay.proposal()).isEqualTo(first.proposal());
        FactoryMaintenanceProposal proposal = first.proposal().orElseThrow();
        assertThat(proposal.faultCode()).isEqualTo(FactoryFaultCode.ENERGY_INSUFFICIENT);
        assertThat(proposal.recommendedAction()).isEqualTo(
                FactoryRecommendedAction.RESTORE_POWER);
        assertThat(proposal.state()).isEqualTo(
                FactoryMaintenanceProposal.State.AWAITING_EXPLICIT_APPROVAL);
        assertThat(proposal.diagnosticHash()).matches("[0-9a-f]{64}");
        assertThat(proposal.worldMutationAuthorized()).isFalse();
        assertThat(proposal.inventoryAccessAuthorized()).isFalse();
        assertThat(proposal.executionAuthorized()).isFalse();
    }

    @Test
    void unknownHealthyAndMultiFaultReportsCannotBecomeMaintenanceAuthority() {
        assertThat(service.propose(reportWith(null, false), 3_000).code())
                .isEqualTo("MAINTENANCE_NOT_REQUIRED");
        assertThat(service.propose(reportWith(FactoryHealthCategory.ENERGY_SUPPLY, true), 3_000)
                .code()).isEqualTo("MAINTENANCE_EVIDENCE_INCOMPLETE");

        List<FactoryHealthObservation> observations = observations(
                FactoryHealthCategory.ENERGY_SUPPLY, false);
        observations.set(FactoryHealthCategory.STRUCTURE_INTEGRITY.ordinal(), fault(
                FactoryHealthCategory.STRUCTURE_INTEGRITY));
        FactoryHealthReport multi = new FactoryHealthAnalyzer().analyze(
                new FactoryHealthSnapshot(SUBJECT, 1_000, observations));
        assertThat(service.propose(multi, 3_000).code())
                .isEqualTo("MAINTENANCE_MULTI_FAULT_REPLAN_REQUIRED");
    }

    @Test
    void proposalCannotClaimAuthorityOrOutliveItsBoundedEvidence() {
        FactoryMaintenanceProposal valid = service.propose(
                reportWith(FactoryHealthCategory.ENERGY_SUPPLY, false), 3_000)
                .proposal().orElseThrow();
        assertThatThrownBy(() -> new FactoryMaintenanceProposal(valid.proposalId(),
                valid.subjectId(), valid.diagnosticTick(), valid.expiresAtTick(),
                valid.faultCode(), valid.category(), valid.recommendedAction(),
                valid.evidenceCode(), valid.diagnosticHash(), valid.state(), true, false, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authority");
        assertThat(service.propose(reportWith(FactoryHealthCategory.ENERGY_SUPPLY, false),
                1_000 + FactoryMaintenanceProposal.MAX_LIFETIME_TICKS + 1).code())
                .isEqualTo("MAINTENANCE_EXPIRY_INVALID");
    }

    private static FactoryHealthReport reportWith(
            FactoryHealthCategory fault, boolean leaveUnknown) {
        return new FactoryHealthAnalyzer().analyze(new FactoryHealthSnapshot(
                SUBJECT, 1_000, observations(fault, leaveUnknown)));
    }

    private static List<FactoryHealthObservation> observations(
            FactoryHealthCategory fault, boolean leaveUnknown) {
        List<FactoryHealthObservation> values = new ArrayList<>();
        for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
            if (category == fault) values.add(fault(category));
            else if (leaveUnknown && category == FactoryHealthCategory.OUTPUT_CAPACITY) {
                values.add(new FactoryHealthObservation(category, FactoryObservationState.UNKNOWN,
                        FactoryEvidenceSource.NO_PROBE, "PROBE_NOT_AVAILABLE", Map.of(), Set.of(),
                        "No exact observer is available"));
            } else {
                values.add(new FactoryHealthObservation(category, FactoryObservationState.HEALTHY,
                        FactoryEvidenceSource.LIVE_WORLD, "PLAN_PROBE_HEALTHY",
                        Map.of("observed", 1L, "required", 1L), Set.of(),
                        "Exact plan-owned evidence is healthy"));
            }
        }
        return values;
    }

    private static FactoryHealthObservation fault(FactoryHealthCategory category) {
        return new FactoryHealthObservation(category, FactoryObservationState.FAULT,
                FactoryEvidenceSource.LIVE_WORLD, "PLAN_PROBE_FAULT",
                Map.of("observed", 0L, "required", 1L), Set.of(),
                "Exact plan-owned evidence is faulted");
    }
}
