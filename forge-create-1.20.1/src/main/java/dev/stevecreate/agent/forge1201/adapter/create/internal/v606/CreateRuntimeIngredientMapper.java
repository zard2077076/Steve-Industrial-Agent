package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simibubi.create.foundation.fluid.FluidIngredient;
import dev.stevecreate.agent.adapter.api.create.FluidIngredientSemantics;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

/** Shared v606 ingredient normalizer used by the existing catalog and capability census. */
final class CreateRuntimeIngredientMapper {
    private CreateRuntimeIngredientMapper() {
    }

    static RecipeIngredient mapItem(Ingredient ingredient, String runtimeFingerprint, long amount) {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        JsonElement json = ingredient.toJson();
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.size() == 1 && object.has("item") && object.get("item").isJsonPrimitive()) {
                return new RecipeIngredient.ExactResource(
                        ResourceId.parse(object.get("item").getAsString()), amount);
            }
            if (object.size() == 1 && object.has("tag") && object.get("tag").isJsonPrimitive()) {
                return new RecipeIngredient.TagReference(
                        ResourceId.parse(object.get("tag").getAsString()),
                        itemCandidates(ingredient),
                        runtimeFingerprint,
                        amount);
            }
            return unsupportedItem(json, "Ingredient object contains custom or compound semantics", amount);
        }
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            List<ResourceId> candidates = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    return unsupportedItem(json, "Ingredient alternative is not an item object", amount);
                }
                JsonObject object = element.getAsJsonObject();
                if (object.size() != 1 || !object.has("item")
                        || !object.get("item").isJsonPrimitive()) {
                    return unsupportedItem(
                            json,
                            "Ingredient alternatives mix tags or custom semantics",
                            amount);
                }
                candidates.add(ResourceId.parse(object.get("item").getAsString()));
            }
            candidates = candidates.stream().distinct()
                    .sorted(Comparator.comparing(ResourceId::toString)).toList();
            if (candidates.size() == 1) {
                return new RecipeIngredient.ExactResource(candidates.get(0), amount);
            }
            if (candidates.size() > 1) {
                return new RecipeIngredient.AnyOfResources(candidates, amount);
            }
        }
        return unsupportedItem(json, "Ingredient JSON shape is empty or unsupported", amount);
    }

    static List<RecipeIngredient> mapAndCountItems(
            List<Ingredient> ingredients,
            String runtimeFingerprint) {
        Objects.requireNonNull(ingredients, "ingredients");
        Map<String, RecipeIngredient> unique = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            RecipeIngredient mapped = mapItem(ingredient, runtimeFingerprint, 1);
            String key = mapped.canonicalIdentity();
            unique.putIfAbsent(key, mapped);
            counts.merge(key, 1L, Math::addExact);
        }
        List<RecipeIngredient> result = new ArrayList<>();
        unique.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                result.add(withAmount(entry.getValue(), counts.get(entry.getKey()))));
        return List.copyOf(result);
    }

    static FluidIngredientSemantics mapFluid(
            FluidIngredient ingredient,
            String runtimeFingerprint) {
        Objects.requireNonNull(ingredient, "ingredient");
        JsonObject json = ingredient.serialize();
        long amount = ingredient.getRequiredAmount();
        List<ResourceId> candidates = fluidCandidates(ingredient.getMatchingFluidStacks());
        if (json.has("fluid") && json.get("fluid").isJsonPrimitive()
                && hasNoMeaningfulNbt(json) && !json.has("tag")) {
            ResourceId identity = ResourceId.parse(json.get("fluid").getAsString());
            return new FluidIngredientSemantics(
                    FluidIngredientSemantics.Kind.EXACT_RESOURCE,
                    java.util.Optional.of(identity),
                    List.of(identity),
                    amount,
                    java.util.Optional.empty(),
                    java.util.Optional.empty());
        }
        String tagKey = json.has("fluidTag") ? "fluidTag" : (json.has("tag") ? "tag" : null);
        if (tagKey != null && json.get(tagKey).isJsonPrimitive()) {
            return new FluidIngredientSemantics(
                    FluidIngredientSemantics.Kind.TAG_REFERENCE,
                    java.util.Optional.of(ResourceId.parse(json.get(tagKey).getAsString())),
                    candidates,
                    amount,
                    java.util.Optional.of(runtimeFingerprint),
                    java.util.Optional.empty());
        }
        if (candidates.size() >= 2 && hasNoMeaningfulNbt(json)) {
            return new FluidIngredientSemantics(
                    FluidIngredientSemantics.Kind.ANY_OF_RESOURCES,
                    java.util.Optional.empty(),
                    candidates,
                    amount,
                    java.util.Optional.of(runtimeFingerprint),
                    java.util.Optional.empty());
        }
        return new FluidIngredientSemantics(
                FluidIngredientSemantics.Kind.UNSUPPORTED_COMPLEX,
                java.util.Optional.empty(),
                List.of(),
                amount,
                java.util.Optional.of(runtimeFingerprint),
                java.util.Optional.of(bounded("Unsupported fluid ingredient: " + json)));
    }

    private static boolean hasNoMeaningfulNbt(JsonObject json) {
        if (!json.has("nbt")) return true;
        JsonElement nbt = json.get("nbt");
        return nbt != null && nbt.isJsonObject() && nbt.getAsJsonObject().size() == 0;
    }

    private static RecipeIngredient withAmount(RecipeIngredient ingredient, long amount) {
        if (ingredient instanceof RecipeIngredient.ExactResource exact) {
            return new RecipeIngredient.ExactResource(exact.resourceId(), amount);
        }
        if (ingredient instanceof RecipeIngredient.AnyOfResources anyOf) {
            return new RecipeIngredient.AnyOfResources(anyOf.resources(), amount);
        }
        if (ingredient instanceof RecipeIngredient.TagReference tag) {
            return new RecipeIngredient.TagReference(
                    tag.tagId(), tag.runtimeCandidates(), tag.runtimeFingerprint(), amount);
        }
        RecipeIngredient.UnsupportedComplexIngredient unsupported =
                (RecipeIngredient.UnsupportedComplexIngredient) ingredient;
        return new RecipeIngredient.UnsupportedComplexIngredient(
                unsupported.identity(), unsupported.detail(), amount);
    }

    private static List<ResourceId> itemCandidates(Ingredient ingredient) {
        TreeSet<ResourceId> candidates = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                candidates.add(resourceId(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            }
        }
        return List.copyOf(candidates);
    }

    private static List<ResourceId> fluidCandidates(List<FluidStack> stacks) {
        TreeSet<ResourceId> candidates = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (FluidStack stack : stacks) {
            if (!stack.isEmpty()) {
                candidates.add(resourceId(BuiltInRegistries.FLUID.getKey(stack.getFluid())));
            }
        }
        return List.copyOf(candidates);
    }

    private static RecipeIngredient unsupportedItem(JsonElement json, String detail, long amount) {
        return new RecipeIngredient.UnsupportedComplexIngredient(
                bounded(json.toString()), detail, amount);
    }

    private static ResourceId resourceId(net.minecraft.resources.ResourceLocation id) {
        if (id == null) {
            throw new IllegalArgumentException("Runtime registry entry has no ID");
        }
        return new ResourceId(id.getNamespace(), id.getPath());
    }

    private static String bounded(String value) {
        return value.length() <= RecipeIngredient.MAX_IDENTITY_LENGTH
                ? value
                : value.substring(0, RecipeIngredient.MAX_IDENTITY_LENGTH);
    }
}
