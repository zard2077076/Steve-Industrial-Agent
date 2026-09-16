package dev.stevecreate.agent.acceptance.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import dev.stevecreate.agent.forge1201.player.client.PlacementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import dev.stevecreate.agent.forge1201.player.client.MaterialSourceSelectionController;
import dev.stevecreate.agent.forge1201.player.client.SalvageSelectionController;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Uses the actual Screen widgets and client interaction methods; never a server service. */
final class InputInjector {
    private InputInjector() {}

    static Map<String, Object> press(Minecraft minecraft, String widgetId) {
        Screen screen = requireScreen(minecraft);
        WidgetLocator.LocatedWidget located = WidgetLocator.require(screen, widgetId);
        if (!(located.widget() instanceof Button button)) {
            throw new BridgeRefusal("WIDGET_NOT_BUTTON", widgetId);
        }
        if (!button.visible || !button.active) {
            throw new BridgeRefusal("WIDGET_NOT_ACTIONABLE", widgetId);
        }
        String before = screen.getClass().getSimpleName();
        button.onPress();
        return Map.of("status", "PRESSED", "widget_id", widgetId,
                "screen_before", before);
    }

    static Map<String, Object> text(Minecraft minecraft, String widgetId, String value) {
        if (value == null || value.length() > 256 || value.contains("\n") || value.contains("\r")) {
            throw new BridgeRefusal("WIDGET_TEXT_INVALID", widgetId);
        }
        Screen screen = requireScreen(minecraft);
        WidgetLocator.LocatedWidget located = WidgetLocator.require(screen, widgetId);
        if (!(located.widget() instanceof EditBox editBox)) {
            throw new BridgeRefusal("WIDGET_NOT_TEXT", widgetId);
        }
        editBox.setFocused(true);
        editBox.setValue(value);
        return Map.of("status", "TEXT_ENTERED", "widget_id", widgetId,
                "value", editBox.getValue(), "screen", screen.getClass().getSimpleName());
    }

    static Map<String, Object> closeScreen(Minecraft minecraft) {
        Screen screen = requireScreen(minecraft);
        String before = screen.getClass().getSimpleName();
        screen.onClose();
        // PauseScreen's onClose implementation can leave the screen installed when
        // the integrated client is already paused.  The bridge's semantic close
        // command means "leave this visible Screen"; finish that operation without
        // synthesising a gameplay packet or clicking a coordinate.
        if (minecraft.screen == screen) minecraft.setScreen(null);
        return Map.of("status", "CLOSED", "screen_before", before);
    }

    static Map<String, Object> selectTarget(Minecraft minecraft, String target) {
        Screen screen = requireScreen(minecraft);
        if (!"GoalPickerScreen".equals(screen.getClass().getSimpleName())) {
            throw new BridgeRefusal("GOAL_PICKER_NOT_OPEN", screen.getClass().getSimpleName());
        }
        if (target == null || target.length() > 128 || !target.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new BridgeRefusal("TARGET_ID_INVALID", String.valueOf(target));
        }
        List<Map<String, Object>> rows = WidgetLocator.targetRows(screen);
        Map<String, Object> selected = rows.stream()
                .filter(row -> target.equals(row.get("target"))).findFirst().orElse(null);
        if (selected == null) {
            WidgetLocator.LocatedWidget search = WidgetLocator.require(screen, "search");
            if (!(search.widget() instanceof EditBox editBox)) {
                throw new BridgeRefusal("GOAL_SEARCH_NOT_TEXT");
            }
            String query = target.substring(target.indexOf(':') + 1);
            editBox.setFocused(true);
            if (!query.equals(editBox.getValue())) editBox.setValue(query);
            return Map.of("status", "TARGET_RESULTS_PENDING", "target", target, "query", query);
        }
        try {
            int targetIndex = (int) selected.get("index");
            Field scrollField = screen.getClass().getDeclaredField("scroll");
            Method visibleRowsMethod = screen.getClass().getDeclaredMethod("visibleRows");
            scrollField.setAccessible(true);
            visibleRowsMethod.setAccessible(true);
            int visibleRows = (int) visibleRowsMethod.invoke(screen);
            int scroll = scrollField.getInt(screen);
            while (targetIndex < scroll) {
                screen.mouseScrolled(screen.width / 2.0, 66.0, 1.0);
                scroll--;
            }
            while (targetIndex >= scroll + visibleRows) {
                screen.mouseScrolled(screen.width / 2.0, 66.0, -1.0);
                scroll++;
            }
            int row = targetIndex - scroll;
            double x = screen.width / 2.0;
            double y = 55.0 + row * 22.0 + 10.0;
            if (!screen.mouseClicked(x, y, 0)) {
                throw new BridgeRefusal("TARGET_ROW_CLICK_REFUSED", target);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "TARGET_SELECTED");
            result.put("target", target);
            result.put("row", row);
            result.put("screen", screen.getClass().getSimpleName());
            return result;
        } catch (ReflectiveOperationException error) {
            throw new BridgeRefusal("TARGET_SELECTION_FAILED", error.getClass().getSimpleName());
        }
    }

