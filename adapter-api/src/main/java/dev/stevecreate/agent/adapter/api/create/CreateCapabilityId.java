package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stable Create capability identities owned by the C-05 through C-10 expansion. */
public enum CreateCapabilityId {
    CRUSHING("C-05", "create:crushing", "steve_industrial:create_crushing_v606"),
    FAN_WASHING("C-06-WASHING", "create:splashing", "steve_industrial:create_fan_washing_v606"),
    FAN_SMOKING("C-06-SMOKING", "minecraft:smoking", "steve_industrial:create_fan_smoking_v606"),
    FAN_HAUNTING("C-06-HAUNTING", "create:haunting", "steve_industrial:create_fan_haunting_v606"),
    FAN_BLASTING("C-06-BLASTING", "minecraft:blasting", "steve_industrial:create_fan_blasting_v606"),
    CUTTING("C-07", "create:cutting", "steve_industrial:create_cutting_v606"),
    MIXING_PHASE_I("C-08", "create:mixing", "steve_industrial:create_mixing_phase1_v606"),
    COMPACTING_PHASE_I("C-09", "create:compacting", "steve_industrial:create_compacting_phase1_v606"),
    DEPLOYING_PHASE_I("C-10", "create:deploying", "steve_industrial:create_deploying_phase1_v606");

    private final String phaseCode;
    private final ResourceId recipeType;
    private final ResourceId implementationCapabilityId;

    CreateCapabilityId(String phaseCode, String recipeType, String implementationCapabilityId) {
        this.phaseCode = phaseCode;
        this.recipeType = ResourceId.parse(recipeType);
        this.implementationCapabilityId = ResourceId.parse(implementationCapabilityId);
    }

    public String phaseCode() {
        return phaseCode;
    }

    public ResourceId recipeType() {
        return recipeType;
    }

    public ResourceId implementationCapabilityId() {
        return implementationCapabilityId;
    }

    public static Optional<CreateCapabilityId> fromRecipeType(ResourceId recipeType) {
        Objects.requireNonNull(recipeType, "recipeType");
        return Arrays.stream(values())
                .filter(value -> value.recipeType.equals(recipeType))
                .findFirst();
    }

    public static List<CreateCapabilityId> canonicalValues() {
        return Arrays.stream(values())
                .sorted(Comparator.comparing(value -> value.recipeType.toString()))
                .toList();
    }
}
