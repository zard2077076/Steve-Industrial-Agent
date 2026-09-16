package dev.stevecreate.agent.acceptance.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.stevecreate.agent.forge1201.player.client.PlacementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;

/** Read-only client and Screen evidence. */
final class ScreenInspector {
    private ScreenInspector() {}

    static Map<String, Object> clientStatus(Minecraft minecraft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "RUNNING");
        result.put("world_name", ClientStateProbe.worldName(minecraft));
        result.put("screen_class", minecraft.screen == null
                ? null : minecraft.screen.getClass().getSimpleName());
        result.put("screen_title", minecraft.screen == null
                ? null : minecraft.screen.getTitle().getString());
        result.put("game_root", minecraft.gameDirectory.getAbsolutePath());
        result.put("singleplayer", minecraft.hasSingleplayerServer());
        result.put("player_ready", minecraft.level != null
                && minecraft.player != null && minecraft.getConnection() != null);
        if (minecraft.level != null) {
            result.put("dimension", minecraft.level.dimension().location().toString());
        }
        if (minecraft.player != null) {
            result.put("player", minecraft.player.getGameProfile().getName());
            result.put("player_x", minecraft.player.getX());
            result.put("player_y", minecraft.player.getY());
            result.put("player_z", minecraft.player.getZ());
            result.put("main_hand_item", BuiltInRegistries.ITEM.getKey(
                    minecraft.player.getMainHandItem().getItem()).toString());
        }
        return result;
    }

    static Map<String, Object> screen(Minecraft minecraft) {
        Screen screen = minecraft.screen;
        Map<String, Object> result = new LinkedHashMap<>(clientStatus(minecraft));
        if (screen == null) {
            result.put("widgets", List.of());
            result.put("target_rows", List.of());
            Map<String, Object> placement = placementState();
            if (!placement.isEmpty()) result.put("placement_state", placement);
            return result;
        }
        result.put("screen_width", screen.width);
        result.put("screen_height", screen.height);
        List<Map<String, Object>> widgets = new ArrayList<>();
        for (WidgetLocator.LocatedWidget located : WidgetLocator.widgets(screen)) {
            var widget = located.widget();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", located.id());
            row.put("type", widget.getClass().getSimpleName());
            row.put("label", widget.getMessage().getString());
            row.put("x", widget.getX());
            row.put("y", widget.getY());
            row.put("width", widget.getWidth());
            row.put("height", widget.getHeight());
            row.put("active", widget.active);
            row.put("visible", widget.visible);
            row.put("focused", widget.isFocused());
            if (widget instanceof EditBox editBox) row.put("value", editBox.getValue());
            widgets.add(row);
        }
        result.put("widgets", widgets);
        result.put("target_rows", WidgetLocator.targetRows(screen));
        Map<String, Object> domainState = domainState(screen);
        if (!domainState.isEmpty()) result.put("domain_state", domainState);
        return result;
    }

    private static Map<String, Object> placementState() {
        if (!PlacementController.active()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", true);
        if (PlacementController.anchor() != null) {
            result.put("anchorX", PlacementController.anchor().getX());
            result.put("anchorY", PlacementController.anchor().getY());
            result.put("anchorZ", PlacementController.anchor().getZ());
        }
        result.put("orientation", PlacementController.orientation().name());
        result.put("variant", PlacementController.variant().name());
        Object preview = PlacementController.preview();
        if (preview != null) {
            result.put("preview", values(preview, List.of(
                    "success", "statusCode", "projectId", "projectNonce", "anchorX",
                    "anchorY", "anchorZ", "orientation", "variant", "safeForConfirmation",
                    "finalized")));
        }
        return result;
    }

    /**
     * Exposes the packet-backed status already rendered by a screen. Reflection keeps
     * the bridge dev-only and avoids adding any production API solely for acceptance.
     */
    private static Map<String, Object> domainState(Screen screen) {
        String screenName = screen.getClass().getSimpleName();
        if ("SiteSurveySummaryScreen".equals(screenName)) {
            return screenFieldState(screen, "preview", List.of(
                    "success", "statusCode", "projectId", "projectNonce", "anchorX", "anchorY",
                    "anchorZ", "orientation", "variant", "minX", "minY", "minZ", "maxX",
                    "maxY", "maxZ", "placeCount", "reuseCount", "clearCount", "protectedCount",
                    "containerCount", "unknownCount", "hazardCount", "safeForConfirmation",
                    "finalized"));
        }
        if ("DemolitionApprovalScreen".equals(screenName)) {
            Map<String, Object> result = screenFieldState(screen, "summary", List.of(
                    "success", "statusCode", "projectId", "projectNonce", "target", "quantity",
                    "anchorX", "anchorY", "anchorZ", "orientation", "layoutVariant",
                    "executionMode", "placeCount", "reuseCount", "clearCount", "protectedCount",
                    "containerCount", "unknownCount", "hazardCount", "requiredInputs",
                    "recommendedBots", "phaseEstimate", "riskLevel", "approvable", "safetyPolicy"));
            result.put("approval_active", booleanField(screen, "tokenIdentity", false,
                    value -> value != null && !value.toString().isBlank()));
            result.put("approval_expires_at_millis", field(screen, "expiresAtMillis", 0L));
            result.put("status_code", field(screen, "statusCode", "REVIEW_REQUIRED"));
            return result;
        }
        if ("ClearingReadyScreen".equals(screenName)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", field(screen, "status", "READY"));
            result.put("project", projectState(field(screen, "project", null)));
            return result;
        }
        if ("MaterialSourceScreen".equals(screenName)) {
            Map<String, Object> result = screenFieldState(screen, "snapshot", List.of(
                    "success", "statusCode", "sourceCount", "reserved", "sufficient",
                    "allowSalvage"));
            Object snapshot = screenField(screen, "snapshot");
            if (snapshot != null) {
                result.put("project", projectState(invoke(snapshot, "project")));
                Object lines = invoke(snapshot, "lines");
                if (lines instanceof List<?> values) {
                    try {
                        result.put("lines", wireRows(values));
                    } catch (ReflectiveOperationException error) {
                        throw new BridgeRefusal("MATERIAL_SOURCE_LINES_INSPECTION_FAILED",
                                error.getClass().getSimpleName());
                    }
                }
                Object report = invoke(snapshot, "report");
                if (report != null) result.put("report", values(report, List.of(
                        "planned", "withdrawn", "consumed", "returned", "salvageTransferred",
                        "observedOutput", "expectedOutput", "duplicateWithdrawals",
                        "duplicateReturns", "unaccountedItems", "privateItemsTouched",
                        "balanced")));
            }
            result.put("allow_salvage", field(screen, "allowSalvage", false));
            return result;
        }
        if ("ConstructionProgressScreen".equals(screenName)) {
            Map<String, Object> result = screenFieldState(screen, "status", List.of(
                    "success", "statusCode", "active", "paused", "prepared", "phase",
                    "completedTargets", "totalTargets", "mutations", "salvageCollected",
                    "salvageDelivered", "activeBots", "safeToCancel"));
            Object status = screenField(screen, "status");
            if (status != null) result.put("project", projectState(invoke(status, "project")));
            return result;
        }
        if ("CompletionReportScreen".equals(screenName)) {
            Map<String, Object> result = screenFieldState(screen, "snapshot", List.of(
                    "success", "statusCode", "sourceCount", "reserved", "sufficient",
                    "allowSalvage"));
            Object snapshot = screenField(screen, "snapshot");
            if (snapshot != null) {
                result.put("project", projectState(invoke(snapshot, "project")));
                Object report = invoke(snapshot, "report");
                if (report != null) result.put("report", values(report, List.of(
                        "planned", "withdrawn", "consumed", "returned", "salvageTransferred",
                        "observedOutput", "expectedOutput", "duplicateWithdrawals",
                        "duplicateReturns", "unaccountedItems", "privateItemsTouched",
                        "balanced")));
            }
            return result;
        }
        if (!"MetalPressOrderScreen".equals(screenName)
                && !"CompositeOrderScreen".equals(screenName)
                && !"CompositeCompletionScreen".equals(screenName)) return Map.of();
        try {
            Field field = screen.getClass().getDeclaredField(
                    "CompositeCompletionScreen".equals(screenName) ? "report" : "status");
            field.setAccessible(true);
            Object status = field.get(screen);
            if (status == null) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            List<String> accessors = "MetalPressOrderScreen".equals(screenName)
                    ? List.of(
                    "success", "operationCode", "orderId", "stage", "statusCode", "progress",
                    "planned", "withdrawn", "consumed", "returned", "energyConsumed",
                    "outputCount", "reportPresent", "paused", "safeToCancel", "ledgerBalanced",
                    "baselineRestored", "unaccountedItems", "duplicateWithdrawals",
                    "duplicateEnergy", "duplicateOutputs", "duplicateReturns", "privateItemsTouched",
                    "sourceX", "sourceY", "sourceZ", "originX", "originY", "originZ")
                    : "CompositeOrderScreen".equals(screenName)
                    ? List.of("success", "operationCode", "projectId", "graphId",
                            "graphFingerprint", "generation", "nodes", "buffers")
                    : List.of("success", "operationCode", "projectId", "orderType", "target",
                            "stage", "generation", "rows", "salvageTransferred",
                            "duplicateWithdrawals", "duplicateReturns",
                            "duplicateEnergySettlements", "duplicateOutputs", "unaccountedItems",
                            "privateItemsTouched", "materialLedgerBalanced", "baselineRestored",
                            "evidenceHash");
            for (String accessor : accessors) {
                Method method = status.getClass().getMethod(accessor);
                Object value = method.invoke(status);
                if ("nodes".equals(accessor) || "buffers".equals(accessor)
                        || "rows".equals(accessor)) {
                    value = wireRows((List<?>) value);
                }
                result.put(accessor, value);
            }
            return result;
        } catch (ReflectiveOperationException error) {
            throw new BridgeRefusal(screenName + "_STATUS_INSPECTION_FAILED",
                    error.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> screenFieldState(Screen screen, String fieldName,
            List<String> accessors) {
        Object value = screenField(screen, fieldName);
        return value == null ? new LinkedHashMap<>() : values(value, accessors);
    }

    private static Object screenField(Screen screen, String fieldName) {
        try {
            Field field = screen.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(screen);
        } catch (ReflectiveOperationException error) {
            throw new BridgeRefusal(screen.getClass().getSimpleName() + "_STATE_INSPECTION_FAILED",
                    error.getClass().getSimpleName());
        }
    }

    private static Object field(Object owner, String fieldName, Object fallback) {
        if (owner == null) return fallback;
        try {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(owner);
            return value == null ? fallback : value;
        } catch (ReflectiveOperationException error) {
            return fallback;
        }
    }

    private static Object booleanField(Object owner, String fieldName, Object fallback,
            java.util.function.Predicate<Object> predicate) {
        Object value = field(owner, fieldName, null);
        return value == null ? fallback : predicate.test(value);
    }

    private static Object invoke(Object owner, String accessor) {
        if (owner == null) return null;
        try {
            return owner.getClass().getMethod(accessor).invoke(owner);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static Map<String, Object> values(Object owner, List<String> accessors) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (owner == null) return result;
        for (String accessor : accessors) {
            Object value = invoke(owner, accessor);
            if (value != null) result.put(accessor, value);
        }
        return result;
    }

    private static Map<String, Object> projectState(Object project) {
        return values(project, List.of("projectId", "target", "quantity", "stage", "statusCode",
                "projectNonce", "hasAnchor", "anchorX", "anchorY", "anchorZ", "orientation",
                "layoutVariant", "executionMode"));
    }

    private static List<Map<String, Object>> wireRows(List<?> values)
            throws ReflectiveOperationException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String accessor : List.of("nodeId", "status", "edgeId", "resource", "quantity",
                    "planned", "withdrawn", "consumed", "returned", "output", "energy", "fluid")) {
                try {
                    Method method = value.getClass().getMethod(accessor);
                    row.put(accessor, method.invoke(value));
                } catch (NoSuchMethodException ignored) {
                    // NodeWire and BufferWire intentionally expose different fields.
                }
            }
            rows.add(row);
        }
        return rows;
    }
}
