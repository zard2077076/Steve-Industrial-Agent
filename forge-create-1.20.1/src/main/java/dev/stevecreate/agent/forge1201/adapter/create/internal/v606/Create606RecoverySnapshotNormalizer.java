package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.JournalData;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Create 6.0.6-only canonicalization for transient recovery snapshot data. */
public final class Create606RecoverySnapshotNormalizer {
    private static final ResourceId BLOCK_ENTITY_SNBT_SCHEMA =
            new ResourceId("minecraft", "snbt/block_entity");
    private static final String NEEDS_SPEED_UPDATE = "NeedsSpeedUpdate";

    private Create606RecoverySnapshotNormalizer() {
    }

    public static Map<BlockPos3i, WorldBlockSnapshot> normalizeKineticSnapshots(
            ServerLevel level,
            Map<BlockPos3i, WorldBlockSnapshot> snapshots) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(snapshots, "snapshots");
        Map<BlockPos3i, WorldBlockSnapshot> normalized = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3i, WorldBlockSnapshot> entry : snapshots.entrySet()) {
            BlockPos3i position = Objects.requireNonNull(entry.getKey(), "snapshots key");
            WorldBlockSnapshot snapshot =
                    Objects.requireNonNull(entry.getValue(), "snapshots value");
            BlockEntity blockEntity = level.getBlockEntity(
                    new BlockPos(position.x(), position.y(), position.z()));
            normalized.put(
                    position,
                    blockEntity instanceof KineticBlockEntity
                            ? normalizeKineticSnapshot(snapshot)
                            : snapshot);
        }
        return Collections.unmodifiableMap(normalized);
    }

    public static WorldBlockSnapshot normalizeKineticSnapshot(WorldBlockSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.blockId().namespace().equals("create")
                || snapshot.blockEntityData().isEmpty()) {
            return snapshot;
        }
        JournalData data = snapshot.blockEntityData().orElseThrow();
        if (!data.schemaId().equals(BLOCK_ENTITY_SNBT_SCHEMA)) {
            return snapshot;
        }
        CompoundTag tag;
        try {
            tag = TagParser.parseTag(data.value());
        } catch (CommandSyntaxException exception) {
            return snapshot;
        }
        if (!tag.getString("id").startsWith("create:")
                || !tag.contains(NEEDS_SPEED_UPDATE, Tag.TAG_BYTE)) {
            return snapshot;
        }
        tag.remove(NEEDS_SPEED_UPDATE);
        return new WorldBlockSnapshot(
                snapshot.blockId(),
                snapshot.properties(),
                Optional.of(new JournalData(data.schemaId(), tag.toString())));
    }
}
