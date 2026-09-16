package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Location-only authorization for a player-selected dedicated salvage container. */
public final class PlayerSalvageSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_player_salvage";
    public static final int SCHEMA = 1;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static PlayerSalvageSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PlayerSalvageSavedData::load, PlayerSalvageSavedData::new, DATA_NAME);
    }

    public static PlayerSalvageSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema != 0 && schema != SCHEMA) {
            throw new IllegalStateException("Unsupported player salvage schema " + schema);
        }
        PlayerSalvageSavedData data = new PlayerSalvageSavedData();
        for (Tag raw : root.getList("Destinations", Tag.TAG_COMPOUND)) {
            try {
                Entry entry = decode((CompoundTag) raw);
                data.entries.put(entry.projectId(), entry);
            } catch (IllegalArgumentException ignored) {
                // Malformed location authority is dropped; container contents are never stored.
            }
        }
        return data;
    }

    public Optional<Entry> entry(UUID projectId) {
        return Optional.ofNullable(entries.get(projectId));
    }

    public void put(Entry entry) {
        entries.put(entry.projectId(), entry);
        setDirty();
    }

    public void remove(UUID projectId) {
        if (entries.remove(projectId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag values = new ListTag();
        entries.values().stream().sorted(java.util.Comparator.comparing(
                entry -> entry.projectId().toString())).forEach(entry -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("ProjectId", entry.projectId());
            tag.putUUID("PlayerId", entry.playerId());
            tag.putString("Dimension", entry.dimension().toString());
            tag.putInt("X", entry.position().x());
            tag.putInt("Y", entry.position().y());
            tag.putInt("Z", entry.position().z());
            tag.putString("StateFingerprint", entry.stateFingerprint());
            tag.putString("DestinationIdentity", entry.destinationIdentity());
            tag.putLong("AuthorizedAt", entry.authorizedAt());
            values.add(tag);
        });
        root.put("Destinations", values);
        return root;
    }

    private static Entry decode(CompoundTag tag) {
        return new Entry(tag.getUUID("ProjectId"), tag.getUUID("PlayerId"),
                ResourceId.parse(tag.getString("Dimension")),
                new BlockPos3i(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                tag.getString("StateFingerprint"), tag.getString("DestinationIdentity"),
                tag.getLong("AuthorizedAt"));
    }

    public record Entry(
            UUID projectId,
            UUID playerId,
            ResourceId dimension,
            BlockPos3i position,
            String stateFingerprint,
            String destinationIdentity,
            long authorizedAt) {
        public Entry {
            if (projectId == null || playerId == null || dimension == null || position == null
                    || !stateFingerprint.matches("[0-9a-f]{64}")
                    || destinationIdentity == null || destinationIdentity.isBlank()
                    || destinationIdentity.length() > 160 || authorizedAt < 0) {
                throw new IllegalArgumentException("invalid player salvage destination");
            }
        }
    }
}
