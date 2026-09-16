package dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020;

import blusunrize.immersiveengineering.api.crafting.AlloyRecipe;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import blusunrize.immersiveengineering.common.blocks.multiblocks.logic.AlloySmelterLogic;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * Version-pinned physical operations for the reviewed IE Alloy Smelter batch.
 *
 * <p>This class deliberately owns only machine-specific state, recipe and inventory
 * behavior. Template placement/formation remains in the shared multiblock helper. It
 * never reads a player container, changes a material ledger, creates an output stack or
 * decides that an order may run.</p>
 */
public final class ImmersiveEngineeringV1020AlloySmelterProduction {
    public static final ResourceLocation REVIEWED_RECIPE =
            ResourceLocation.fromNamespaceAndPath(
                    "immersiveengineering", "alloysmelter/brass");
    private static final String ACTOR_SEED = "steve-ie-alloy-smelter-production";

    private ImmersiveEngineeringV1020AlloySmelterProduction() {}

    public static List<BlockPos> baselinePositions(ServerLevel level, BlockPos origin) {
        requireServer(level);
        return ImmersiveEngineeringV1020MultiblockFormation.componentPositions(
                level, IEMultiblocks.ALLOY_SMELTER, origin).stream()
                .sorted(positionOrder()).toList();
    }

    public static StructureResult placeStructure(ServerLevel level, BlockPos origin) {
        requireServer(level);
        var result = ImmersiveEngineeringV1020MultiblockFormation.place(
                level, IEMultiblocks.ALLOY_SMELTER, origin);
        return new StructureResult(result.success(), result.code(), result.placedBlocks());
    }

    public static boolean rawStructurePresent(ServerLevel level, BlockPos origin) {
        requireServer(level);
        return ImmersiveEngineeringV1020MultiblockFormation.rawStructurePresent(
                level, IEMultiblocks.ALLOY_SMELTER, origin);
    }

    public static OperationResult form(
            ServerLevel level, BlockPos origin, ItemStack hammer) {
        requireServer(level);
        if (hammer.isEmpty()) return OperationResult.failure("HAMMER_REQUIRED");
        boolean formed = ImmersiveEngineeringV1020MultiblockFormation.form(
                level, IEMultiblocks.ALLOY_SMELTER, origin, Direction.SOUTH,
                actor(level), hammer);
        return formed && machine(level, origin).isPresent()
                ? OperationResult.success("ALLOY_SMELTER_FORMED")
                : OperationResult.failure("ALLOY_SMELTER_FORMATION_FAILED");
    }

    public static boolean multiblockFormed(ServerLevel level, BlockPos origin) {
        requireServer(level);
        return machine(level, origin).isPresent();
    }

