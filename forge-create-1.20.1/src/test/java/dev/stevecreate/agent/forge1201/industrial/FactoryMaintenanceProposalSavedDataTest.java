package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.diagnostic.FactoryFaultCode;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthCategory;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposal;
import dev.stevecreate.agent.core.diagnostic.FactoryRecommendedAction;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class FactoryMaintenanceProposalSavedDataTest {
    private static final UUID PLAYER =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void roundTripsAnAuthorityFreePlayerBoundProposal() {
        FactoryMaintenanceProposalSavedData data = new FactoryMaintenanceProposalSavedData();
        FactoryMaintenanceProposal proposal = proposal();
        data.put(PLAYER, proposal);

        FactoryMaintenanceProposal restored = FactoryMaintenanceProposalSavedData
                .load(data.save(new CompoundTag())).proposal(PLAYER).orElseThrow();

        assertThat(restored).isEqualTo(proposal);
        assertThat(restored.worldMutationAuthorized()).isFalse();
        assertThat(restored.inventoryAccessAuthorized()).isFalse();
        assertThat(restored.executionAuthorized()).isFalse();
    }

    @Test
    void tamperedAuthorityDuplicatePlayerOrHashDropsEveryProposal() {
        FactoryMaintenanceProposalSavedData data = new FactoryMaintenanceProposalSavedData();
        data.put(PLAYER, proposal());
        CompoundTag original = data.save(new CompoundTag());

        CompoundTag authority = original.copy();
        authority.getList("Proposals", Tag.TAG_COMPOUND).getCompound(0)
                .putBoolean("WorldMutationAuthorized", true);
        assertThat(FactoryMaintenanceProposalSavedData.load(authority).proposal(PLAYER)).isEmpty();

        CompoundTag hash = original.copy();
        hash.getList("Proposals", Tag.TAG_COMPOUND).getCompound(0)
                .putString("DiagnosticHash", "invalid");
        assertThat(FactoryMaintenanceProposalSavedData.load(hash).proposal(PLAYER)).isEmpty();

        CompoundTag duplicate = original.copy();
        duplicate.getList("Proposals", Tag.TAG_COMPOUND).add(
                duplicate.getList("Proposals", Tag.TAG_COMPOUND).getCompound(0).copy());
        assertThat(FactoryMaintenanceProposalSavedData.load(duplicate).proposal(PLAYER)).isEmpty();
    }

    @Test
    void dismissRemovesOnlyTheNamedPlayersProposal() {
        UUID other = UUID.fromString("55555555-5555-5555-5555-555555555555");
        FactoryMaintenanceProposalSavedData data = new FactoryMaintenanceProposalSavedData();
        data.put(PLAYER, proposal());
        data.put(other, proposal());

        data.remove(PLAYER);

        assertThat(data.proposal(PLAYER)).isEmpty();
        assertThat(data.proposal(other)).contains(proposal());
    }

    private static FactoryMaintenanceProposal proposal() {
        return new FactoryMaintenanceProposal(
                ResourceId.parse("steve_industrial:maintenance/" + "1".repeat(32)),
                ResourceId.parse("steve_industrial:ie_order/" + "2".repeat(32)),
                100, 1_300, FactoryFaultCode.ENERGY_INSUFFICIENT,
                FactoryHealthCategory.ENERGY_SUPPLY, FactoryRecommendedAction.RESTORE_POWER,
                "PLAN_FE_POWER_INSUFFICIENT", "3".repeat(64),
                FactoryMaintenanceProposal.State.AWAITING_EXPLICIT_APPROVAL,
                false, false, false);
    }
}