    static Map<String, Object> useMainHand(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.gameMode == null) {
            throw new BridgeRefusal("PLAYER_NOT_READY");
        }
        String item = minecraft.player.getMainHandItem().getItem().toString();
        var interaction = minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        return Map.of("status", "USED_MAIN_HAND", "item", item,
                "interaction_result", interaction.toString());
    }

    static Map<String, Object> confirmPlacement(Minecraft minecraft) {
        ClientStateProbe.requireAcceptanceWorld(minecraft, "Steve Agent Mac Acceptance");
        if (!PlacementController.confirmForAcceptance()) {
            throw new BridgeRefusal("PLACEMENT_ANCHOR_NOT_SELECTED");
        }
        return Map.of("status", "PLACEMENT_CONFIRM_REQUESTED");
    }

    /** Rotate the real local player and refresh the normal client hit result. */
    static Map<String, Object> lookAt(Minecraft minecraft, BlockPos position) {
        if (minecraft.player == null || minecraft.level == null) {
            throw new BridgeRefusal("PLAYER_NOT_READY");
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 target = Vec3.atCenterOf(position);
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * (180.0D / Math.PI));
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.yBodyRot = yaw;
        minecraft.gameRenderer.pick(1.0F);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "LOOKED_AT");
        result.put("position", position.toShortString());
        result.put("yaw", yaw);
        result.put("pitch", pitch);
        result.put("hit_result", minecraft.hitResult == null
                ? null : minecraft.hitResult.getType().toString());
        if (minecraft.hitResult instanceof BlockHitResult hit) {
            result.put("hit_block", hit.getBlockPos().toShortString());
            result.put("hit_face", hit.getDirection().getName());
        }
        return result;
    }

    static Map<String, Object> bindSalvage(Minecraft minecraft, BlockPos position) {
        if (!SalvageSelectionController.bindForAcceptance(position)) {
            throw new BridgeRefusal("SALVAGE_BIND_NOT_READY", position.toShortString());
        }
        return Map.of("status", "SALVAGE_BIND_REQUESTED",
                "position", position.toShortString());
    }

    static Map<String, Object> bindMaterialSource(Minecraft minecraft, BlockPos position,
            Direction face) {
        if (!MaterialSourceSelectionController.bindForAcceptance(position, face)) {
            throw new BridgeRefusal("MATERIAL_SOURCE_BIND_NOT_READY", position.toShortString());
        }
        return Map.of("status", "MATERIAL_SOURCE_BIND_REQUESTED",
                "position", position.toShortString(), "face", face.getName());
    }

    /**
     * Sends the same interaction packet as useMainHand, but precedes it with a real
     * client input packet carrying the sneak bit. This is needed for the terminal's
     * source/site gestures; it is deliberately not a server-side service call.
     */
    static Map<String, Object> sneakUseMainHand(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.gameMode == null
                || minecraft.player.connection == null) {
            throw new BridgeRefusal("PLAYER_NOT_READY");
        }
        String item = minecraft.player.getMainHandItem().getItem().toString();
        boolean prior = minecraft.player.input.shiftKeyDown;
        sendSneakInput(minecraft, true);
        try {
            var interaction = minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
            return Map.of("status", "SNEAK_USED_MAIN_HAND", "item", item,
                    "interaction_result", interaction.toString());
        } finally {
            releaseSneakLater(minecraft, prior);
        }
    }

    /**
     * Performs a real use-on packet against a bounded block position. The bridge
     * validates the position before reaching this method; the hit result preserves
     * the normal server-side UseOnContext and packet path.
     */
    static Map<String, Object> sneakUseOn(Minecraft minecraft, BlockPos position,
            Direction face) {
        if (minecraft.player == null || minecraft.gameMode == null
                || minecraft.player.connection == null) {
            throw new BridgeRefusal("PLAYER_NOT_READY");
        }
        String item = minecraft.player.getMainHandItem().getItem().toString();
        boolean prior = minecraft.player.input.shiftKeyDown;
        sendSneakInput(minecraft, true);
        try {
            Vec3 hit = Vec3.atCenterOf(position).add(
                    face.getStepX() * 0.5D, face.getStepY() * 0.5D,
                    face.getStepZ() * 0.5D);
            BlockHitResult target = new BlockHitResult(hit, face, position, false);
            var interaction = minecraft.gameMode.useItemOn(
                    minecraft.player, InteractionHand.MAIN_HAND, target);
            return Map.of("status", "SNEAK_USED_ON", "item", item,
                    "position", position.toShortString(), "face", face.getName(),
                    "interaction_result", interaction.toString());
        } finally {
            releaseSneakLater(minecraft, prior);
        }
    }

    private static void releaseSneakLater(Minecraft minecraft, boolean prior) {
        if (prior) return;
        CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute(
                () -> minecraft.execute(() -> sendSneakInput(minecraft, false)));
    }

    private static void sendSneakInput(Minecraft minecraft, boolean down) {
        minecraft.player.input.shiftKeyDown = down;
        minecraft.options.keyShift.setDown(down);
        minecraft.player.connection.send(new ServerboundPlayerCommandPacket(
                minecraft.player,
                down ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                        : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        minecraft.player.connection.send(new ServerboundPlayerInputPacket(
                minecraft.player.input.leftImpulse, minecraft.player.input.forwardImpulse,
                minecraft.player.input.jumping, down));
    }

    private static Screen requireScreen(Minecraft minecraft) {
        if (minecraft.screen == null) throw new BridgeRefusal("SCREEN_NOT_OPEN");
        return minecraft.screen;
    }
}
