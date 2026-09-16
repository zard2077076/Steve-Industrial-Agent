package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable player-selected sources, reservations and material transaction journal. */
public final class PlayerMaterialSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_player_materials";
    public static final int SCHEMA = 3;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static PlayerMaterialSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PlayerMaterialSavedData::load, PlayerMaterialSavedData::new, DATA_NAME);
    }

    public static PlayerMaterialSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported player material schema " + schema);
        }
        PlayerMaterialSavedData data = new PlayerMaterialSavedData();
        for (Tag raw : root.getList("Projects", Tag.TAG_COMPOUND)) {
            try {
                Entry entry = decodeEntry((CompoundTag) raw);
                data.entries.put(entry.projectId(), entry);
            } catch (IllegalArgumentException ignored) {
                // Drop only the malformed project. No material authority is reconstructed from it.
            }
        }
        return data;
    }

    public Optional<Entry> entry(UUID projectId) { return Optional.ofNullable(entries.get(projectId)); }

    public Map<UUID, Entry> entries() { return Map.copyOf(entries); }

    public void put(Entry entry) {
        entries.put(Objects.requireNonNull(entry, "entry").projectId(), entry);
        setDirty();
    }

    public void remove(UUID projectId) {
        if (entries.remove(Objects.requireNonNull(projectId, "projectId")) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag projects = new ListTag();
        entries.values().stream().sorted(Comparator.comparing(value -> value.projectId().toString()))
                .map(PlayerMaterialSavedData::encodeEntry).forEach(projects::add);
        root.put("Projects", projects);
        return root;
    }

    private static CompoundTag encodeEntry(Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("ProjectId", entry.projectId());
        tag.putUUID("PlayerId", entry.playerId());
        tag.putString("Dimension", entry.dimension().toString());
        tag.putString("PlanHash", entry.planHash());
        tag.putString("RuntimeFingerprint", entry.runtimeFingerprint());
        tag.putBoolean("AllowSalvage", entry.allowSalvage());
        tag.putLong("CreatedAt", entry.createdAt());
        tag.putLong("UpdatedAt", entry.updatedAt());
        tag.putString("StatusCode", entry.statusCode());
        if (entry.logistics() != null) {
            CompoundTag logistics = new CompoundTag();
            encodePosition(logistics, "Staging", entry.logistics().staging());
            encodePosition(logistics, "Delivery", entry.logistics().delivery());
            tag.put("Logistics", logistics);
        }
        ListTag requirements = new ListTag();
        entry.requirements().entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).forEach(value -> {
            CompoundTag row = new CompoundTag();
            row.putString("Item", value.getKey().toString());
            row.putLong("Quantity", value.getValue());
            requirements.add(row);
        });
        tag.put("Requirements", requirements);
        ListTag sources = new ListTag();
        entry.sources().forEach(value -> sources.add(encodeSource(value)));
        tag.put("Sources", sources);
        ListTag reservations = new ListTag();
        entry.reservations().forEach(value -> reservations.add(encodeReservation(value)));
        tag.put("Reservations", reservations);
        ListTag transactions = new ListTag();
        entry.transactions().forEach(value -> transactions.add(encodeTransaction(value)));
        tag.put("Transactions", transactions);
        ListTag journal = new ListTag();
        entry.journal().forEach(value -> journal.add(encodeJournal(value)));
        tag.put("Journal", journal);
        if (entry.report() != null) tag.put("Report", encodeReport(entry.report()));
        return tag;
    }

    private static Entry decodeEntry(CompoundTag tag) {
        LinkedHashMap<ResourceId, Long> requirements = new LinkedHashMap<>();
        for (Tag raw : tag.getList("Requirements", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            requirements.put(ResourceId.parse(row.getString("Item")), row.getLong("Quantity"));
        }
        ArrayList<Source> sources = new ArrayList<>();
        for (Tag raw : tag.getList("Sources", Tag.TAG_COMPOUND)) sources.add(decodeSource((CompoundTag) raw));
        ArrayList<Reservation> reservations = new ArrayList<>();
        for (Tag raw : tag.getList("Reservations", Tag.TAG_COMPOUND)) {
            reservations.add(decodeReservation((CompoundTag) raw));
        }
        ArrayList<Transaction> transactions = new ArrayList<>();
        for (Tag raw : tag.getList("Transactions", Tag.TAG_COMPOUND)) {
            transactions.add(decodeTransaction((CompoundTag) raw));
        }
        ArrayList<JournalEvent> journal = new ArrayList<>();
        for (Tag raw : tag.getList("Journal", Tag.TAG_COMPOUND)) {
            journal.add(decodeJournal((CompoundTag) raw));
        }
        Logistics logistics = null;
        if (tag.contains("Logistics", Tag.TAG_COMPOUND)) {
            CompoundTag encoded = tag.getCompound("Logistics");
            logistics = new Logistics(decodePosition(encoded, "Staging"),
                    decodePosition(encoded, "Delivery"));
        }
        return new Entry(tag.getUUID("ProjectId"), tag.getUUID("PlayerId"),
                ResourceId.parse(tag.getString("Dimension")), requirements,
                tag.getString("PlanHash"), tag.getString("RuntimeFingerprint"), sources,
                reservations, transactions, journal, logistics, tag.getBoolean("AllowSalvage"), tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"), tag.getString("StatusCode"),
                tag.contains("Report", Tag.TAG_COMPOUND) ? decodeReport(tag.getCompound("Report")) : null);
    }

    private static CompoundTag encodeSource(Source source) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SourceId", source.sourceId());
        tag.putInt("X", source.position().x()); tag.putInt("Y", source.position().y());
        tag.putInt("Z", source.position().z()); tag.putString("Face", source.accessFace());
        tag.putString("BlockEntityType", source.blockEntityType());
        tag.putString("BlockStateHash", source.blockStateHash());
        tag.putString("InventoryHash", source.inventoryHash());
        tag.putInt("Priority", source.priority()); tag.putLong("MaximumWithdrawal", source.maximumWithdrawal());
        tag.putLong("BoundAt", source.boundAt()); tag.putLong("ExpiresAt", source.expiresAt());
        tag.putBoolean("ProjectSalvage", source.projectSalvage());
        ListTag slots = new ListTag();
        source.slots().forEach(value -> {
            CompoundTag row = new CompoundTag();
            row.putInt("Slot", value.slot()); row.putString("Item", value.identity().itemId().toString());
            row.putString("PayloadHash", value.identity().payloadSha256()); row.putLong("Quantity", value.quantity());
            slots.add(row);
        });
        tag.put("Slots", slots);
        return tag;
    }

    private static Source decodeSource(CompoundTag tag) {
        ArrayList<Slot> slots = new ArrayList<>();
        for (Tag raw : tag.getList("Slots", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            slots.add(new Slot(row.getInt("Slot"), new MaterialIdentity(
                    ResourceId.parse(row.getString("Item")), row.getString("PayloadHash")),
                    row.getLong("Quantity")));
        }
        return new Source(tag.getUUID("SourceId"), new BlockPos3i(tag.getInt("X"), tag.getInt("Y"),
                tag.getInt("Z")), tag.getString("Face"), tag.getString("BlockEntityType"),
                tag.getString("BlockStateHash"), tag.getString("InventoryHash"), tag.getInt("Priority"),
                tag.getLong("MaximumWithdrawal"), tag.getLong("BoundAt"), tag.getLong("ExpiresAt"),
                tag.getBoolean("ProjectSalvage"), slots);
    }

    private static CompoundTag encodeReservation(Reservation value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("ReservationId", value.reservationId()); tag.putString("Requirement", value.requirement().toString());
        tag.putUUID("SourceId", value.sourceId()); tag.putInt("Slot", value.slot());
        tag.putString("Item", value.identity().itemId().toString());
        tag.putString("PayloadHash", value.identity().payloadSha256());
        tag.putLong("Quantity", value.quantity()); tag.putLong("ExpiresAt", value.expiresAt());
        return tag;
    }

    private static Reservation decodeReservation(CompoundTag tag) {
        return new Reservation(tag.getUUID("ReservationId"), ResourceId.parse(tag.getString("Requirement")),
                tag.getUUID("SourceId"), tag.getInt("Slot"), new MaterialIdentity(
                ResourceId.parse(tag.getString("Item")), tag.getString("PayloadHash")),
                tag.getLong("Quantity"), tag.getLong("ExpiresAt"));
    }

    private static CompoundTag encodeTransaction(Transaction value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("TransactionId", value.transactionId()); tag.putUUID("ReservationId", value.reservationId());
        tag.putString("TaskId", value.taskId()); tag.putString("Executor", value.executor().name());
        tag.putString("State", value.state().name()); tag.putLong("Quantity", value.quantity());
        tag.putLong("UpdatedTick", value.updatedTick()); return tag;
    }

    private static Transaction decodeTransaction(CompoundTag tag) {
        return new Transaction(tag.getUUID("TransactionId"), tag.getUUID("ReservationId"),
                tag.getString("TaskId"), MaterialExecutorKind.valueOf(tag.getString("Executor")),
                MaterialTransactionState.valueOf(tag.getString("State")), tag.getLong("Quantity"),
                tag.getLong("UpdatedTick"));
    }

    private static CompoundTag encodeJournal(JournalEvent event) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Sequence", event.sequence());
        tag.putUUID("TransactionId", event.transactionId());
        tag.putString("State", event.state().name());
        tag.putLong("Tick", event.tick());
        tag.putString("Code", event.code());
        return tag;
    }

    private static JournalEvent decodeJournal(CompoundTag tag) {
        return new JournalEvent(tag.getLong("Sequence"), tag.getUUID("TransactionId"),
                MaterialTransactionState.valueOf(tag.getString("State")), tag.getLong("Tick"),
                tag.getString("Code"));
    }

    private static CompoundTag encodeReport(CompletionReport value) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Planned", value.planned()); tag.putLong("Withdrawn", value.withdrawn());
        tag.putLong("Consumed", value.consumed()); tag.putLong("Returned", value.returned());
        tag.putLong("SalvageTransferred", value.salvageTransferred());
        tag.putLong("ObservedOutput", value.observedOutput()); tag.putLong("ExpectedOutput", value.expectedOutput());
        tag.putLong("DuplicateWithdrawals", value.duplicateWithdrawals());
        tag.putLong("DuplicateReturns", value.duplicateReturns()); tag.putLong("UnaccountedItems", value.unaccountedItems());
        tag.putLong("PrivateItemsTouched", value.privateItemsTouched());
        tag.putBoolean("Balanced", value.balanced()); return tag;
    }

    private static CompletionReport decodeReport(CompoundTag tag) {
        return new CompletionReport(tag.getLong("Planned"), tag.getLong("Withdrawn"),
                tag.getLong("Consumed"), tag.getLong("Returned"), tag.getLong("SalvageTransferred"),
                tag.getLong("ObservedOutput"), tag.getLong("ExpectedOutput"),
                tag.getLong("DuplicateWithdrawals"), tag.getLong("DuplicateReturns"),
                tag.getLong("UnaccountedItems"), tag.getLong("PrivateItemsTouched"), tag.getBoolean("Balanced"));
    }

    private static void encodePosition(CompoundTag tag, String prefix, BlockPos3i position) {
        tag.putInt(prefix + "X", position.x());
        tag.putInt(prefix + "Y", position.y());
        tag.putInt(prefix + "Z", position.z());
    }

    private static BlockPos3i decodePosition(CompoundTag tag, String prefix) {
        return new BlockPos3i(tag.getInt(prefix + "X"), tag.getInt(prefix + "Y"),
                tag.getInt(prefix + "Z"));
    }

    public record Entry(UUID projectId, UUID playerId, ResourceId dimension,
            Map<ResourceId, Long> requirements, String planHash, String runtimeFingerprint,
            List<Source> sources, List<Reservation> reservations, List<Transaction> transactions,
            List<JournalEvent> journal, Logistics logistics, boolean allowSalvage,
            long createdAt, long updatedAt, String statusCode,
            CompletionReport report) {
        public Entry {
            Objects.requireNonNull(projectId); Objects.requireNonNull(playerId); Objects.requireNonNull(dimension);
            requirements = Map.copyOf(Objects.requireNonNull(requirements));
            sources = List.copyOf(Objects.requireNonNull(sources));
            reservations = List.copyOf(Objects.requireNonNull(reservations));
            transactions = List.copyOf(Objects.requireNonNull(transactions));
            journal = List.copyOf(Objects.requireNonNull(journal));
            if (requirements.isEmpty() || requirements.size() > 128 || sources.size() > 8
                    || reservations.size() > 4_096 || transactions.size() > 4_096 || journal.size() > 4_096
                    || !planHash.matches("[0-9a-f]{64}") || runtimeFingerprint.isBlank()
                    || createdAt < 0 || updatedAt < createdAt || statusCode.isBlank()
                    || statusCode.length() > 96) throw new IllegalArgumentException("invalid material project entry");
        }

        public Entry withSources(List<Source> next, long now, String code) {
            return new Entry(projectId, playerId, dimension, requirements, planHash, runtimeFingerprint,
                    next, List.of(), transactions, journal, logistics, allowSalvage, createdAt, now, code, report);
        }

        public Entry withReservations(List<Reservation> next, boolean salvage, long now, String code) {
            return new Entry(projectId, playerId, dimension, requirements, planHash, runtimeFingerprint,
                    sources, next, transactions, journal, logistics, salvage, createdAt, now, code, report);
        }

        public Entry withTransactions(List<Transaction> next, long now, String code, CompletionReport nextReport) {
            ArrayList<JournalEvent> nextJournal = new ArrayList<>(journal);
            long sequence = journal.stream().mapToLong(JournalEvent::sequence).max().orElse(0);
            Map<UUID, Transaction> previous = transactions.stream().collect(
                    java.util.stream.Collectors.toMap(Transaction::transactionId, value -> value));
            for (Transaction transaction : next) {
                Transaction prior = previous.get(transaction.transactionId());
                if (prior == null || prior.state() != transaction.state()) {
                    nextJournal.add(new JournalEvent(++sequence, transaction.transactionId(),
                            transaction.state(), transaction.updatedTick(), code));
                }
            }
            return new Entry(projectId, playerId, dimension, requirements, planHash, runtimeFingerprint,
                    sources, reservations, next, nextJournal, logistics, allowSalvage,
                    createdAt, now, code, nextReport);
        }

        public Entry withLogistics(Logistics next, long now, String code) {
            return new Entry(projectId, playerId, dimension, requirements, planHash, runtimeFingerprint,
                    sources, reservations, transactions, journal, next, allowSalvage,
                    createdAt, now, code, report);
        }

        public Entry withoutTransactions(long now, String code) {
            return new Entry(projectId, playerId, dimension, requirements, planHash, runtimeFingerprint,
                    sources, reservations, List.of(), journal, logistics, allowSalvage,
                    createdAt, now, code, report);
        }
    }

    public record Logistics(BlockPos3i staging, BlockPos3i delivery) {
        public Logistics { Objects.requireNonNull(staging); Objects.requireNonNull(delivery); }
    }

    public record Source(UUID sourceId, BlockPos3i position, String accessFace, String blockEntityType,
            String blockStateHash, String inventoryHash, int priority, long maximumWithdrawal,
            long boundAt, long expiresAt, boolean projectSalvage, List<Slot> slots) {
        public Source {
            Objects.requireNonNull(sourceId); Objects.requireNonNull(position); Objects.requireNonNull(accessFace);
            Objects.requireNonNull(blockEntityType); Objects.requireNonNull(blockStateHash); Objects.requireNonNull(inventoryHash);
            slots = List.copyOf(Objects.requireNonNull(slots));
            if (!blockStateHash.matches("[0-9a-f]{64}") || !inventoryHash.matches("[0-9a-f]{64}")
                    || priority < 0 || priority >= 8 || maximumWithdrawal < 1 || boundAt < 0
                    || expiresAt <= boundAt || slots.size() > 4_096) throw new IllegalArgumentException("invalid material source");
        }
    }

    public record Slot(int slot, MaterialIdentity identity, long quantity) {
        public Slot { if (slot < 0 || quantity < 1) throw new IllegalArgumentException("invalid material slot"); Objects.requireNonNull(identity); }
    }

    public record Reservation(UUID reservationId, ResourceId requirement, UUID sourceId, int slot,
            MaterialIdentity identity, long quantity, long expiresAt) {
        public Reservation { Objects.requireNonNull(reservationId); Objects.requireNonNull(requirement); Objects.requireNonNull(sourceId); Objects.requireNonNull(identity); if (slot < 0 || quantity < 1 || expiresAt < 1) throw new IllegalArgumentException("invalid reservation"); }
    }

    public record Transaction(UUID transactionId, UUID reservationId, String taskId,
            MaterialExecutorKind executor, MaterialTransactionState state, long quantity, long updatedTick) {
        public Transaction { Objects.requireNonNull(transactionId); Objects.requireNonNull(reservationId); Objects.requireNonNull(taskId); Objects.requireNonNull(executor); Objects.requireNonNull(state); if (taskId.isBlank() || taskId.length() > 160 || quantity < 1 || updatedTick < 0) throw new IllegalArgumentException("invalid transaction"); }
    }

    public record JournalEvent(long sequence, UUID transactionId,
            MaterialTransactionState state, long tick, String code) {
        public JournalEvent {
            Objects.requireNonNull(transactionId); Objects.requireNonNull(state); Objects.requireNonNull(code);
            if (sequence < 1 || tick < 0 || code.isBlank() || code.length() > 96) {
                throw new IllegalArgumentException("invalid material journal event");
            }
        }
    }

    public record CompletionReport(long planned, long withdrawn, long consumed, long returned,
            long salvageTransferred, long observedOutput, long expectedOutput,
            long duplicateWithdrawals, long duplicateReturns, long unaccountedItems,
            long privateItemsTouched, boolean balanced) {
        public CompletionReport {
            if (planned < 0 || withdrawn < 0 || consumed < 0 || returned < 0 || salvageTransferred < 0
                    || observedOutput < 0 || expectedOutput < 0 || duplicateWithdrawals < 0
                    || duplicateReturns < 0 || unaccountedItems < 0 || privateItemsTouched < 0
                    || balanced != (planned == withdrawn && withdrawn == consumed + returned
                    && duplicateWithdrawals == 0
                    && duplicateReturns == 0 && unaccountedItems == 0 && privateItemsTouched == 0)) {
                throw new IllegalArgumentException("invalid completion material balance");
            }
        }
    }
}
