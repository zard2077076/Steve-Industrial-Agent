package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.core.player.WorkflowStage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World-local player project state. It is progress state, never execution authority. */
public final class PlayerWorkflowSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_player_workflow";
    public static final int SCHEMA = 2;
    private final Map<UUID, ProjectEntry> entries;

    public PlayerWorkflowSavedData() {
        this(new LinkedHashMap<>());
    }

    private PlayerWorkflowSavedData(Map<UUID, ProjectEntry> entries) {
        this.entries = entries;
    }

    public static PlayerWorkflowSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PlayerWorkflowSavedData::load,
                PlayerWorkflowSavedData::new,
                DATA_NAME);
    }

    public static PlayerWorkflowSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported player workflow schema " + schema);
        }
        LinkedHashMap<UUID, ProjectEntry> loaded = new LinkedHashMap<>();
        ListTag list = root.getList("Projects", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            try {
                ProjectEntry entry = decode((CompoundTag) raw, schema);
                loaded.put(entry.playerId(), entry);
            } catch (IllegalArgumentException ignored) {
                // Fail closed per malformed entry without discarding other players' projects.
            }
        }
        return new PlayerWorkflowSavedData(loaded);
    }

    public Optional<ProjectEntry> entry(UUID playerId) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(playerId, "playerId")));
    }

    public void put(ProjectEntry entry) {
        entries.put(Objects.requireNonNull(entry, "entry").playerId(), entry);
        setDirty();
    }

    public void remove(UUID playerId) {
        if (entries.remove(Objects.requireNonNull(playerId, "playerId")) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag list = new ListTag();
        entries.values().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.playerId().toString()))
                .map(PlayerWorkflowSavedData::encode)
                .forEach(list::add);
        root.put("Projects", list);
        return root;
    }

    private static CompoundTag encode(ProjectEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("ProjectId", entry.projectId());
        tag.putUUID("PlayerId", entry.playerId());
        tag.putString("Target", entry.target().toString());
        tag.putLong("Quantity", entry.quantity());
        tag.putString("Dimension", entry.dimension().toString());
        tag.putString("Stage", entry.stage().name());
        tag.putString("ProductionMode", entry.productionMode().name());
        tag.putString("ExecutionMode", entry.executionMode().name());
        tag.putString("LayoutVariant", entry.layoutVariant().name());
        tag.putString("Orientation", entry.orientation().name());
        tag.putBoolean("HasAnchor", entry.anchor() != null);
        if (entry.anchor() != null) {
            tag.putInt("AnchorX", entry.anchor().x());
            tag.putInt("AnchorY", entry.anchor().y());
            tag.putInt("AnchorZ", entry.anchor().z());
        }
        tag.putLong("Nonce", entry.nonce());
        tag.putLong("CreatedAt", entry.createdAt());
        tag.putLong("UpdatedAt", entry.updatedAt());
        tag.putString("StatusCode", entry.statusCode());
        tag.putString("PlanHash", entry.planHash());
        tag.putString("SiteSnapshotHash", entry.siteSnapshotHash());
        return tag;
    }

    private static ProjectEntry decode(CompoundTag tag, int schema) {
        BlockPos3i anchor = tag.getBoolean("HasAnchor")
                ? new BlockPos3i(tag.getInt("AnchorX"), tag.getInt("AnchorY"), tag.getInt("AnchorZ"))
                : null;
        WorkflowStage stage = WorkflowStage.valueOf(tag.getString("Stage"));
        String statusCode = tag.getString("StatusCode");
        if (schema < 2 && stage == WorkflowStage.CONSTRUCTION
                && "CONSTRUCTION_MATERIAL_SOURCE_REQUIRED".equals(statusCode)) {
            stage = WorkflowStage.MATERIAL_SOURCE_SELECTION;
            statusCode = "MATERIAL_SOURCE_SELECTION_REQUIRED";
        }
        return new ProjectEntry(
                tag.getUUID("ProjectId"),
                tag.getUUID("PlayerId"),
                ResourceId.parse(tag.getString("Target")),
                tag.getLong("Quantity"),
                ResourceId.parse(tag.getString("Dimension")),
                stage,
                ProductionMode.valueOf(tag.getString("ProductionMode")),
                PlayerExecutionMode.valueOf(tag.getString("ExecutionMode")),
                LayoutVariant.valueOf(tag.getString("LayoutVariant")),
                QuarterTurn.valueOf(tag.getString("Orientation")),
                anchor,
                tag.getLong("Nonce"),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"),
                statusCode,
                tag.getString("PlanHash"),
                tag.getString("SiteSnapshotHash"));
    }

    public record ProjectEntry(
            UUID projectId,
            UUID playerId,
            ResourceId target,
            long quantity,
            ResourceId dimension,
            WorkflowStage stage,
            ProductionMode productionMode,
            PlayerExecutionMode executionMode,
            LayoutVariant layoutVariant,
            QuarterTurn orientation,
            BlockPos3i anchor,
            long nonce,
            long createdAt,
            long updatedAt,
            String statusCode,
            String planHash,
            String siteSnapshotHash) {
        public ProjectEntry {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(productionMode, "productionMode");
            Objects.requireNonNull(executionMode, "executionMode");
            Objects.requireNonNull(layoutVariant, "layoutVariant");
            Objects.requireNonNull(orientation, "orientation");
            Objects.requireNonNull(statusCode, "statusCode");
            Objects.requireNonNull(planHash, "planHash");
            Objects.requireNonNull(siteSnapshotHash, "siteSnapshotHash");
            if (quantity < 1 || quantity > 64 || nonce < 1 || createdAt < 0 || updatedAt < createdAt
                    || statusCode.length() > 96 || !validOptionalHash(planHash)
                    || !validOptionalHash(siteSnapshotHash)) {
                throw new IllegalArgumentException("invalid player workflow project");
            }
        }

        public ProjectEntry withStage(WorkflowStage next, long nextNonce, long now, String code) {
            return new ProjectEntry(projectId, playerId, target, quantity, dimension, next,
                    productionMode, executionMode, layoutVariant, orientation, anchor,
                    nextNonce, createdAt, now, code, planHash, siteSnapshotHash);
        }

        public ProjectEntry withPlacement(
                LayoutVariant variant,
                QuarterTurn turn,
                BlockPos3i position,
                WorkflowStage next,
                long nextNonce,
                long now,
                String code,
                String nextPlanHash,
                String nextSiteSnapshotHash) {
            return new ProjectEntry(projectId, playerId, target, quantity, dimension, next,
                    productionMode, executionMode, variant, turn, position,
                    nextNonce, createdAt, now, code, nextPlanHash, nextSiteSnapshotHash);
        }

        public ProjectEntry resetForPlacement(long nextNonce, long now) {
            return new ProjectEntry(projectId, playerId, target, quantity, dimension,
                    WorkflowStage.PLACEMENT_PREVIEW, productionMode, executionMode,
                    layoutVariant, orientation, null, nextNonce, createdAt, now,
                    "PLACEMENT_PREVIEW_READY", "", "");
        }

        private static boolean validOptionalHash(String value) {
            return value.isEmpty() || value.matches("[0-9a-f]{64}");
        }
    }
}
