package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable transaction boundary for the gap between approval consumption and world mutation. */
public final class FactoryMaintenanceExecutionSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_maintenance_executions";
    public static final int SCHEMA = 1;
    public static final int MAX_ENTRIES = 8_192;
    private final Map<ResourceId, Entry> entries = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private long generation;

    public static FactoryMaintenanceExecutionSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FactoryMaintenanceExecutionSavedData::load,
                FactoryMaintenanceExecutionSavedData::new, DATA_NAME);
    }

    public static FactoryMaintenanceExecutionSavedData load(CompoundTag root) {
        FactoryMaintenanceExecutionSavedData data = new FactoryMaintenanceExecutionSavedData();
        try {
            int schema = root.getInt("Schema");
            if (schema < 0 || schema > SCHEMA) throw new IllegalArgumentException("schema");
            for (Tag raw : root.getList("Executions", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) raw;
                Entry entry = new Entry(ResourceId.parse(row.getString("Proposal")),
                        row.getUUID("Player"), row.getString("DiagnosticHash"),
                        State.valueOf(row.getString("State")), row.getLong("PreparedAt"),
                        row.getLong("UpdatedAt"), row.getInt("WorldMutations"));
                if (data.entries.putIfAbsent(entry.proposalId(), entry) != null) {
                    throw new IllegalArgumentException("duplicate maintenance execution");
                }
            }
            root.getList("Journal", Tag.TAG_STRING).forEach(raw ->
                    data.journal.add(raw.getAsString()));
            data.generation = root.getLong("Generation");
            data.validateJournal();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            data.entries.clear();
            data.journal.clear();
            data.generation = 0;
        }
        return data;
    }

    public Optional<Entry> entry(ResourceId proposalId) {
        return Optional.ofNullable(entries.get(proposalId));
    }

    public boolean canPrepare(ResourceId proposalId) {
        return entries.containsKey(proposalId) || entries.size() < MAX_ENTRIES;
    }

    public Entry prepare(ResourceId proposalId, UUID playerId, String hash, long tick) {
        Entry existing = entries.get(proposalId);
        if (existing != null) {
            if (!existing.playerId().equals(playerId)
                    || !existing.diagnosticHash().equals(hash)) {
                throw new IllegalStateException("MAINTENANCE_EXECUTION_SCOPE_MISMATCH");
            }
            return existing;
        }
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("maintenance execution entries are full");
        }
        requireJournalCapacity();
        Entry entry = new Entry(proposalId, playerId, hash, State.PREPARED,
                tick, tick, 0);
        entries.put(proposalId, entry);
        journal(entry, tick);
        setDirty();
        return entry;
    }

    public Entry transition(ResourceId proposalId, State state, long tick, int mutations) {
        Entry current = entries.get(proposalId);
        if (current == null || state.ordinal() != current.state().ordinal() + 1) {
            throw new IllegalStateException("MAINTENANCE_EXECUTION_TRANSITION_INVALID");
        }
        requireJournalCapacity();
        Entry next = new Entry(current.proposalId(), current.playerId(),
                current.diagnosticHash(), state, current.preparedAt(), tick, mutations);
        entries.put(proposalId, next);
        journal(next, tick);
        setDirty();
        return next;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag rows = new ListTag();
        entries.values().stream().sorted(java.util.Comparator.comparing(value ->
                value.proposalId().toString())).forEach(entry -> {
                    CompoundTag row = new CompoundTag();
                    row.putString("Proposal", entry.proposalId().toString());
                    row.putUUID("Player", entry.playerId());
                    row.putString("DiagnosticHash", entry.diagnosticHash());
                    row.putString("State", entry.state().name());
                    row.putLong("PreparedAt", entry.preparedAt());
                    row.putLong("UpdatedAt", entry.updatedAt());
                    row.putInt("WorldMutations", entry.worldMutations());
                    rows.add(row);
                });
        root.put("Executions", rows);
        ListTag journalRows = new ListTag();
        journal.forEach(value -> journalRows.add(net.minecraft.nbt.StringTag.valueOf(value)));
        root.put("Journal", journalRows);
        root.putLong("Generation", generation);
        return root;
    }

    private void journal(Entry entry, long tick) {
        journal.add(++generation + "|" + tick + "|" + entry.state() + "|"
                + entry.proposalId() + "|" + entry.playerId() + "|"
                + entry.diagnosticHash() + "|" + entry.worldMutations());
    }

    private void requireJournalCapacity() {
        if (journal.size() >= 24_576) {
            throw new IllegalStateException("maintenance execution journal is full");
        }
    }

    private void validateJournal() {
        if (entries.size() > MAX_ENTRIES || journal.size() > 24_576
                || generation != journal.size()) {
            throw new IllegalArgumentException("maintenance execution journal size drifted");
        }
        long expectedGeneration = 1;
        Map<ResourceId, State> lastState = new LinkedHashMap<>();
        for (String row : journal) {
            String[] parts = row.split("\\|", 8);
            if (parts.length != 7 || Long.parseLong(parts[0]) != expectedGeneration++) {
                throw new IllegalArgumentException("maintenance execution journal order invalid");
            }
            ResourceId proposal = ResourceId.parse(parts[3]);
            Entry entry = entries.get(proposal);
            State state = State.valueOf(parts[2]);
            State previous = lastState.get(proposal);
            if (entry == null || !entry.playerId().toString().equals(parts[4])
                    || !entry.diagnosticHash().equals(parts[5])
                    || entry.preparedAt() > Long.parseLong(parts[1])
                    || Integer.parseInt(parts[6]) != (state == State.PREPARED ? 0 : 2)
                    || state.ordinal() != (previous == null ? 0 : previous.ordinal() + 1)) {
                throw new IllegalArgumentException("maintenance execution journal identity invalid");
            }
            lastState.put(proposal, state);
        }
        if (lastState.size() != entries.size()) {
            throw new IllegalArgumentException("maintenance execution journal has missing entries");
        }
        entries.forEach((id, entry) -> {
            if (lastState.get(id) != entry.state()) {
                throw new IllegalArgumentException("maintenance execution state rolled back");
            }
        });
    }

    public record Entry(ResourceId proposalId, UUID playerId, String diagnosticHash,
            State state, long preparedAt, long updatedAt, int worldMutations) {
        public Entry {
            if (proposalId == null || playerId == null || state == null
                    || diagnosticHash == null || !diagnosticHash.matches("[0-9a-f]{64}")
                    || preparedAt < 0 || updatedAt < preparedAt || worldMutations < 0
                    || (state == State.PREPARED && worldMutations != 0)
                    || (state != State.PREPARED && worldMutations != 2)) {
                throw new IllegalArgumentException("maintenance execution entry is invalid");
            }
        }
    }

    public enum State { PREPARED, APPLIED, VERIFIED }
}
