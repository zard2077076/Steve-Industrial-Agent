package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.diagnostic.FactoryEvidenceSource;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthAnalyzer;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthCategory;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthObservation;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthReport;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthSnapshot;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApproval;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApprovalLedger;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposal;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposalService;
import dev.stevecreate.agent.core.diagnostic.FactoryObservationState;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class FactoryMaintenanceApprovalSavedDataTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void roundTripsConsumedApprovalWithoutReopeningIt() {
        Fixture fixture = fixture();
        FactoryMaintenanceApprovalSavedData data = new FactoryMaintenanceApprovalSavedData();
        data.put(fixture.ledger());

        FactoryMaintenanceApprovalSavedData restored =
                FactoryMaintenanceApprovalSavedData.load(data.save(new CompoundTag()));
        FactoryMaintenanceApproval approval = restored.snapshot().approvals()
                .get(fixture.proposal().proposalId());

        assertThat(approval.state()).isEqualTo(FactoryMaintenanceApproval.State.CONSUMED);
        assertThat(restored.ledger().consume(fixture.proposal(), report(1_300), PLAYER, 1_301)
                .code()).isEqualTo("MAINTENANCE_APPROVAL_ALREADY_CONSUMED");
    }

    @Test
    void tamperedPlayerHashJournalOrDuplicateRowDropsAllAuthority() {
        Fixture fixture = fixture();
        FactoryMaintenanceApprovalSavedData data = new FactoryMaintenanceApprovalSavedData();
        data.put(fixture.ledger());
        CompoundTag original = data.save(new CompoundTag());

        CompoundTag wrongPlayer = original.copy();
        wrongPlayer.getList("Approvals", Tag.TAG_COMPOUND).getCompound(0)
                .putUUID("Player", UUID.fromString("22222222-2222-2222-2222-222222222222"));
        assertThat(FactoryMaintenanceApprovalSavedData.load(wrongPlayer).snapshot().approvals())
                .isEmpty();

        CompoundTag wrongHash = original.copy();
        wrongHash.getList("Approvals", Tag.TAG_COMPOUND).getCompound(0)
                .putString("DiagnosticHash", "0".repeat(64));
        assertThat(FactoryMaintenanceApprovalSavedData.load(wrongHash).snapshot().approvals())
                .isEmpty();

        CompoundTag missingJournal = original.copy();
        missingJournal.put("Journal", new ListTag());
        assertThat(FactoryMaintenanceApprovalSavedData.load(missingJournal).snapshot().approvals())
                .isEmpty();

        CompoundTag duplicate = original.copy();
        ListTag rows = duplicate.getList("Approvals", Tag.TAG_COMPOUND);
        rows.add(rows.getCompound(0).copy());
        duplicate.put("Approvals", rows);
        assertThat(FactoryMaintenanceApprovalSavedData.load(duplicate).snapshot().approvals())
                .isEmpty();
    }

    private static Fixture fixture() {
        FactoryHealthReport original = report(1_000);
        FactoryMaintenanceProposal proposal = new FactoryMaintenanceProposalService()
                .propose(original, 3_000).proposal().orElseThrow();
        FactoryMaintenanceApprovalLedger ledger = new FactoryMaintenanceApprovalLedger();
        ledger.approve(proposal, PLAYER, 1_100);
        ledger.consume(proposal, report(1_200), PLAYER, 1_201);
        return new Fixture(proposal, ledger);
    }

    private static FactoryHealthReport report(long tick) {
        List<FactoryHealthObservation> values = new ArrayList<>();
        for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
            boolean fault = category == FactoryHealthCategory.ENERGY_SUPPLY;
            values.add(new FactoryHealthObservation(category,
                    fault ? FactoryObservationState.FAULT : FactoryObservationState.HEALTHY,
                    FactoryEvidenceSource.LIVE_WORLD,
                    fault ? "PLAN_FE_CONNECTION_MISMATCH" : "PLAN_PROBE_HEALTHY",
                    Map.of("observed", fault ? 0L : 1L, "required", 1L),
                    Set.of(ResourceId.parse("test:resource")),
                    fault ? "Exact FE topology is disconnected" : "Exact evidence is healthy"));
        }
        return new FactoryHealthAnalyzer().analyze(new FactoryHealthSnapshot(
                ResourceId.parse("test:factory"), tick, values));
    }

    private record Fixture(
            FactoryMaintenanceProposal proposal,
            FactoryMaintenanceApprovalLedger ledger) {}
}
