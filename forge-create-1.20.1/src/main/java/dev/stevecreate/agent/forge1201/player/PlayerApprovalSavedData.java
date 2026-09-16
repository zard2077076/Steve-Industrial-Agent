package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.siteprep.ApprovedObstacle;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalContext;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalState;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalToken;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Exact world-local approval persistence; token state remains one-time and revocable. */
public final class PlayerApprovalSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_player_approvals";
    public static final int SCHEMA = 1;
    private final Map<UUID, DemolitionApprovalToken> tokens = new LinkedHashMap<>();

    public static PlayerApprovalSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PlayerApprovalSavedData::load,
                PlayerApprovalSavedData::new,
                DATA_NAME);
    }

    public static PlayerApprovalSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema != 0 && schema != SCHEMA) {
            throw new IllegalStateException("Unsupported player approval schema " + schema);
        }
        PlayerApprovalSavedData data = new PlayerApprovalSavedData();
        for (Tag raw : root.getList("Approvals", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag tag = (CompoundTag) raw;
                data.tokens.put(tag.getUUID("ProjectId"), decodeToken(tag.getCompound("Token")));
            } catch (IllegalArgumentException ignored) {
                // Malformed authority is dropped rather than repaired or guessed.
            }
        }
        return data;
    }

    public Optional<DemolitionApprovalToken> token(UUID projectId) {
        return Optional.ofNullable(tokens.get(projectId));
    }

    public void put(UUID projectId, DemolitionApprovalToken token) {
        tokens.put(projectId, token);
        setDirty();
    }

    public void remove(UUID projectId) {
        if (tokens.remove(projectId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag list = new ListTag();
        tokens.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("ProjectId", entry.getKey());
            tag.put("Token", encodeToken(entry.getValue()));
            list.add(tag);
        });
        root.put("Approvals", list);
        return root;
    }

    private static CompoundTag encodeToken(DemolitionApprovalToken token) {
        CompoundTag tag = new CompoundTag();
        tag.putString("TokenIdentity", token.tokenIdentity());
        tag.putString("WorldIdentity", token.worldIdentity());
        tag.putString("Dimension", token.dimension().toString());
        tag.putString("PlanHash", token.planHash());
        tag.putString("SiteSnapshotHash", token.siteSnapshotHash());
        tag.putString("ApprovalHash", token.approvalHash());
        tag.putString("PlayerIdentity", token.playerIdentity());
        tag.put("Context", encodeContext(token.context()));
        ListTag obstacles = new ListTag();
        for (ApprovedObstacle obstacle : token.approvedObstacles()) {
            CompoundTag value = new CompoundTag();
            value.putString("ObstacleId", obstacle.obstacleId());
            value.putInt("X", obstacle.position().x());
            value.putInt("Y", obstacle.position().y());
            value.putInt("Z", obstacle.position().z());
            value.putString("State", obstacle.blockStateFingerprint());
            value.putString("Classification", obstacle.classification().name());
            obstacles.add(value);
        }
        tag.put("Obstacles", obstacles);
        tag.putLong("IssuedAt", token.issuedAt().toEpochMilli());
        tag.putLong("ExpiresAt", token.expiresAt().toEpochMilli());
        tag.putInt("MaximumMutations", token.maximumMutations());
        tag.putString("State", token.state().name());
        return tag;
    }

    private static DemolitionApprovalToken decodeToken(CompoundTag tag) {
        ArrayList<ApprovedObstacle> obstacles = new ArrayList<>();
        for (Tag raw : tag.getList("Obstacles", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            obstacles.add(new ApprovedObstacle(value.getString("ObstacleId"),
                    new BlockPos3i(value.getInt("X"), value.getInt("Y"), value.getInt("Z")),
                    value.getString("State"),
                    ObstacleClassification.valueOf(value.getString("Classification"))));
        }
        return new DemolitionApprovalToken(tag.getString("TokenIdentity"),
                tag.getString("WorldIdentity"), ResourceId.parse(tag.getString("Dimension")),
                tag.getString("PlanHash"), tag.getString("SiteSnapshotHash"),
                tag.getString("ApprovalHash"), tag.getString("PlayerIdentity"),
                decodeContext(tag.getCompound("Context")), obstacles,
                Instant.ofEpochMilli(tag.getLong("IssuedAt")),
                Instant.ofEpochMilli(tag.getLong("ExpiresAt")),
                tag.getInt("MaximumMutations"),
                DemolitionApprovalState.valueOf(tag.getString("State")));
    }

    private static CompoundTag encodeContext(DemolitionApprovalContext context) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ProjectIdentity", context.projectIdentity());
        tag.putString("Target", context.target().toString());
        tag.putLong("Quantity", context.quantity());
        tag.putInt("AnchorX", context.anchor().x());
        tag.putInt("AnchorY", context.anchor().y());
        tag.putInt("AnchorZ", context.anchor().z());
        tag.putString("Orientation", context.orientation().name());
        tag.putString("LayoutVariant", context.layoutVariant().name());
        tag.putString("RegionAuthorizationHash", context.regionAuthorizationHash());
        tag.putString("SafetyPolicy", context.safetyPolicy());
        tag.putString("ExecutionMode", context.executionMode().name());
        tag.putBoolean("PlayerWorkflowBound", context.playerWorkflowBound());
        tag.putString("ContextHash", context.contextHash());
        return tag;
    }

    private static DemolitionApprovalContext decodeContext(CompoundTag tag) {
        return new DemolitionApprovalContext(tag.getString("ProjectIdentity"),
                ResourceId.parse(tag.getString("Target")), tag.getLong("Quantity"),
                new BlockPos3i(tag.getInt("AnchorX"), tag.getInt("AnchorY"), tag.getInt("AnchorZ")),
                QuarterTurn.valueOf(tag.getString("Orientation")),
                LayoutVariant.valueOf(tag.getString("LayoutVariant")),
                tag.getString("RegionAuthorizationHash"), tag.getString("SafetyPolicy"),
                PlayerExecutionMode.valueOf(tag.getString("ExecutionMode")),
                tag.getBoolean("PlayerWorkflowBound"), tag.getString("ContextHash"));
    }
}
