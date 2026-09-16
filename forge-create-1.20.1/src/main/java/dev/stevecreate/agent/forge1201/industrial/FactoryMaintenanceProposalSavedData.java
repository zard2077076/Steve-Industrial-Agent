package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.diagnostic.FactoryFaultCode;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthCategory;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposal;
import dev.stevecreate.agent.core.diagnostic.FactoryRecommendedAction;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable player-to-proposal binding; clients never reconstruct maintenance scope. */
public final class FactoryMaintenanceProposalSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_maintenance_proposals";
    public static final int SCHEMA = 1;
    private final Map<UUID, FactoryMaintenanceProposal> proposals = new LinkedHashMap<>();

    public static FactoryMaintenanceProposalSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FactoryMaintenanceProposalSavedData::load,
                FactoryMaintenanceProposalSavedData::new, DATA_NAME);
    }

    public static FactoryMaintenanceProposalSavedData load(CompoundTag root) {
        FactoryMaintenanceProposalSavedData data = new FactoryMaintenanceProposalSavedData();
        try {
            int schema = root.getInt("Schema");
            if (schema < 0 || schema > SCHEMA) throw new IllegalArgumentException("schema");
            for (Tag raw : root.getList("Proposals", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) raw;
                UUID player = row.getUUID("Player");
                FactoryMaintenanceProposal proposal = new FactoryMaintenanceProposal(
                        ResourceId.parse(row.getString("Proposal")),
                        ResourceId.parse(row.getString("Subject")),
                        row.getLong("DiagnosticTick"), row.getLong("ExpiresAt"),
                        FactoryFaultCode.valueOf(row.getString("Fault")),
                        FactoryHealthCategory.valueOf(row.getString("Category")),
                        FactoryRecommendedAction.valueOf(row.getString("Action")),
                        row.getString("Evidence"), row.getString("DiagnosticHash"),
                        FactoryMaintenanceProposal.State.valueOf(row.getString("State")),
                        row.getBoolean("WorldMutationAuthorized"),
                        row.getBoolean("InventoryAccessAuthorized"),
                        row.getBoolean("ExecutionAuthorized"));
                if (data.proposals.putIfAbsent(player, proposal) != null) {
                    throw new IllegalArgumentException("duplicate player proposal");
                }
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            data.proposals.clear();
        }
        return data;
    }

    public Optional<FactoryMaintenanceProposal> proposal(UUID playerId) {
        return Optional.ofNullable(proposals.get(playerId));
    }

    public void put(UUID playerId, FactoryMaintenanceProposal proposal) {
        proposals.put(playerId, proposal);
        setDirty();
    }

    public void remove(UUID playerId) {
        if (proposals.remove(playerId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag rows = new ListTag();
        proposals.entrySet().stream().sorted(Comparator.comparing(value ->
                value.getKey().toString())).forEach(entry -> {
                    FactoryMaintenanceProposal proposal = entry.getValue();
                    CompoundTag row = new CompoundTag();
                    row.putUUID("Player", entry.getKey());
                    row.putString("Proposal", proposal.proposalId().toString());
                    row.putString("Subject", proposal.subjectId().toString());
                    row.putLong("DiagnosticTick", proposal.diagnosticTick());
                    row.putLong("ExpiresAt", proposal.expiresAtTick());
                    row.putString("Fault", proposal.faultCode().name());
                    row.putString("Category", proposal.category().name());
                    row.putString("Action", proposal.recommendedAction().name());
                    row.putString("Evidence", proposal.evidenceCode());
                    row.putString("DiagnosticHash", proposal.diagnosticHash());
                    row.putString("State", proposal.state().name());
                    row.putBoolean("WorldMutationAuthorized", proposal.worldMutationAuthorized());
                    row.putBoolean("InventoryAccessAuthorized", proposal.inventoryAccessAuthorized());
                    row.putBoolean("ExecutionAuthorized", proposal.executionAuthorized());
                    rows.add(row);
                });
        root.put("Proposals", rows);
        return root;
    }
}
