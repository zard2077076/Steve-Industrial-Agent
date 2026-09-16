package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Arrays;

/** The four bounded C-06 airflow media and their exact recipe types. */
public enum FanProcessingMode {
    WASHING("create:splashing", "minecraft:water", "minecraft:stone", false),
    SMOKING("minecraft:smoking", "minecraft:fire", "minecraft:netherrack", true),
    HAUNTING("create:haunting", "minecraft:soul_fire", "minecraft:soul_soil", true),
    BLASTING("minecraft:blasting", "minecraft:lava", "minecraft:stone", true);

    private final ResourceId recipeType;
    private final ResourceId mediumBlock;
    private final ResourceId supportBlock;
    private final boolean dangerousToBots;

    FanProcessingMode(
            String recipeType,
            String mediumBlock,
            String supportBlock,
            boolean dangerousToBots) {
        this.recipeType = ResourceId.parse(recipeType);
        this.mediumBlock = ResourceId.parse(mediumBlock);
        this.supportBlock = ResourceId.parse(supportBlock);
        this.dangerousToBots = dangerousToBots;
    }

    public ResourceId recipeType() { return recipeType; }
    public ResourceId mediumBlock() { return mediumBlock; }
    public ResourceId supportBlock() { return supportBlock; }
    public boolean dangerousToBots() { return dangerousToBots; }

    public static FanProcessingMode forRecipeType(ResourceId recipeType) {
        return Arrays.stream(values())
                .filter(value -> value.recipeType.equals(recipeType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported C-06 fan recipe type " + recipeType));
    }
}
