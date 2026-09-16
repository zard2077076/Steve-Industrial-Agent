package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.diagnostic.FactoryFaultCode;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApproval;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApprovalLedger;
import dev.stevecreate.agent.core.diagnostic.FactoryRecommendedAction;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable A-MAINT-01 approval journal; malformed state restores no authority. */
public final class FactoryMaintenanceApprovalSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_maintenance_approvals";
    public static final int SCHEMA = 1;
    private FactoryMaintenanceApprovalLedger.Snapshot snapshot =
            new FactoryMaintenanceApprovalLedger().snapshot();

    public static FactoryMaintenanceApprovalSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FactoryMaintenanceApprovalSavedData::load,
                FactoryMaintenanceApprovalSavedData::new, DATA_NAME);
    }

    public static FactoryMaintenanceApprovalSavedData load(CompoundTag root) {
        FactoryMaintenanceApprovalSavedData data = new FactoryMaintenanceApprovalSavedData();
        try {
            int schema = root.getInt("Schema");
            if (schema < 0 || schema > SCHEMA) {
                throw new IllegalArgumentException("unsupported maintenance approval schema");
            }
            LinkedHashMap<ResourceId, FactoryMaintenanceApproval> approvals =
                    new LinkedHashMap<>();
            for (Tag raw : root.getList("Approvals", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) raw;
                FactoryMaintenanceApproval approval = new FactoryMaintenanceApproval(
                        ResourceId.parse(row.getString("Proposal")), row.getUUID("Player"),
                        row.getString("DiagnosticHash"),
                        FactoryFaultCode.valueOf(row.getString("Fault")),
                        FactoryRecommendedAction.valueOf(row.getString("Action")),
                        row.getLong("ApprovedAt"), row.getLong("ExpiresAt"),
                        FactoryMaintenanceApproval.State.valueOf(row.getString("State")),
                        row.getLong("Generation"));
                if (approvals.putIfAbsent(approval.proposalId(), approval) != null) {
                    throw new IllegalArgumentException("duplicate maintenance approval");
                }
            }
            List<String> journal = root.getList("Journal", Tag.TAG_STRING).stream()
                    .map(Tag::getAsString).toList();
            FactoryMaintenanceApprovalLedger.Snapshot candidate =
                    new FactoryMaintenanceApprovalLedger.Snapshot(
                            approvals, journal, root.getLong("Generation"));
            FactoryMaintenanceApprovalLedger.restore(candidate);
            data.snapshot = candidate;
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Never reconstruct maintenance permission from partial or tampered evidence.
        }
        return data;
    }

    public FactoryMaintenanceApprovalLedger ledger() {
        return FactoryMaintenanceApprovalLedger.restore(snapshot);
    }

    public void put(FactoryMaintenanceApprovalLedger ledger) {
        snapshot = ledger.snapshot();
        FactoryMaintenanceApprovalLedger.restore(snapshot);
        setDirty();
    }

    public FactoryMaintenanceApprovalLedger.Snapshot snapshot() { return snapshot; }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag rows = new ListTag();
        snapshot.approvals().values().stream().sorted(Comparator.comparing(
                value -> value.proposalId().toString())).forEach(approval -> {
                    CompoundTag row = new CompoundTag();
                    row.putString("Proposal", approval.proposalId().toString());
                    row.putUUID("Player", approval.playerId());
                    row.putString("DiagnosticHash", approval.diagnosticHash());
                    row.putString("Fault", approval.faultCode().name());
                    row.putString("Action", approval.recommendedAction().name());
                    row.putLong("ApprovedAt", approval.approvedAtTick());
                    row.putLong("ExpiresAt", approval.expiresAtTick());
                    row.putString("State", approval.state().name());
                    row.putLong("Generation", approval.generation());
                    rows.add(row);
                });
        root.put("Approvals", rows);
        ListTag journal = new ListTag();
        snapshot.journal().forEach(value -> journal.add(StringTag.valueOf(value)));
        root.put("Journal", journal);
        root.putLong("Generation", snapshot.generation());
        return root;
    }
}
