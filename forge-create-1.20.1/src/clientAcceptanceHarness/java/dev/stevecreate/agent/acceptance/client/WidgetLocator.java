package dev.stevecreate.agent.acceptance.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Semantic selectors over the actual widgets owned by the current Screen. */
final class WidgetLocator {
    private static final Map<String, List<String>> KNOWN = Map.ofEntries(
            Map.entry("EngineerTerminalScreen", List.of(
                    "new_line", "continue_project", "manage_project", "remove_owned")),
            Map.entry("GoalPickerScreen", List.of(
                    "search", "quantity", "execution_mode", "select_target", "back")),
            Map.entry("MaterialSourceScreen", List.of(
                    "confirm_materials", "add_material_source", "reset_material_sources",
                    "cancel_project", "allow_salvage")),
            Map.entry("ConstructionProgressScreen", List.of(
                    "pause_project", "resume_project", "cancel_project")),
            Map.entry("DemolitionApprovalScreen", List.of(
                    "approve_clear", "select_salvage", "reselect")),
            Map.entry("MetalPressOrderScreen", List.of(
                    "refresh_order", "cancel_order", "close_order")),
            Map.entry("CompositeOrderScreen", List.of(
                    "refresh_order", "cancel_order", "close_order")),
            Map.entry("CompositeCompletionScreen", List.of("close_report")),
            Map.entry("CompletionReportScreen", List.of("close_report")),
            Map.entry("ClearingReadyScreen", List.of("start_clearing", "cancel_project")),
            Map.entry("SiteSurveySummaryScreen", List.of(
                    "review_confirm", "find_recommendation", "close_survey")),
            Map.entry("RelocationAdvisorScreen", List.of(
                    "choose_candidate_0", "choose_candidate_1", "choose_candidate_2",
                    "choose_candidate_3", "choose_candidate_4", "choose_candidate_5",
                    "reselect_placement", "close_relocation")));

    private WidgetLocator() {}

    static List<LocatedWidget> widgets(Screen screen) {
        List<LocatedWidget> result = new ArrayList<>();
        List<String> known = KNOWN.getOrDefault(screen.getClass().getSimpleName(), List.of());
        int widgetIndex = 0;
        for (var child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            String id = widgetIndex < known.size()
                    ? known.get(widgetIndex) : fallbackId(widget, widgetIndex);
            result.add(new LocatedWidget(id, widget));
            widgetIndex++;
        }
        return result;
    }

    static LocatedWidget require(Screen screen, String id) {
        return widgets(screen).stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new BridgeRefusal("WIDGET_NOT_FOUND", id));
    }

    static List<Map<String, Object>> targetRows(Screen screen) {
        if (!"GoalPickerScreen".equals(screen.getClass().getSimpleName())) return List.of();
        try {
            Field filteredField = screen.getClass().getDeclaredField("filtered");
            Field scrollField = screen.getClass().getDeclaredField("scroll");
            Method visibleRowsMethod = screen.getClass().getDeclaredMethod("visibleRows");
            filteredField.setAccessible(true);
            scrollField.setAccessible(true);
            visibleRowsMethod.setAccessible(true);
            List<?> filtered = (List<?>) filteredField.get(screen);
            int scroll = scrollField.getInt(screen);
            int visibleRows = (int) visibleRowsMethod.invoke(screen);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = 0; index < filtered.size(); index++) {
                Object goal = filtered.get(index);
                Method targetMethod = goal.getClass().getMethod("target");
                Method recipeMethod = goal.getClass().getMethod("recipe");
                Method capabilityMethod = goal.getClass().getMethod("capability");
                Method inputsMethod = goal.getClass().getMethod("inputs");
                Method modulesMethod = goal.getClass().getMethod("modules");
                Method availableMethod = goal.getClass().getMethod("available");
                Method quantityMethod = goal.getClass().getMethod("verifiedQuantity");
                Method statusMethod = goal.getClass().getMethod("statusCode");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", index);
                row.put("target", targetMethod.invoke(goal));
                row.put("recipe", recipeMethod.invoke(goal));
                row.put("capability", capabilityMethod.invoke(goal));
                row.put("inputs", inputsMethod.invoke(goal));
                row.put("modules", modulesMethod.invoke(goal));
                row.put("available", availableMethod.invoke(goal));
                row.put("verified_quantity", quantityMethod.invoke(goal));
                row.put("status_code", statusMethod.invoke(goal));
                row.put("visible", index >= scroll && index < scroll + visibleRows);
                rows.add(row);
            }
            return rows;
        } catch (ReflectiveOperationException error) {
            throw new BridgeRefusal("GOAL_PICKER_INSPECTION_FAILED",
                    error.getClass().getSimpleName());
        }
    }

    private static String fallbackId(AbstractWidget widget, int index) {
        Component message = widget.getMessage();
        if (message.getContents() instanceof TranslatableContents translated) {
            return "translation_" + slug(translated.getKey()) + "_" + index;
        }
        String prefix = widget instanceof EditBox ? "text" : "widget";
        String label = slug(message.getString());
        return prefix + "_" + (label.isBlank() ? index : label + "_" + index);
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    record LocatedWidget(String id, AbstractWidget widget) {}
}
