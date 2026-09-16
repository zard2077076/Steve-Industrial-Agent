package dev.stevecreate.agent.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FactoryMaintenanceApprovalLedgerTest {
    private static final ResourceId SUBJECT = ResourceId.parse("test:factory");
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final FactoryMaintenanceProposalService proposals =
            new FactoryMaintenanceProposalService();

    @Test
    void exactPlayerApprovalIsConsumedOnceOnlyAfterMatchingFreshDiagnosis() {
        FactoryHealthReport original = report(1_000, "PLAN_FE_CONNECTION_MISMATCH");
        FactoryMaintenanceProposal proposal = proposals.propose(original, 3_000)
                .proposal().orElseThrow();
        FactoryMaintenanceApprovalLedger ledger = new FactoryMaintenanceApprovalLedger();

        assertThat(ledger.approve(proposal, PLAYER, 1_100).code())
                .isEqualTo("MAINTENANCE_APPROVED");
        FactoryHealthReport fresh = report(1_200, "PLAN_FE_CONNECTION_MISMATCH");
        var consumed = ledger.consume(proposal, fresh, PLAYER, 1_201);

        assertThat(consumed.success()).isTrue();
        assertThat(consumed.approval().orElseThrow().state())
                .isEqualTo(FactoryMaintenanceApproval.State.CONSUMED);
        assertThat(ledger.consume(proposal, fresh, PLAYER, 1_202).code())
                .isEqualTo("MAINTENANCE_APPROVAL_ALREADY_CONSUMED");
    }

    @Test
    void staleChangedWrongPlayerAndExpiredEvidenceAllRefuseWithoutConsumption() {
        FactoryHealthReport original = report(1_000, "PLAN_FE_CONNECTION_MISMATCH");
        FactoryMaintenanceProposal proposal = proposals.propose(original, 3_000)
                .proposal().orElseThrow();
        FactoryMaintenanceApprovalLedger ledger = new FactoryMaintenanceApprovalLedger();
        ledger.approve(proposal, PLAYER, 1_100);

        assertThat(ledger.consume(proposal, report(1_200, "PLAN_FE_POWER_INSUFFICIENT"),
                PLAYER, 1_201).code()).isEqualTo("MAINTENANCE_FRESH_DIAGNOSIS_MISMATCH");
        assertThat(ledger.consume(proposal, report(1_200, "PLAN_FE_CONNECTION_MISMATCH"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 1_201).code())
                .isEqualTo("MAINTENANCE_APPROVAL_SCOPE_MISMATCH");
        assertThat(ledger.consume(proposal, original, PLAYER, 1_201).code())
                .isEqualTo("MAINTENANCE_APPROVAL_OR_DIAGNOSIS_EXPIRED");
        assertThat(ledger.consume(proposal, report(3_001, "PLAN_FE_CONNECTION_MISMATCH"),
                PLAYER, 3_001).code()).isEqualTo("MAINTENANCE_APPROVAL_OR_DIAGNOSIS_EXPIRED");
        assertThat(ledger.consume(proposal, report(3_000, "PLAN_FE_CONNECTION_MISMATCH"),
                PLAYER, 3_000).code()).isEqualTo("MAINTENANCE_APPROVAL_OR_DIAGNOSIS_EXPIRED");
        assertThat(ledger.approvals().get(proposal.proposalId()).state())
                .isEqualTo(FactoryMaintenanceApproval.State.APPROVED);
    }

    @Test
    void approvalIdentityCannotBeReassignedOrRecreatedAfterConsumption() {
        FactoryHealthReport report = report(1_000, "PLAN_FE_CONNECTION_MISMATCH");
        FactoryMaintenanceProposal proposal = proposals.propose(report, 3_000)
                .proposal().orElseThrow();
        FactoryMaintenanceApprovalLedger ledger = new FactoryMaintenanceApprovalLedger();
        assertThat(ledger.approve(proposal, PLAYER, 1_100).code())
                .isEqualTo("MAINTENANCE_APPROVED");
        assertThat(ledger.approve(proposal, PLAYER, 1_101).code())
                .isEqualTo("MAINTENANCE_APPROVAL_ALREADY_RECORDED");
        assertThat(ledger.approve(proposal,
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 1_101).code())
                .isEqualTo("MAINTENANCE_APPROVAL_IDENTITY_REUSED");
        ledger.consume(proposal, report(1_200, "PLAN_FE_CONNECTION_MISMATCH"), PLAYER, 1_201);
        assertThat(ledger.approve(proposal, PLAYER, 1_202).code())
                .isEqualTo("MAINTENANCE_APPROVAL_IDENTITY_REUSED");
    }

    @Test
    void snapshotRestoresConsumedAuthorityAndRejectsMissingOrDuplicateJournalEvidence() {
        FactoryHealthReport report = report(1_000, "PLAN_FE_CONNECTION_MISMATCH");
        FactoryMaintenanceProposal proposal = proposals.propose(report, 3_000)
                .proposal().orElseThrow();
        FactoryMaintenanceApprovalLedger ledger = new FactoryMaintenanceApprovalLedger();
        ledger.approve(proposal, PLAYER, 1_100);
        ledger.consume(proposal, report(1_200, "PLAN_FE_CONNECTION_MISMATCH"), PLAYER, 1_201);

        var snapshot = ledger.snapshot();
        var restored = FactoryMaintenanceApprovalLedger.restore(snapshot);
        assertThat(restored.approvals().get(proposal.proposalId()).state())
                .isEqualTo(FactoryMaintenanceApproval.State.CONSUMED);
        assertThat(restored.consume(proposal, report(1_300, "PLAN_FE_CONNECTION_MISMATCH"),
                PLAYER, 1_301).code()).isEqualTo("MAINTENANCE_APPROVAL_ALREADY_CONSUMED");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                FactoryMaintenanceApprovalLedger.restore(
                        new FactoryMaintenanceApprovalLedger.Snapshot(
                                snapshot.approvals(), List.of(), snapshot.generation()))))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> duplicate = new ArrayList<>(snapshot.journal());
        duplicate.add(snapshot.journal().get(1));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                FactoryMaintenanceApprovalLedger.restore(
                        new FactoryMaintenanceApprovalLedger.Snapshot(
                                snapshot.approvals(), duplicate, snapshot.generation() + 1))))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> wrongPlayer = new ArrayList<>(snapshot.journal());
        wrongPlayer.set(0, wrongPlayer.get(0).replace(PLAYER.toString(),
                "22222222-2222-2222-2222-222222222222"));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                FactoryMaintenanceApprovalLedger.restore(
                        new FactoryMaintenanceApprovalLedger.Snapshot(
                                snapshot.approvals(), wrongPlayer, snapshot.generation()))))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> wrongHash = new ArrayList<>(snapshot.journal());
        wrongHash.set(0, wrongHash.get(0).substring(0, wrongHash.get(0).length() - 1) + "0");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                FactoryMaintenanceApprovalLedger.restore(
                        new FactoryMaintenanceApprovalLedger.Snapshot(
                                snapshot.approvals(), wrongHash, snapshot.generation()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FactoryHealthReport report(long tick, String energyCode) {
        List<FactoryHealthObservation> observations = new ArrayList<>();
        for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
            boolean energy = category == FactoryHealthCategory.ENERGY_SUPPLY;
            observations.add(new FactoryHealthObservation(category,
                    energy ? FactoryObservationState.FAULT : FactoryObservationState.HEALTHY,
                    FactoryEvidenceSource.LIVE_WORLD, energy ? energyCode : "PLAN_PROBE_HEALTHY",
                    Map.of("observed", energy ? 0L : 1L, "required", 1L),
                    Set.of(ResourceId.parse("test:resource")),
                    energy ? "Exact plan-owned FE evidence is faulted"
                            : "Exact plan-owned evidence is healthy"));
        }
        return new FactoryHealthAnalyzer().analyze(
                new FactoryHealthSnapshot(SUBJECT, tick, observations));
    }
}