    /**
     * Read-only restart evidence for the exact reviewed batch. No slot number is
     * persisted or trusted: the live handler must still expose the three exact stacks
     * in distinct slots and resolve the same recipe.
     */
    public static AdmissionObservation observeReviewedAdmission(
            ServerLevel level,
            BlockPos origin,
            ItemStack first,
            ItemStack second,
            ItemStack fuel) {
        requireServer(level);
        Machine machine = machine(level, origin).orElse(null);
        if (machine == null) return AdmissionObservation.failure(
                "ALLOY_SMELTER_MASTER_NOT_FOUND");
        ReviewedRecipe reviewed = reviewedRecipe(level).orElse(null);
        if (reviewed == null || !exact(first, reviewed.firstInput())
                || !exact(second, reviewed.secondInput()) || fuel.isEmpty()
                || fuel.getCount() != 1 || fuel.hasTag()) {
            return AdmissionObservation.failure("ALLOY_SMELTER_ADMISSION_EXPECTATION_INVALID");
        }
        IItemHandlerModifiable inventory = machine.inventory();
        Set<Integer> taken = new LinkedHashSet<>();
        int firstSlot = exactSlot(inventory, first, taken);
        if (firstSlot < 0) return AdmissionObservation.failure(
                "ALLOY_SMELTER_FIRST_INPUT_NOT_ADMITTED");
        taken.add(firstSlot);
        int secondSlot = exactSlot(inventory, second, taken);
        if (secondSlot < 0) return AdmissionObservation.failure(
                "ALLOY_SMELTER_SECOND_INPUT_NOT_ADMITTED");
        taken.add(secondSlot);
        int fuelSlot = exactSlot(inventory, fuel, taken);
        if (fuelSlot < 0) return AdmissionObservation.failure(
                "ALLOY_SMELTER_FUEL_NOT_ADMITTED");
        AlloyRecipe matched = AlloyRecipe.findRecipe(level,
                inventory.getStackInSlot(firstSlot), inventory.getStackInSlot(secondSlot), null);
        if (matched == null || !matched.getId().equals(reviewed.recipeId())) {
            return AdmissionObservation.failure("ALLOY_SMELTER_LIVE_RECIPE_MISMATCH");
        }
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            total += inventory.getStackInSlot(slot).getCount();
        }
        int expectedTotal = first.getCount() + second.getCount() + fuel.getCount();
        if (total != expectedTotal) {
            return new AdmissionObservation(false, "ALLOY_SMELTER_ADMISSION_CONTAMINATED",
                    firstSlot, secondSlot, fuelSlot, total);
        }
        return new AdmissionObservation(true, "ALLOY_SMELTER_BATCH_ADMISSION_OBSERVED",
                firstSlot, secondSlot, fuelSlot, total);
    }

    /**
     * Reconciles an already journaled batch after fuel may have entered IE's burn
     * state. The reviewed inputs must still be exact, or the unique real output must
     * already be present; unrelated inventory is never accepted as progress.
     */
    public static ProcessingObservation observeReviewedProcessing(
            ServerLevel level,
            BlockPos origin,
            ItemStack first,
            ItemStack second,
            ItemStack expectedOutput) {
        requireServer(level);
        Machine machine = machine(level, origin).orElse(null);
        if (machine == null) return ProcessingObservation.failure(
                "ALLOY_SMELTER_MASTER_NOT_FOUND");
        OutputObservation output = observeUniqueOutput(level, origin, expectedOutput, 1);
        if (output.verified()) {
            return new ProcessingObservation(true, "ALLOY_SMELTER_OUTPUT_READY",
                    true, output.outputCount());
        }
        IItemHandlerModifiable inventory = machine.inventory();
        Set<Integer> taken = new LinkedHashSet<>();
        int firstSlot = exactSlot(inventory, first, taken);
        if (firstSlot < 0) return ProcessingObservation.failure(
                "ALLOY_SMELTER_PROCESS_FIRST_INPUT_MISSING");
        taken.add(firstSlot);
        int secondSlot = exactSlot(inventory, second, taken);
        if (secondSlot < 0) return ProcessingObservation.failure(
                "ALLOY_SMELTER_PROCESS_SECOND_INPUT_MISSING");
        AlloyRecipe matched = AlloyRecipe.findRecipe(level,
                inventory.getStackInSlot(firstSlot), inventory.getStackInSlot(secondSlot), null);
        if (matched == null || !matched.getId().equals(REVIEWED_RECIPE)) {
            return ProcessingObservation.failure("ALLOY_SMELTER_PROCESS_RECIPE_DIVERGED");
        }
        int unrelated = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (slot == firstSlot || slot == secondSlot) continue;
            ItemStack held = inventory.getStackInSlot(slot);
            if (!held.isEmpty()) unrelated += held.getCount();
        }
        if (unrelated > 1) {
            return new ProcessingObservation(false, "ALLOY_SMELTER_PROCESS_CONTAMINATED",
                    false, unrelated);
        }
        return new ProcessingObservation(true, "ALLOY_SMELTER_PROCESSING_OBSERVED",
                false, 0);
    }

    /** Exact live recipe binding used by the material planner before withdrawal. */
    public static Optional<ReviewedRecipe> reviewedRecipe(ServerLevel level) {
        requireServer(level);
        return level.getRecipeManager().byKey(REVIEWED_RECIPE)
                .filter(AlloyRecipe.class::isInstance)
                .map(AlloyRecipe.class::cast)
                .flatMap(recipe -> {
                    ItemStack[] first = recipe.input0.getMatchingStacks();
                    ItemStack[] second = recipe.input1.getMatchingStacks();
                    ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
                    if (first.length == 0 || second.length == 0 || output.isEmpty()
                            || first[0].isEmpty() || second[0].isEmpty()) return Optional.empty();
                    return Optional.of(new ReviewedRecipe(recipe.getId(), first[0].copy(),
                            second[0].copy(), output, recipe.time));
                });
    }

    /**
     * Inserts one exact reviewed batch and one exact fuel stack into an empty machine.
     * Slot identities are discovered through the real handler rather than assumed.
     */
    public static FeedResult feedReviewedBatch(
            ServerLevel level,
            BlockPos origin,
            ItemStack first,
            ItemStack second,
            ItemStack fuel) {
        requireServer(level);
        Machine machine = machine(level, origin).orElse(null);
        if (machine == null) return FeedResult.failure("ALLOY_SMELTER_MASTER_NOT_FOUND");
        ReviewedRecipe reviewed = reviewedRecipe(level).orElse(null);
        if (reviewed == null) return FeedResult.failure("ALLOY_SMELTER_RECIPE_UNAVAILABLE");
        if (!exact(first, reviewed.firstInput()) || !exact(second, reviewed.secondInput())) {
            return FeedResult.failure("ALLOY_SMELTER_INPUT_MISMATCH");
        }
        if (fuel.isEmpty() || fuel.getCount() != 1 || fuel.hasTag()) {
            return FeedResult.failure("ALLOY_SMELTER_FUEL_NOT_EXACT_SINGLE_ITEM");
        }
        IItemHandlerModifiable inventory = machine.inventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                return FeedResult.failure("ALLOY_SMELTER_INVENTORY_NOT_EMPTY");
            }
        }
        Set<Integer> taken = new LinkedHashSet<>();
        int firstSlot = acceptingSlot(inventory, first, taken);
        if (firstSlot < 0) return FeedResult.failure("ALLOY_SMELTER_FIRST_INPUT_REJECTED");
        taken.add(firstSlot);
        int secondSlot = acceptingSlot(inventory, second, taken);
        if (secondSlot < 0) return FeedResult.failure("ALLOY_SMELTER_SECOND_INPUT_REJECTED");
        taken.add(secondSlot);
        int fuelSlot = acceptingSlot(inventory, fuel, taken);
        if (fuelSlot < 0) return FeedResult.failure("ALLOY_SMELTER_FUEL_REJECTED");

        if (!inventory.insertItem(firstSlot, first.copy(), false).isEmpty()
                || !inventory.insertItem(secondSlot, second.copy(), false).isEmpty()
                || !inventory.insertItem(fuelSlot, fuel.copy(), false).isEmpty()) {
            List<ItemStack> recovered = extractAll(inventory);
            return new FeedResult(false, "ALLOY_SMELTER_INSERTION_PARTIAL", -1, -1,
                    -1, reviewed.recipeId(), reviewed.output().copy(), recovered);
        }
        AlloyRecipe matched = AlloyRecipe.findRecipe(level,
                inventory.getStackInSlot(firstSlot), inventory.getStackInSlot(secondSlot), null);
        if (matched == null || !matched.getId().equals(reviewed.recipeId())) {
            List<ItemStack> recovered = extractAll(inventory);
            return new FeedResult(false, "ALLOY_SMELTER_LIVE_RECIPE_MISMATCH", -1, -1,
                    -1, reviewed.recipeId(), reviewed.output().copy(), recovered);
        }
        return new FeedResult(true, "ALLOY_SMELTER_BATCH_ADMITTED", firstSlot, secondSlot,
                fuelSlot, reviewed.recipeId(), reviewed.output().copy(), List.of());
    }

    /** Observes only real inventory changes; it never applies the recipe itself. */
    public static OutputObservation observeUniqueOutput(
            ServerLevel level, BlockPos origin, ItemStack expected, int admittedFuelCount) {
        requireServer(level);
        Machine machine = machine(level, origin).orElse(null);
        if (machine == null) return OutputObservation.failure("ALLOY_SMELTER_MASTER_NOT_FOUND");
        if (expected.isEmpty() || admittedFuelCount < 1) {
            return OutputObservation.failure("ALLOY_SMELTER_OBSERVATION_INVALID");
        }
        IItemHandlerModifiable inventory = machine.inventory();
        int outputCount = 0;
        int outputSlot = -1;
        int nonOutputItems = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack held = inventory.getStackInSlot(slot);
            if (held.isEmpty()) continue;
            if (ItemStack.isSameItemSameTags(held, expected)) {
                outputCount += held.getCount();
                outputSlot = outputSlot < 0 ? slot : -2;
            } else {
                nonOutputItems += held.getCount();
            }
        }
        if (outputCount == 0) {
            return new OutputObservation(false, "ALLOY_SMELTER_OUTPUT_NOT_YET_OBSERVED",
                    0, -1, false, nonOutputItems);
        }
        boolean exactOutput = outputCount == expected.getCount() && outputSlot >= 0;
        boolean fuelConsumed = nonOutputItems == 0 && admittedFuelCount == 1;
        if (!exactOutput) {
            return new OutputObservation(false, "ALLOY_SMELTER_OUTPUT_NOT_UNIQUE",
                    outputCount, outputSlot, fuelConsumed, nonOutputItems);
        }
        if (!fuelConsumed) {
            return new OutputObservation(false, "ALLOY_SMELTER_INPUT_OR_FUEL_RESIDUE",
                    outputCount, outputSlot, false, nonOutputItems);
        }
        return new OutputObservation(true, "ALLOY_SMELTER_OUTPUT_OBSERVED", outputCount,
                outputSlot, true, 0);
    }

    public static ItemStack claimUniqueOutput(
            ServerLevel level, BlockPos origin, ItemStack expected) {
        requireServer(level);
        Machine machine = machine(level, origin).orElseThrow(() ->
                new IllegalStateException("alloy smelter master is absent"));
        OutputObservation observation = observeUniqueOutput(level, origin, expected, 1);
        if (!observation.verified()) {
            throw new IllegalStateException("alloy smelter output is not claimable: "
                    + observation.code());
        }
        ItemStack extracted = machine.inventory().extractItem(
                observation.outputSlot(), expected.getCount(), false);
        if (!exact(extracted, expected)) {
            throw new IllegalStateException("alloy smelter output extraction drifted");
        }
        return extracted;
    }

    /** Exact cancellation/recovery primitive; the caller remains responsible for settlement. */
    public static List<ItemStack> extractAllInputs(ServerLevel level, BlockPos origin) {
        requireServer(level);
        return machine(level, origin).map(value -> extractAll(value.inventory())).orElse(List.of());
    }

    public static void clear(ServerLevel level, BlockPos origin) {
        requireServer(level);
        ImmersiveEngineeringV1020MultiblockFormation.clear(
                level, IEMultiblocks.ALLOY_SMELTER, origin);
    }

    private static Optional<Machine> machine(ServerLevel level, BlockPos origin) {
        return ImmersiveEngineeringV1020MultiblockFormation
                .formedMaster(level, IEMultiblocks.ALLOY_SMELTER, origin)
                .filter(master -> master.getHelper().getState() instanceof AlloySmelterLogic.State)
                .map(master -> new Machine(
                        (AlloySmelterLogic.State) master.getHelper().getState()));
    }

    private static int acceptingSlot(
            IItemHandlerModifiable inventory, ItemStack stack, Set<Integer> excluded) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (excluded.contains(slot)) continue;
            ItemStack remainder = inventory.insertItem(slot, stack.copy(), true);
            if (remainder.getCount() < stack.getCount()) return slot;
        }
        return -1;
    }

    private static int exactSlot(
            IItemHandlerModifiable inventory, ItemStack stack, Set<Integer> excluded) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!excluded.contains(slot) && exact(inventory.getStackInSlot(slot), stack)) {
                return slot;
            }
        }
        return -1;
    }

    private static List<ItemStack> extractAll(IItemHandlerModifiable inventory) {
        ArrayList<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack held = inventory.getStackInSlot(slot);
            if (held.isEmpty()) continue;
            ItemStack extracted = inventory.extractItem(slot, held.getCount(), false);
            if (!extracted.isEmpty()) result.add(extracted);
        }
        return List.copyOf(result);
    }

    private static boolean exact(ItemStack actual, ItemStack expected) {
        return !actual.isEmpty() && actual.getCount() == expected.getCount()
                && ItemStack.isSameItemSameTags(actual, expected);
    }

    private static FakePlayer actor(ServerLevel level) {
        return FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(ACTOR_SEED.getBytes(StandardCharsets.UTF_8)),
                "SteveIeAlloySmelter"));
    }

    private static Comparator<BlockPos> positionOrder() {
        return Comparator.comparingInt((BlockPos value) -> value.getX())
                .thenComparingInt(value -> value.getY())
                .thenComparingInt(value -> value.getZ());
    }

    private static void requireServer(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("IE alloy smelter operation requires the server thread");
        }
    }

    private record Machine(IItemHandlerModifiable inventory) {
        private Machine(AlloySmelterLogic.State state) { this(state.getInventory()); }
    }

    public record ReviewedRecipe(
            ResourceLocation recipeId,
            ItemStack firstInput,
            ItemStack secondInput,
            ItemStack output,
            int processingTicks) {
        public ReviewedRecipe {
            Objects.requireNonNull(recipeId, "recipeId");
            firstInput = firstInput.copy();
            secondInput = secondInput.copy();
            output = output.copy();
            if (firstInput.isEmpty() || secondInput.isEmpty() || output.isEmpty()
                    || processingTicks < 1) {
                throw new IllegalArgumentException("reviewed alloy recipe is incomplete");
            }
        }

        @Override public ItemStack firstInput() { return firstInput.copy(); }
        @Override public ItemStack secondInput() { return secondInput.copy(); }
        @Override public ItemStack output() { return output.copy(); }
    }

    public record StructureResult(boolean success, String code, int placedBlocks) {}

    public record OperationResult(boolean success, String code) {
        static OperationResult success(String code) { return new OperationResult(true, code); }
        static OperationResult failure(String code) { return new OperationResult(false, code); }
    }

    public record FeedResult(
            boolean success,
            String code,
            int firstInputSlot,
            int secondInputSlot,
            int fuelSlot,
            ResourceLocation recipeId,
            ItemStack expectedOutput,
            List<ItemStack> recoveredOnFailure) {
        public FeedResult {
            Objects.requireNonNull(code, "code");
            expectedOutput = expectedOutput.copy();
            recoveredOnFailure = recoveredOnFailure.stream().map(ItemStack::copy).toList();
        }

        static FeedResult failure(String code) {
            return new FeedResult(false, code, -1, -1, -1, REVIEWED_RECIPE,
                    ItemStack.EMPTY, List.of());
        }

        @Override public ItemStack expectedOutput() { return expectedOutput.copy(); }
        @Override public List<ItemStack> recoveredOnFailure() {
            return recoveredOnFailure.stream().map(ItemStack::copy).toList();
        }
    }

    public record OutputObservation(
            boolean verified,
            String code,
            int outputCount,
            int outputSlot,
            boolean fuelConsumed,
            int residualItems) {
        static OutputObservation failure(String code) {
            return new OutputObservation(false, code, 0, -1, false, 0);
        }
    }

    public record AdmissionObservation(
            boolean verified,
            String code,
            int firstInputSlot,
            int secondInputSlot,
            int fuelSlot,
            int observedItems) {
        static AdmissionObservation failure(String code) {
            return new AdmissionObservation(false, code, -1, -1, -1, 0);
        }
    }

    public record ProcessingObservation(
            boolean verified,
            String code,
            boolean outputReady,
            int observedOutput) {
        static ProcessingObservation failure(String code) {
            return new ProcessingObservation(false, code, false, 0);
        }
    }
}
