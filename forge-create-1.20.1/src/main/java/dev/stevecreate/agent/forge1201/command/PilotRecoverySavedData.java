package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.JournalData;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
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

/** World-owned bounded persistence for IWP player-command recovery and cleanup ownership. */
final class PilotRecoverySavedData extends SavedData {
    static final String DATA_NAME = "steve_industrial_iwp_pilot_recovery";
    private static final int MAX_PLAYERS = 16;
    private static final int MAX_JOURNALS = 64;
    private static final String ENTRIES = "Entries";

    private final Map<UUID, RecoveryEntry> entries;

    PilotRecoverySavedData() {
        this(Map.of());
    }

    private PilotRecoverySavedData(Map<UUID, RecoveryEntry> entries) {
        if (entries.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("IWP recovery player count exceeds " + MAX_PLAYERS);
        }
        this.entries = new LinkedHashMap<>(entries);
    }

    static PilotRecoverySavedData forLevel(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                PilotRecoverySavedData::load,
                PilotRecoverySavedData::new,
                DATA_NAME);
    }

    static PilotRecoverySavedData load(CompoundTag root) {
        ListTag list = root.getList(ENTRIES, Tag.TAG_COMPOUND);
        if (list.size() > MAX_PLAYERS) {
            throw new IllegalStateException("Persisted IWP recovery player count exceeds bounds");
        }
        Map<UUID, RecoveryEntry> entries = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            RecoveryEntry entry = decodeEntry(list.getCompound(index));
            if (entries.put(entry.playerId(), entry) != null) {
                throw new IllegalStateException("Duplicate persisted IWP recovery player");
            }
        }
        return new PilotRecoverySavedData(entries);
    }

    Optional<RecoveryEntry> entry(UUID playerId) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(playerId, "playerId")));
    }

    void put(RecoveryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!entries.containsKey(entry.playerId()) && entries.size() >= MAX_PLAYERS) {
            throw new IllegalStateException("IWP recovery player count exceeds bounds");
        }
        entries.put(entry.playerId(), entry);
        setDirty();
    }

    void remove(UUID playerId) {
        if (entries.remove(Objects.requireNonNull(playerId, "playerId")) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        entries.values().stream().sorted((left, right) ->
                left.playerId().toString().compareTo(right.playerId().toString()))
                .map(PilotRecoverySavedData::encodeEntry).forEach(list::add);
        root.put(ENTRIES, list);
        return root;
    }

    enum Stage { BUILD, CONNECT, FEED, PROCESS, VERIFY, COMPLETE, CANCELLED, FAILED }

    enum RecoveryMode { SAFE_CHECKPOINT, UNSAFE_RESOURCE_HISTORY, VERIFY_PENDING, COMPLETE, TERMINAL }

    record RecoveryEntry(
            UUID playerId,
            String worldIdentity,
            String worldFingerprint,
            ResourceId dimension,
            DeploymentBoundingBox region,
            String regionHash,
            String regionFingerprint,
            ResourceId target,
            long quantity,
            QuarterTurn orientation,
            ResourceId rootSessionId,
            BlockPos3i resourceBuffer,
            String previewHash,
            String backupIdentity,
            int mutationBudget,
            long authorityExpiresAt,
            Stage stage,
            RecoveryMode mode,
            byte[] checkpoint,
            List<WorldChangeJournal> journals,
            long outputObserved,
            String detail,
            long updatedTick) {
        RecoveryEntry {
            Objects.requireNonNull(playerId, "playerId");
            worldIdentity = text(worldIdentity, "worldIdentity");
            worldFingerprint = text(worldFingerprint, "worldFingerprint");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(region, "region");
            regionHash = text(regionHash, "regionHash");
            regionFingerprint = text(regionFingerprint, "regionFingerprint");
            Objects.requireNonNull(target, "target");
            if (quantity < 1 || quantity > 64) throw new IllegalArgumentException("quantity outside bounds");
            Objects.requireNonNull(orientation, "orientation");
            Objects.requireNonNull(rootSessionId, "rootSessionId");
            Objects.requireNonNull(resourceBuffer, "resourceBuffer");
            previewHash = text(previewHash, "previewHash");
            backupIdentity = text(backupIdentity, "backupIdentity");
            if (mutationBudget < 1 || mutationBudget > 256) {
                throw new IllegalArgumentException("mutationBudget outside bounds");
            }
            if (authorityExpiresAt < 1) throw new IllegalArgumentException("authority expiry is invalid");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(mode, "mode");
            checkpoint = boundedCheckpoint(checkpoint);
            journals = List.copyOf(Objects.requireNonNull(journals, "journals"));
            if (journals.size() > MAX_JOURNALS) throw new IllegalArgumentException("journal count exceeds bounds");
            if (outputObserved < 0 || outputObserved > 1_000_000_000L) {
                throw new IllegalArgumentException("outputObserved outside bounds");
            }
            detail = text(detail, "detail");
            if (updatedTick < 0) throw new IllegalArgumentException("updatedTick cannot be negative");
            if (mode == RecoveryMode.SAFE_CHECKPOINT && checkpoint.length == 0) {
                throw new IllegalArgumentException("safe recovery requires a checkpoint");
            }
            if (mode != RecoveryMode.SAFE_CHECKPOINT && checkpoint.length != 0) {
                throw new IllegalArgumentException("only safe recovery may carry a checkpoint");
            }
        }

        @Override
        public byte[] checkpoint() { return checkpoint.clone(); }

        RecoveryEntry state(
                Stage nextStage,
                RecoveryMode nextMode,
                byte[] nextCheckpoint,
                List<WorldChangeJournal> nextJournals,
                long nextOutput,
                String nextDetail,
                long tick) {
            return new RecoveryEntry(playerId, worldIdentity, worldFingerprint, dimension,
                    region, regionHash, regionFingerprint, target, quantity, orientation,
                    rootSessionId, resourceBuffer, previewHash, backupIdentity, mutationBudget,
                    authorityExpiresAt,
                    nextStage, nextMode, nextCheckpoint, nextJournals, nextOutput, nextDetail, tick);
        }
    }

    private static CompoundTag encodeEntry(RecoveryEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Player", entry.playerId().toString());
        tag.putString("World", entry.worldIdentity());
        tag.putString("WorldFingerprint", entry.worldFingerprint());
        tag.putString("Dimension", entry.dimension().toString());
        putBounds(tag, entry.region());
        tag.putString("RegionHash", entry.regionHash());
        tag.putString("RegionFingerprint", entry.regionFingerprint());
        tag.putString("Target", entry.target().toString());
        tag.putLong("Quantity", entry.quantity());
        tag.putString("Orientation", entry.orientation().name());
        tag.putString("RootSession", entry.rootSessionId().toString());
        putPosition(tag, "Buffer", entry.resourceBuffer());
        tag.putString("PreviewHash", entry.previewHash());
        tag.putString("BackupIdentity", entry.backupIdentity());
        tag.putInt("MutationBudget", entry.mutationBudget());
        tag.putLong("AuthorityExpiresAt", entry.authorityExpiresAt());
        tag.putString("Stage", entry.stage().name());
        tag.putString("Mode", entry.mode().name());
        tag.putByteArray("Checkpoint", entry.checkpoint());
        ListTag journals = new ListTag();
        entry.journals().stream().map(PilotRecoverySavedData::encodeJournal).forEach(journals::add);
        tag.put("Journals", journals);
        tag.putLong("OutputObserved", entry.outputObserved());
        tag.putString("Detail", entry.detail());
        tag.putLong("UpdatedTick", entry.updatedTick());
        return tag;
    }

    private static RecoveryEntry decodeEntry(CompoundTag tag) {
        try {
            ListTag journalTags = tag.getList("Journals", Tag.TAG_COMPOUND);
            if (journalTags.size() > MAX_JOURNALS) throw new IllegalStateException("journal count exceeds bounds");
            List<WorldChangeJournal> journals = new ArrayList<>();
            for (int index = 0; index < journalTags.size(); index++) {
                journals.add(decodeJournal(journalTags.getCompound(index)));
            }
            return new RecoveryEntry(
                    UUID.fromString(tag.getString("Player")),
                    tag.getString("World"), tag.getString("WorldFingerprint"),
                    ResourceId.parse(tag.getString("Dimension")), getBounds(tag),
                    tag.getString("RegionHash"), tag.getString("RegionFingerprint"),
                    ResourceId.parse(tag.getString("Target")), tag.getLong("Quantity"),
                    QuarterTurn.valueOf(tag.getString("Orientation")),
                    ResourceId.parse(tag.getString("RootSession")),
                    getPosition(tag, "Buffer"), tag.getString("PreviewHash"),
                    tag.getString("BackupIdentity"), tag.getInt("MutationBudget"),
                    tag.getLong("AuthorityExpiresAt"),
                    Stage.valueOf(tag.getString("Stage")),
                    RecoveryMode.valueOf(tag.getString("Mode")),
                    tag.getByteArray("Checkpoint"), journals,
                    tag.getLong("OutputObserved"), tag.getString("Detail"),
                    tag.getLong("UpdatedTick"));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Persisted IWP recovery entry is invalid", invalid);
        }
    }

    private static CompoundTag encodeJournal(WorldChangeJournal journal) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Session", journal.sessionId().toString());
        ListTag changes = new ListTag();
        for (WorldChangeJournal.Entry entry : journal.entries()) {
            if (entry instanceof BlockChange block) changes.add(encodeBlockChange(block));
        }
        tag.put("Blocks", changes);
        return tag;
    }

    private static WorldChangeJournal decodeJournal(CompoundTag tag) {
        ResourceId session = ResourceId.parse(tag.getString("Session"));
        ListTag blocks = tag.getList("Blocks", Tag.TAG_COMPOUND);
        if (blocks.size() > WorldChangeJournal.MAX_ENTRIES) {
            throw new IllegalStateException("Persisted cleanup block count exceeds bounds");
        }
        List<BlockChange> changes = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            changes.add(decodeBlockChange(blocks.getCompound(index), session));
        }
        return new WorldChangeJournal(session, changes);
    }

    private static CompoundTag encodeBlockChange(BlockChange change) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Change", change.changeId().toString());
        tag.putString("Step", change.sourceStepId().toString());
        tag.putLong("Tick", change.recordedTick());
        putPosition(tag, "Position", change.position());
        tag.put("Before", encodeSnapshot(change.before()));
        tag.put("After", encodeSnapshot(change.after()));
        return tag;
    }

    private static BlockChange decodeBlockChange(CompoundTag tag, ResourceId session) {
        return new BlockChange(
                ResourceId.parse(tag.getString("Change")), session,
                ResourceId.parse(tag.getString("Step")), tag.getLong("Tick"),
                getPosition(tag, "Position"), decodeSnapshot(tag.getCompound("Before")),
                decodeSnapshot(tag.getCompound("After")));
    }

    private static CompoundTag encodeSnapshot(WorldBlockSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Block", snapshot.blockId().toString());
        CompoundTag properties = new CompoundTag();
        snapshot.properties().forEach(properties::putString);
        tag.put("Properties", properties);
        snapshot.blockEntityData().ifPresent(data -> {
            tag.putString("DataSchema", data.schemaId().toString());
            tag.putString("DataValue", data.value());
        });
        return tag;
    }

    private static WorldBlockSnapshot decodeSnapshot(CompoundTag tag) {
        CompoundTag propertiesTag = tag.getCompound("Properties");
        Map<String, String> properties = new LinkedHashMap<>();
        for (String key : propertiesTag.getAllKeys()) properties.put(key, propertiesTag.getString(key));
        Optional<JournalData> data = tag.contains("DataSchema", Tag.TAG_STRING)
                ? Optional.of(new JournalData(ResourceId.parse(tag.getString("DataSchema")),
                        tag.getString("DataValue")))
                : Optional.empty();
        return new WorldBlockSnapshot(ResourceId.parse(tag.getString("Block")), properties, data);
    }

    private static void putBounds(CompoundTag tag, DeploymentBoundingBox bounds) {
        putPosition(tag, "Minimum", bounds.minimum());
        putPosition(tag, "Maximum", bounds.maximum());
    }

    private static DeploymentBoundingBox getBounds(CompoundTag tag) {
        return new DeploymentBoundingBox(getPosition(tag, "Minimum"), getPosition(tag, "Maximum"));
    }

    private static void putPosition(CompoundTag tag, String prefix, BlockPos3i position) {
        tag.putInt(prefix + "X", position.x());
        tag.putInt(prefix + "Y", position.y());
        tag.putInt(prefix + "Z", position.z());
    }

    private static BlockPos3i getPosition(CompoundTag tag, String prefix) {
        return new BlockPos3i(tag.getInt(prefix + "X"), tag.getInt(prefix + "Y"),
                tag.getInt(prefix + "Z"));
    }

    private static byte[] boundedCheckpoint(byte[] checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.length > RecoveryCheckpointCodec.MAX_ENCODED_BYTES + 32) {
            throw new IllegalArgumentException("IWP recovery checkpoint exceeds bounds");
        }
        return Arrays.copyOf(checkpoint, checkpoint.length);
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 65_536) {
            throw new IllegalArgumentException(name + " is blank or outside bounds");
        }
        return value;
    }
}
