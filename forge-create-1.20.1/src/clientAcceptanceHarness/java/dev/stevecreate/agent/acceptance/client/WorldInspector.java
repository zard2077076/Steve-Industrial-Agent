package dev.stevecreate.agent.acceptance.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.stevecreate.agent.forge1201.command.PlayerCreateClientAcceptanceFixture;
import dev.stevecreate.agent.forge1201.command.CompositeClientAcceptanceFixture;
import net.minecraft.nbt.CompoundTag;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Read-only evidence from the real client world. This intentionally observes only
 * chunks and entities already available to the client; it never asks a server
 * service for hidden state.
 */
final class WorldInspector {
    private WorldInspector() {}

    static Map<String, Object> inspect(Minecraft minecraft, BlockPos center, int radius) {
        ClientStateProbe.requireAcceptanceWorld(minecraft,
                "Steve Agent Mac Acceptance");
        if (minecraft.level == null || minecraft.player == null) {
            throw new BridgeRefusal("PLAYER_NOT_READY");
        }
        // Keep the JSON evidence bounded; refuse larger probes instead of silently
        // truncating a real-client observation.
        if (radius < 0 || radius > 3) throw new BridgeRefusal("WORLD_RADIUS_INVALID");
        if (minecraft.player.distanceToSqr(center.getX() + .5D, center.getY() + .5D,
                center.getZ() + .5D) > 96D * 96D) {
            throw new BridgeRefusal("WORLD_PROBE_OUT_OF_RANGE");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INSPECTED");
        result.put("center", position(center));
        result.put("radius", radius);
        result.put("game_time", minecraft.level.getGameTime());

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            var state = minecraft.level.getBlockState(position);
            if (state.isAir()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", position(position));
            row.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            BlockEntity entity = minecraft.level.getBlockEntity(position);
            if (entity instanceof Container container) row.put("container", container(container));
            fixtureMarker(entity).ifPresent(marker -> row.put("fixture", marker));
            blocks.add(row);
        }
        result.put("blocks", blocks);

        List<Map<String, Object>> entities = new ArrayList<>();
        AABB box = new AABB(center).inflate(radius + 1D);
        for (Entity entity : minecraft.level.getEntities(null, box)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", entity.getUUID().toString());
            row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            row.put("x", entity.getX());
            row.put("y", entity.getY());
            row.put("z", entity.getZ());
            if (entity instanceof ConstructionBotEntity bot) {
                row.put("role", bot.role().serializedName());
                row.put("tags", bot.getTags().stream().sorted().limit(16).toList());
            }
            if (entity instanceof ItemEntity item) {
                row.put("item", item(item.getItem()));
            }
            entities.add(row);
        }
        result.put("entities", entities);
        return result;
    }

    private static Map<String, Object> position(BlockPos position) {
        return Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ());
    }

    private static Map<String, Object> container(Container container) {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int index = 0; index < container.getContainerSize(); index++) {
            ItemStack stack = container.getItem(index);
            if (stack.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", index);
            row.put("item", item(stack));
            slots.add(row);
        }
        return Map.of("size", container.getContainerSize(), "slots", slots);
    }

    private static Map<String, Object> item(ItemStack stack) {
        return Map.of("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                "count", stack.getCount());
    }

    private static java.util.Optional<Map<String, Object>> fixtureMarker(BlockEntity entity) {
        if (entity == null) return java.util.Optional.empty();
        CompoundTag root = entity.getPersistentData();
        String kind;
        CompoundTag marker;
        if (root.contains(PlayerCreateClientAcceptanceFixture.FIXTURE_MARKER_KEY)) {
            kind = "steve_agent_c03_fixture";
            marker = root.getCompound(PlayerCreateClientAcceptanceFixture.FIXTURE_MARKER_KEY);
        } else if (root.contains(CompositeClientAcceptanceFixture.FIXTURE_MARKER_KEY)) {
            kind = "steve_agent_composite_fixture";
            marker = root.getCompound(CompositeClientAcceptanceFixture.FIXTURE_MARKER_KEY);
        } else {
            return java.util.Optional.empty();
        }
        String role = marker.getString("role");
        String target = marker.getString("target");
        String nonce = marker.getString("nonce");
        long quantity = marker.getLong("quantity");
        long preparedAt = marker.getLong("preparedAt");
        String planHash = marker.getString("planHash");
        String orderType = marker.getString("orderType");
        boolean c03 = kind.equals("steve_agent_c03_fixture")
                && (role.equals("source") || role.equals("salvage"))
                && planHash.length() == 64;
        boolean composite = kind.equals("steve_agent_composite_fixture")
                && (role.equals("primary") || role.equals("secondary"))
                && !orderType.isBlank();
        if ((!c03 && !composite) || target.isBlank() || nonce.length() != 32
                || quantity < 1 || preparedAt < 0) {
            return java.util.Optional.empty();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("role", role);
        result.put("target", target);
        result.put("quantity", quantity);
        result.put("nonce", nonce);
        result.put("preparedAt", preparedAt);
        if (c03) result.put("planHash", planHash);
        if (composite) result.put("orderType", orderType);
        return java.util.Optional.of(result);
    }
}
