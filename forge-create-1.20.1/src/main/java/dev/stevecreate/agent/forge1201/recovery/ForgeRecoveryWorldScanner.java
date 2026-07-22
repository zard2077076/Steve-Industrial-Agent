package dev.stevecreate.agent.forge1201.recovery;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.JournalData;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

/** Stable Forge-only exact snapshot scanner used before any recovery decision. */
public final class ForgeRecoveryWorldScanner {
    public static final ResourceId BLOCK_ENTITY_SNBT_SCHEMA =
            new ResourceId("minecraft", "snbt/block_entity");

    private ForgeRecoveryWorldScanner() {
    }

    public static AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> scan(
            ServerLevel level,
            List<BlockPos3i> positions) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(positions, "positions");
        if (!level.getServer().isSameThread()) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.WRONG_THREAD,
                    "Recovery rescan must run on the authoritative server thread");
        }
        if (positions.size() > WorldChangeJournal.MAX_ENTRIES) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.SCAN_LIMIT_EXCEEDED,
                    "Recovery rescan position count exceeds "
                            + WorldChangeJournal.MAX_ENTRIES);
        }
        Set<BlockPos3i> unique = new HashSet<>();
        Map<BlockPos3i, WorldBlockSnapshot> snapshots = new LinkedHashMap<>();
        for (BlockPos3i position : positions) {
            BlockPos3i value = Objects.requireNonNull(position, "positions element");
            if (!unique.add(value)) {
                return new AdapterResult.Failure<>(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Recovery rescan contains duplicate position " + value);
            }
            if (!level.hasChunk(value.x() >> 4, value.z() >> 4)) {
                return new AdapterResult.Failure<>(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "Recovery rescan target chunk is not loaded at " + value);
            }
            snapshots.put(value, capture(level, value));
        }
        return new AdapterResult.Success<>(Collections.unmodifiableMap(snapshots));
    }

    private static WorldBlockSnapshot capture(ServerLevel level, BlockPos3i position) {
        BlockPos blockPos = new BlockPos(position.x(), position.y(), position.z());
        BlockState state = level.getBlockState(blockPos);
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            throw new IllegalStateException(
                    "Recovery scan found an unregistered block at " + position);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propertyValue(state, property));
        }
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        Optional<JournalData> blockEntityData = blockEntity == null
                ? Optional.empty()
                : Optional.of(new JournalData(
                        BLOCK_ENTITY_SNBT_SCHEMA,
                        blockEntity.saveWithFullMetadata().toString()));
        return new WorldBlockSnapshot(
                ResourceId.parse(key.toString()),
                properties,
                blockEntityData);
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state,
            Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
