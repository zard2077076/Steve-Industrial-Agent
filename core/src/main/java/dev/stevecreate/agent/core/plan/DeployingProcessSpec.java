package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Item-only deterministic C-10 Deployer contract. */
public record DeployingProcessSpec(
        GenericProcessSpec genericSpec,
        ResourceId heldItem,
        HeldItemDisposition heldItemDisposition,
        DeployerInteractionPolicy interactionPolicy,
        int powerTimeoutTicks) {
    public static final int MAX_ITEM_COUNT = 64;

    private static final ResourceId DEPLOYING = id("create:deploying");
    private static final ResourceId ROTATIONAL_POWER =
            id("steve_industrial:capability/rotational_power");
    private static final ResourceId DEPOT_INPUT =
            id("create:capability/depot_input");
    private static final ResourceId DEPLOYER =
            id("create:capability/deployer_item_application");
    private static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    private static final ResourceId HELD_ITEM_BEFORE_AFTER =
            id("create:evidence/deployer_held_item_before_after");
    private static final ResourceId DEPOT_ITEM_OBSERVED =
            id("create:evidence/depot_item_observed");
    private static final ResourceId DEPLOYER_CYCLE =
            id("create:evidence/deployer_cycle_observed");
    private static final ResourceId OUTPUT_STORED =
            id("create:evidence/deployer_output_stored");

    private static final ResourceId PROCESSING_PATH =
            id("create:processing_path");
    private static final ResourceId HELD_ITEM =
            id("create:held_item");
    private static final ResourceId HELD_ITEM_DISPOSITION =
            id("create:held_item_disposition");
    private static final ResourceId INTERACTION_TARGET =
            id("create:interaction_target");
    private static final ResourceId INTERACTION_FACE =
            id("create:interaction_face");
    private static final ResourceId ARBITRARY_BLOCK_USE =
            id("create:arbitrary_block_use");
    private static final ResourceId ENTITY_INTERACTION =
            id("create:entity_interaction");
    private static final ResourceId PLAYER_INVENTORY =
            id("create:player_inventory");
    private static final ResourceId PRIVATE_STORAGE =
            id("create:private_storage");
    private static final ResourceId UNKNOWN_NBT_MUTATION =
            id("create:unknown_nbt_mutation");
    private static final ResourceId UNKNOWN_WORLD_SIDE_EFFECTS =
            id("create:unknown_world_side_effects");

    public DeployingProcessSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        Objects.requireNonNull(heldItem, "heldItem");
        Objects.requireNonNull(heldItemDisposition, "heldItemDisposition");
        Objects.requireNonNull(interactionPolicy, "interactionPolicy");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        validate(genericSpec, heldItem, heldItemDisposition, interactionPolicy);
    }

    public DeployingProcessSpec(
            ResourceId recipeId,
            ResourceId processedItem,
            int processedItemCount,
            ResourceId heldItem,
            HeldItemDisposition heldItemDisposition,
            ResourceId outputItem,
            int outputCount,
            DeployerInteractionPolicy interactionPolicy,
            int powerTimeoutTicks,
            int processingTimeoutTicks) {
        this(createGeneric(
                        recipeId,
                        processedItem,
                        processedItemCount,
                        heldItem,
                        heldItemDisposition,
                        outputItem,
                        outputCount,
                        interactionPolicy,
                        processingTimeoutTicks),
                heldItem,
                heldItemDisposition,
                interactionPolicy,
                powerTimeoutTicks);
    }

    public ResourceId recipeId() { return genericSpec.recipeId(); }
    public ResourceId recipeType() { return genericSpec.recipeType(); }
    public ResourceId processedItem() {
        return genericSpec.inputs().get(0).resourceId();
    }
    public int processedItemCount() {
        return Math.toIntExact(genericSpec.inputs().get(0).amount());
    }
    public ResourceId expectedOutputItem() {
        return genericSpec.outputs().get(0).resourceId();
    }
    public int expectedOutputCount() {
        return Math.toIntExact(genericSpec.outputs().get(0).amount());
    }
    public int processingTimeoutTicks() {
        return genericSpec.maximumWaitTicks();
    }

    private static GenericProcessSpec createGeneric(
            ResourceId recipeId,
            ResourceId processedItem,
            int processedItemCount,
            ResourceId heldItem,
            HeldItemDisposition heldItemDisposition,
            ResourceId outputItem,
            int outputCount,
            DeployerInteractionPolicy policy,
            int processingTimeoutTicks) {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(processedItem, "processedItem");
        Objects.requireNonNull(heldItem, "heldItem");
        Objects.requireNonNull(heldItemDisposition, "heldItemDisposition");
        Objects.requireNonNull(outputItem, "outputItem");
        Objects.requireNonNull(policy, "policy");
        requireCount(processedItemCount, "processedItemCount");
        requireCount(outputCount, "outputCount");
        requireTimeout(processingTimeoutTicks, "processingTimeoutTicks");
        if (processedItem.equals(outputItem)) {
            throw new IllegalArgumentException(
                    "C-10 processed input and deterministic output must differ");
        }
        return new GenericProcessSpec(
                recipeId,
                DEPLOYING,
                List.of(item(processedItem, processedItemCount)),
                List.of(item(outputItem, outputCount)),
                List.of(),
                Set.of(ROTATIONAL_POWER, DEPOT_INPUT, DEPLOYER),
                Set.of(
                        INPUT_CONSUMED,
                        PROCESS_COMPLETED,
                        OUTPUT_PRODUCED,
                        HELD_ITEM_BEFORE_AFTER,
                        DEPOT_ITEM_OBSERVED,
                        DEPLOYER_CYCLE,
                        OUTPUT_STORED),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.EXACT_DECLARED,
                Map.ofEntries(
                        Map.entry(PROCESSING_PATH, "owned_depot_item"),
                        Map.entry(HELD_ITEM, heldItem.toString()),
                        Map.entry(
                                HELD_ITEM_DISPOSITION,
                                heldItemDisposition.name().toLowerCase()),
                        Map.entry(
                                INTERACTION_TARGET,
                                policy.target().name().toLowerCase()),
                        Map.entry(
                                INTERACTION_FACE,
                                policy.interactionFace().name().toLowerCase()),
                        Map.entry(ARBITRARY_BLOCK_USE, "forbidden"),
                        Map.entry(ENTITY_INTERACTION, "forbidden"),
                        Map.entry(PLAYER_INVENTORY, "forbidden"),
                        Map.entry(PRIVATE_STORAGE, "forbidden"),
                        Map.entry(UNKNOWN_NBT_MUTATION, "forbidden"),
                        Map.entry(UNKNOWN_WORLD_SIDE_EFFECTS, "forbidden")));
    }

    private static void validate(
            GenericProcessSpec spec,
            ResourceId heldItem,
            HeldItemDisposition disposition,
            DeployerInteractionPolicy policy) {
        Map<ResourceId, String> data = spec.extensionData();
        if (!spec.recipeType().equals(DEPLOYING)
                || spec.inputs().size() != 1
                || spec.outputs().size() != 1
                || !spec.optionalByproducts().isEmpty()
                || spec.inputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.outputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.inputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.outputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.inputs().get(0).resourceId().equals(
                        spec.outputs().get(0).resourceId())
                || spec.inputConsumptionRequirement()
                        != InputConsumptionRequirement.EXACT_DECLARED
                || spec.outputVerificationRequirement()
                        != OutputVerificationRequirement.EXACT_DECLARED
                || !spec.requiredMachineCapabilities().containsAll(
                        Set.of(ROTATIONAL_POWER, DEPOT_INPUT, DEPLOYER))
                || !spec.requiredCompletionEvidence().containsAll(Set.of(
                        INPUT_CONSUMED,
                        PROCESS_COMPLETED,
                        OUTPUT_PRODUCED,
                        HELD_ITEM_BEFORE_AFTER,
                        DEPOT_ITEM_OBSERVED,
                        DEPLOYER_CYCLE,
                        OUTPUT_STORED))
                || !"owned_depot_item".equals(data.get(PROCESSING_PATH))
                || !heldItem.toString().equals(data.get(HELD_ITEM))
                || !disposition.name().toLowerCase().equals(
                        data.get(HELD_ITEM_DISPOSITION))
                || !policy.target().name().toLowerCase().equals(
                        data.get(INTERACTION_TARGET))
                || !policy.interactionFace().name().toLowerCase().equals(
                        data.get(INTERACTION_FACE))
                || !forbidden(data, ARBITRARY_BLOCK_USE)
                || !forbidden(data, ENTITY_INTERACTION)
                || !forbidden(data, PLAYER_INVENTORY)
                || !forbidden(data, PRIVATE_STORAGE)
                || !forbidden(data, UNKNOWN_NBT_MUTATION)
                || !forbidden(data, UNKNOWN_WORLD_SIDE_EFFECTS)) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the safe C-10 item-only contract");
        }
    }

    private static boolean forbidden(
            Map<ResourceId, String> data, ResourceId key) {
        return "forbidden".equals(data.get(key));
    }

    private static ProcessResource item(ResourceId id, int count) {
        return new ProcessResource(id, GenericResourceType.ITEM, count);
    }

    private static void requireCount(int value, String name) {
        if (value < 1 || value > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException(name + " must be 1..64");
        }
    }

    private static void requireTimeout(int value, String name) {
        if (value < 1 || value > GenericProcessSpec.MAX_WAIT_TICKS) {
            throw new IllegalArgumentException(
                    name + " is outside the bounded timeout");
        }
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
