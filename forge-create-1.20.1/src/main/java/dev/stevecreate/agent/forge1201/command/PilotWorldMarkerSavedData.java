package dev.stevecreate.agent.forge1201.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Versioned world-local opt-in marker. It grants no region, backup, or execution authority. */
final class PilotWorldMarkerSavedData extends SavedData {
    static final String DATA_NAME = "steve_industrial_public_test_world";
    static final int SCHEMA = 1;
    private Marker marker;

    static PilotWorldMarkerSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PilotWorldMarkerSavedData::load,
                PilotWorldMarkerSavedData::new,
                DATA_NAME);
    }

    static PilotWorldMarkerSavedData load(CompoundTag tag) {
        PilotWorldMarkerSavedData data = new PilotWorldMarkerSavedData();
        if (tag.getBoolean("Marked")) {
            int schema = tag.getInt("Schema");
            if (schema != SCHEMA) throw new IllegalStateException("Unsupported test-world marker schema " + schema);
            data.marker = new Marker(schema, tag.getString("WorldIdentity"),
                    tag.getString("Generation"), tag.getString("DimensionPolicy"),
                    tag.getString("CreationEvidence"), tag.getString("MarkedBy"),
                    tag.getLong("CreatedAt"));
        }
        return data;
    }

    Optional<Marker> marker() {
        return Optional.ofNullable(marker);
    }

    void mark(Marker value) {
        marker = Objects.requireNonNull(value, "value");
        setDirty();
    }

    void unmark() {
        if (marker != null) {
            marker = null;
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Marked", marker != null);
        if (marker != null) {
            tag.putInt("Schema", marker.schema());
            tag.putString("WorldIdentity", marker.worldIdentity());
            tag.putString("Generation", marker.generation());
            tag.putString("DimensionPolicy", marker.dimensionPolicy());
            tag.putString("CreationEvidence", marker.creationEvidence());
            tag.putString("MarkedBy", marker.markedBy());
            tag.putLong("CreatedAt", marker.createdAt());
        }
        return tag;
    }

    record Marker(
            int schema,
            String worldIdentity,
            String generation,
            String dimensionPolicy,
            String creationEvidence,
            String markedBy,
            long createdAt) {
        Marker {
            if (schema != SCHEMA || worldIdentity == null || worldIdentity.isBlank()
                    || generation == null || !generation.matches("[0-9a-f-]{36}")
                    || dimensionPolicy == null || dimensionPolicy.isBlank()
                    || creationEvidence == null || creationEvidence.isBlank()
                    || markedBy == null || markedBy.isBlank() || createdAt < 0) {
                throw new IllegalArgumentException("Invalid public test-world marker");
            }
            UUID.fromString(generation);
        }
    }
}
