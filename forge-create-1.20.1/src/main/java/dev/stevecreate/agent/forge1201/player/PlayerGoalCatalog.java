package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reviewed player catalog. Runtime validation still occurs before preview or execution. */
public final class PlayerGoalCatalog {
    private static final List<GoalCatalogEntry> ENTRIES = List.of(
            goal("minecraft:gravel", "create:milling/cobblestone", "create:milling",
                    Map.of("minecraft:andesite", 1L), 1, 2, false),
            goal("create:iron_sheet", "create:pressing/iron_ingot", "create:pressing",
                    Map.of("minecraft:iron_ingot", 1L), 1, 1, false),
            goal("minecraft:sand", "create:crushing/gravel", "create:crushing",
                    Map.of("minecraft:gravel", 1L), 1, 1, true),
            goal("create:shaft", "create:cutting/andesite_alloy", "create:cutting",
                    Map.of("create:andesite_alloy", 1L), 6, 1, true),
            goal("create:dough", "create:splashing/wheat_flour", "create:splashing",
                    Map.of("create:wheat_flour", 1L), 1, 1, true),
            goal("create:blaze_cake_base", "create:compacting/blaze_cake", "create:compacting",
                    Map.of("minecraft:egg", 1L, "minecraft:sugar", 1L,
                            "create:cinder_flour", 1L), 1, 1, true),
            goal("create:andesite_alloy", "create:mixing/andesite_alloy", "create:mixing",
                    Map.of("minecraft:andesite", 1L, "minecraft:iron_nugget", 1L), 1, 1, true),
            goal("create:cogwheel", "create:deploying/cogwheel", "create:deploying",
                    Map.of("create:shaft", 1L, "minecraft:oak_planks", 1L), 1, 1, true),
            goal("minecraft:cooked_beef", "minecraft:cooked_beef_from_smoking",
                    "minecraft:smoking", Map.of("minecraft:beef", 1L), 1, 1, true),
            goal("minecraft:blackstone", "create:haunting/blackstone", "create:haunting",
                    Map.of("minecraft:cobblestone", 1L), 1, 1, true),
            goal("minecraft:iron_ingot", "minecraft:iron_ingot_from_blasting_raw_iron",
                    "minecraft:blasting", Map.of("minecraft:raw_iron", 1L), 1, 1, true));

    private static final Map<ResourceId, GoalCatalogEntry> BY_TARGET;

    static {
        LinkedHashMap<ResourceId, GoalCatalogEntry> indexed = new LinkedHashMap<>();
        ENTRIES.forEach(entry -> indexed.put(entry.target(), entry));
        BY_TARGET = Map.copyOf(indexed);
    }

    private PlayerGoalCatalog() {}

    public static List<GoalCatalogEntry> entries() {
        return ENTRIES;
    }

    public static Optional<GoalCatalogEntry> find(ResourceId target) {
        return Optional.ofNullable(BY_TARGET.get(target));
    }

    public static String translationKey(GoalCatalogEntry entry) {
        return "goal.steve_create_agent." + entry.target().namespace() + "."
                + entry.target().path().replace('/', '.');
    }

    public static int verifiedQuantity(GoalCatalogEntry entry) {
        return switch (entry.target().toString()) {
            case "minecraft:gravel" -> 3;
            case "create:iron_sheet" -> 2;
            case "create:shaft" -> 6;
            default -> 1;
        };
    }

    private static GoalCatalogEntry goal(
            String target,
            String recipe,
            String capability,
            Map<String, Long> inputs,
            long outputPerBatch,
            int modules,
            boolean preparedSiteRequired) {
        LinkedHashMap<ResourceId, Long> converted = new LinkedHashMap<>();
        inputs.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> converted.put(id(entry.getKey()), entry.getValue()));
        return new GoalCatalogEntry(id(target), id(recipe), id(capability), converted,
                outputPerBatch, modules, preparedSiteRequired, true);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
