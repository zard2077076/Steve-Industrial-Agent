package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.core.execution.construction.MaterialAllocation;
import dev.stevecreate.agent.core.execution.construction.MaterialBalanceReport;
import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialRequirement;
import dev.stevecreate.agent.core.execution.construction.MaterialRequirementPlan;
import dev.stevecreate.agent.core.execution.construction.MaterialSlotSnapshot;
import dev.stevecreate.agent.core.execution.construction.MaterialSourceBinding;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLedger;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import dev.stevecreate.agent.core.player.CreateCapabilityContractV1;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerEvidenceV1;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerMappingV1;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateKineticAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Isolated real-Create C-08 survival-power candidate probe.
 *
 * <p>The published C-08 mapping is VERIFIED_SURVIVAL. This fixture proves its
 * water wheel, two real cog ratios, the live mixer lifecycle, exact input
 * consumption, output storage and a balanced transactional material ledger.</p>
 */
@PrefixGameTestTemplate(false)
public final class CreateC08SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId LARGE_COGWHEEL = id("create:large_cogwheel");
    private static final ResourceId COGWHEEL = id("create:cogwheel");
    private static final ResourceId MIXER = id("create:mechanical_mixer");
    private static final ResourceId BASIN = id("create:basin");
    private static final ResourceId BURNER = id("create:blaze_burner");
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER_BUCKET = id("minecraft:water_bucket");
    private static final ResourceId ANDESITE = id("minecraft:andesite");
    private static final ResourceId IRON_NUGGET = id("minecraft:iron_nugget");
    private static final ResourceId ANDESITE_ALLOY = id("create:andesite_alloy");
    private static final ResourceId PROJECT = id("steve_industrial:c08/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c08/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;
    private static final String BLOCK_HASH = "a".repeat(64);
    private static final String INVENTORY_HASH = "b".repeat(64);

    private CreateC08SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2_400)
    public static void c08WaterWheelBasinMixerSurvivalProduction(GameTestHelper helper) {
        Fixture fixture = buildFixture(
                helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 2)));
        helper.succeedWhen(() -> {
            if (fixture.completed) return;
            try {
                verifyAndCleanup(fixture);
                fixture.completed = true;
            } catch (Pending pending) {
                throw new GameTestAssertException(pending.getMessage());
            } catch (RuntimeException failure) {
                cleanupBestEffort(fixture);
                throw failure;
            }
        });
    }

    private static Fixture buildFixture(ServerLevel level, BlockPos origin) {
        BlockPos sourceChest = origin.offset(12, 0, 0);
        place(level, sourceChest, key("minecraft", "chest"), null);
        ChestBlockEntity source = chest(level, sourceChest);
        List<MaterialSpec> specs = List.of(
                new MaterialSpec(WATER_WHEEL, 1, false),
                new MaterialSpec(GEARBOX, 1, false),
                new MaterialSpec(SHAFT, 1, false),
                new MaterialSpec(LARGE_COGWHEEL, 2, false),
                new MaterialSpec(COGWHEEL, 1, false),
                new MaterialSpec(MIXER, 1, false),
                new MaterialSpec(BASIN, 1, false),
                new MaterialSpec(BURNER, 1, false),
                new MaterialSpec(CHEST, 1, false),
                new MaterialSpec(STONE, 13, false),
                new MaterialSpec(WATER_BUCKET, 1, false),
                new MaterialSpec(ANDESITE, 1, true),
                new MaterialSpec(IRON_NUGGET, 1, true));
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec spec = specs.get(index);
            put(source, index, spec.item(), spec.quantity());
            slots.add(new MaterialSlotSnapshot(index,
                    new MaterialIdentity(spec.item(), EMPTY_PAYLOAD), spec.quantity()));
        }
        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c08/survival-power-plan"), PROJECT,
                id("create:c08/water-wheel-basin-mixer"), 1, "forge-create-6.0.6",
                "c".repeat(64), specs.stream().map(spec -> new MaterialRequirement(
                        id("steve_industrial:c08/requirement/" + spec.item().path()),
                        id("create:c08/water-wheel-basin-mixer"), 0,
                        RecipeIngredientKind.EXACT_RESOURCE, spec.item().toString(),
                        List.of(spec.item()), spec.quantity())).toList());
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(
                SOURCE, PROJECT, "c08-acceptance", id("minecraft:overworld"),
                blockPos(sourceChest), "up", "minecraft:chest", BLOCK_HASH, INVENTORY_HASH,
                0, specs.stream().mapToLong(MaterialSpec::quantity).sum(), 0, 100_000, slots), 1);
        List<MaterialWithdrawal> withdrawals = new ArrayList<>();
        long tick = 2;
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec spec = specs.get(index);
            MaterialRequirement requirement = plan.requirements().stream()
                    .filter(value -> value.acceptedResources().contains(spec.item()))
                    .findFirst().orElseThrow();
            ResourceId allocationId = id("steve_industrial:c08/allocation/" + index);
            ledger.reserve(new MaterialAllocation(
                    allocationId, PROJECT, requirement.requirementId(), SOURCE, index,
                    new MaterialIdentity(spec.item(), EMPTY_PAYLOAD), spec.quantity()), tick++);
            ResourceId transactionId = id("steve_industrial:c08/transaction/" + index);
            ledger.prepare(transactionId, PROJECT, id("steve_industrial:c08/task/" + index),
                    allocationId, MaterialExecutorKind.DIRECT, spec.quantity(), tick++);
            decrement(source, index, spec.quantity());
            ledger.advance(transactionId, MaterialTransactionState.WITHDRAWN, tick++);
            withdrawals.add(new MaterialWithdrawal(transactionId, index, spec));
        }

        List<BlockPos> changed = new ArrayList<>();
        List<BlockPos> supports = List.of(
                origin.offset(0, 1, -1), origin.offset(-1, 2, -1),
                origin.offset(1, 2, -1), origin.offset(0, 2, -2),
                origin.offset(0, 2, 0), origin.offset(-1, 4, -1),
                origin.offset(1, 4, -1), origin.offset(0, 4, -2),
                origin.offset(0, 4, 0), origin.offset(-1, 3, -1),
                origin.offset(1, 3, -1), origin.offset(0, 3, -2),
                origin.offset(3, 4, 0));
        for (BlockPos position : supports) {
            place(level, position, key("minecraft", "stone"), null);
            changed.add(position);
        }

        BlockPos waterWheel = origin.offset(0, 3, 0);
        BlockPos gearbox = origin.offset(1, 3, 0);
        BlockPos verticalShaft = origin.offset(1, 4, 0);
        BlockPos firstLargeCog = origin.offset(1, 5, 0);
        // Parallel Y-axis gears mesh in the X/Z plane. The small cog receives
        // 2x speed diagonally from the first large cog, transfers that speed
        // vertically to the second large cog, then drives the mixer at 2x once
        // more through the second same-plane diagonal.
        BlockPos smallCog = origin.offset(2, 5, 1);
        BlockPos secondLargeCog = origin.offset(2, 6, 1);
        BlockPos mixerPosition = origin.offset(3, 6, 2);
        BlockPos basinPosition = origin.offset(3, 4, 2);
        BlockPos burnerPosition = origin.offset(3, 3, 2);
        BlockPos outputChest = origin.offset(4, 4, 2);
        place(level, waterWheel, key("create", "water_wheel"), Direction.EAST);
        place(level, gearbox, key("create", "gearbox"), Direction.Axis.Z);
        place(level, verticalShaft, key("create", "shaft"), Direction.Axis.Y);
        place(level, firstLargeCog, key("create", "large_cogwheel"), Direction.Axis.Y);
        place(level, smallCog, key("create", "cogwheel"), Direction.Axis.Y);
        place(level, secondLargeCog, key("create", "large_cogwheel"), Direction.Axis.Y);
        place(level, mixerPosition, key("create", "mechanical_mixer"), null);
        place(level, basinPosition, key("create", "basin"), null);
        place(level, burnerPosition, key("create", "blaze_burner"), null);
        place(level, outputChest, key("minecraft", "chest"), null);
        changed.addAll(List.of(waterWheel, gearbox, verticalShaft, firstLargeCog,
                smallCog, secondLargeCog, mixerPosition, basinPosition,
                burnerPosition, outputChest));

        BlockPos waterSource = origin.offset(0, 4, -1);
        BlockPos waterFlowOne = origin.offset(0, 3, -1);
        BlockPos waterFlowTwo = origin.offset(0, 2, -1);
        placeFluid(level, waterSource, Fluids.WATER.defaultFluidState().createLegacyBlock());
        placeFluid(level, waterFlowOne,
                Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        placeFluid(level, waterFlowTwo,
                Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        changed.addAll(List.of(waterSource, waterFlowOne, waterFlowTwo, sourceChest));
        return new Fixture(level, source, ledger, changed, withdrawals,
                waterWheel, gearbox, verticalShaft, firstLargeCog, smallCog,
                secondLargeCog, mixerPosition, basinPosition, outputChest);
    }

    private static void verifyAndCleanup(Fixture fixture) {
        WaterWheelBlockEntity wheel = waterWheel(fixture.level, fixture.waterWheel);
        if (wheel != null && wheel.flowScore == 0) wheel.determineAndApplyFlowScore();
        Map<String, KineticSnapshot> snapshots = captureKinetics(fixture);
        KineticSnapshot mixerPower = snapshots.get("mechanical_mixer");
        if (mixerPower.speedRpm() < 32) {
            throw new Pending("C08 real mixer has not reached the required 32 RPM");
        }
        if (!fixture.fed) feedRealBasin(fixture);
        BasinBlockEntity basin = basin(fixture.level, fixture.basin);
        MechanicalMixerBlockEntity mixer = mixer(fixture.level, fixture.mixer);
        ChestBlockEntity output = chest(fixture.level, fixture.outputChest);
        if (basin == null || mixer == null || output == null) {
            throw new IllegalStateException("C08 mixer, basin or output chest disappeared");
        }
        if (mixer.running || mixer.runningTicks > 0 || mixer.processingTicks > 0) {
            fixture.mixerCycleObserved = true;
        }
        transferRealOutputs(basin, output);
        int outputCount = count(output, ANDESITE_ALLOY);
        if (!basin.getInputInventory().isEmpty()
                || !basin.getOutputInventory().isEmpty() || outputCount != 1) {
            throw new Pending("C08 real basin is still processing input or output");
        }
        if (!fixture.mixerCycleObserved) {
            throw new Pending("C08 live mixer cycle has not been observed");
        }
        for (UUID entityId : fixture.inputEntities) {
            Entity entity = fixture.level.getEntity(entityId);
            if (entity != null && entity.isAlive()) {
                throw new IllegalStateException("C08 input entity was not consumed");
            }
        }

        long settlementTick = 100;
        for (MaterialWithdrawal withdrawal : fixture.withdrawals) {
            if (withdrawal.spec.consumed()) {
                fixture.ledger.advance(withdrawal.transactionId,
                        MaterialTransactionState.DELIVERED, settlementTick++);
                fixture.ledger.advance(withdrawal.transactionId,
                        MaterialTransactionState.CONSUMED, settlementTick++);
            } else {
                fixture.ledger.advance(withdrawal.transactionId,
                        MaterialTransactionState.RETURN_PENDING, settlementTick++);
                put(fixture.sourceChest, withdrawal.slot,
                        withdrawal.spec.item(), withdrawal.spec.quantity());
                fixture.ledger.advance(withdrawal.transactionId,
                        MaterialTransactionState.RETURNED, settlementTick++);
            }
        }
        MaterialBalanceReport balance = fixture.ledger.balance(PROJECT);
        if (!balance.materialLedgerBalanced() || balance.duplicateWithdrawals() != 0
                || balance.duplicateReturns() != 0 || balance.unaccountedItems() != 0
                || balance.consumed() != 2) {
            throw new IllegalStateException(
                    "C08 candidate material ledger is not balanced: " + balance);
        }

        OptionalLong firstNetwork = snapshots.values().stream()
                .findFirst().orElseThrow().networkId();
        String network = firstNetwork.isPresent()
                ? Long.toUnsignedString(firstNetwork.getAsLong()) : "none";
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations =
                new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role,
                new CreateSurvivalPowerEvidenceV1.RoleObservation(
                        value.blockId(), network, value.gameTick(), value.speedRpm(),
                        value.stressEnabled(), value.stressCapacity(), value.stressLoad(),
                        value.overstressed())));
        CreateSurvivalPowerMappingV1.Entry mapping =
                CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C08);
        CreateSurvivalPowerEvidenceV1.Validation validation =
                CreateSurvivalPowerEvidenceV1.validate(
                        new CreateSurvivalPowerEvidenceV1.Candidate(
                                mapping, WATER_WHEEL,
                                "c08:water_wheel[x]->gearbox[z]->shaft[y]->large[y]"
                                        + "->small[y,xz-diagonal]@2x->large[y,same-axis]"
                                        + "->mixer[y,xz-diagonal]@2x",
                                observations, "c08-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || !accepted.ordinaryPlayerEligible()) {
            throw new IllegalStateException("C08 production gate changed: " + validation);
        }
        output.clearContent();
        cleanupBestEffort(fixture);
        if (fixture.changed.stream().anyMatch(
                position -> !fixture.level.getBlockState(position).isAir())) {
            throw new IllegalStateException("C08 candidate baseline did not restore");
        }
        LOGGER.info("C08_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} network={} speeds={}",
                WATER_WHEEL, observations.keySet(), network,
                observations.values().stream()
                        .map(value -> Double.toString(value.speedRpm())).toList());
        LOGGER.info("C08_SURVIVAL_POWER_PROCESS PASS recipe=create:mixing/andesite_alloy "
                        + "inputs=[minecraft:andesite x1,minecraft:iron_nugget x1] "
                        + "output=create:andesite_alloy x{} mixerCycleObserved={} realInputEntities=true",
                outputCount, fixture.mixerCycleObserved);
        LOGGER.info("C08_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} "
                        + "consumed={} returned={} duplicateWithdrawals={} duplicateReturns={} "
                        + "unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.consumed(),
                balance.returned(), balance.duplicateWithdrawals(), balance.duplicateReturns(),
                balance.unaccountedItems(), balance.materialLedgerBalanced());
        LOGGER.info("C08_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} "
                        + "ordinaryPlayerEligible={} rollbackVerified=true baselineRestored=true "
                        + "reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        LOGGER.info("C08_SURVIVAL_POWER_GAMETEST PASS minecraft=1.20.1 forge={} create={} "
                        + "realWaterWheel=true realGearing=true realMixer=true realMixingRecipe=true "
                        + "realInputEntities=true realOutput=true realMaterialLedger=true productionEligible=true",
                mixerPower.runtime().loaderVersion(),
                mixerPower.runtime().industrialModVersions().get("create"));
    }

    private static Map<String, KineticSnapshot> captureKinetics(Fixture fixture) {
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(fixture.level);
        Map<String, BlockPos> positions = new LinkedHashMap<>();
        positions.put("water_wheel", fixture.waterWheel);
        positions.put("bottom_gearbox", fixture.gearbox);
        positions.put("vertical_shaft", fixture.verticalShaft);
        positions.put("large_cogwheel_input", fixture.firstLargeCog);
        positions.put("small_cogwheel", fixture.smallCog);
        positions.put("large_cogwheel_output", fixture.secondLargeCog);
        positions.put("mechanical_mixer", fixture.mixer);
        Map<String, KineticSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> entry : positions.entrySet()) {
            AdapterResult<KineticSnapshot> result = adapter.captureKinetics(
                    new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(
                            blockPos(entry.getValue())));
            if (result instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                if (failure.code()
                        == dev.stevecreate.agent.adapter.api.AdapterFailureCode.LIFECYCLE_NOT_READY) {
                    throw new Pending(
                            "C08 kinetic network is still settling: " + entry.getKey());
                }
                throw new IllegalStateException("C08 kinetic capture failed: " + failure);
            }
            KineticSnapshot snapshot =
                    ((AdapterResult.Success<KineticSnapshot>) result).value();
            if (snapshot.speedRpm() <= 0 || snapshot.overstressed()) {
                throw new Pending("C08 role is not positively powered: " + entry.getKey());
            }
            snapshots.put(entry.getKey(), snapshot);
        }
        OptionalLong firstNetwork = snapshots.values().stream()
                .findFirst().orElseThrow().networkId();
        if (firstNetwork.isEmpty() || snapshots.values().stream().anyMatch(
                value -> value.networkId().isEmpty()
                        || value.networkId().getAsLong() != firstNetwork.getAsLong())) {
            throw new Pending("C08 roles do not yet share one live Create network");
        }
        return snapshots;
    }

    private static void feedRealBasin(Fixture fixture) {
        BasinBlockEntity basin = basin(fixture.level, fixture.basin);
        MechanicalMixerBlockEntity mixer = mixer(fixture.level, fixture.mixer);
        if (basin == null || mixer == null) {
            throw new IllegalStateException("C08 basin or mixer is missing before feed");
        }
        Recipe<?> recipe = fixture.level.getRecipeManager()
                .byKey(key("create", "mixing/andesite_alloy")).orElse(null);
        if (!(recipe instanceof ProcessingRecipe<?> processing)
                || recipe.getType() != AllRecipeTypes.MIXING.getType()
                || processing.getRequiredHeat() != HeatCondition.NONE
                || processing.getIngredients().size() != 2
                || processing.getRollableResults().size() != 1) {
            throw new IllegalStateException("C08 live Create mixing recipe is missing or changed");
        }
        if (!matchesExactly(processing.getIngredients(), ANDESITE, IRON_NUGGET)) {
            throw new IllegalStateException("C08 live mixing ingredients changed");
        }
        ProcessingOutput result = processing.getRollableResults().get(0);
        if (!ANDESITE_ALLOY.equals(itemId(result.getStack()))
                || result.getStack().getCount() != 1 || result.getChance() != 1.0F) {
            throw new IllegalStateException("C08 live mixing output is not guaranteed alloy x1");
        }
        for (ResourceId input : List.of(ANDESITE, IRON_NUGGET)) {
            ItemEntity entity = new ItemEntity(fixture.level,
                    fixture.basin.getX() + 0.5D, fixture.basin.getY() + 1.1D,
                    fixture.basin.getZ() + 0.5D, new ItemStack(item(input), 1));
            if (!fixture.level.addFreshEntity(entity)) {
                throw new IllegalStateException("C08 input entity was rejected");
            }
            fixture.inputEntities.add(entity.getUUID());
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    basin.getInputInventory(), entity.getItem().copy(), false);
            if (!remainder.isEmpty()) {
                throw new IllegalStateException("C08 basin rejected exact input entity");
            }
            entity.discard();
        }
        basin.notifyChangeOfContents();
        if (!BasinRecipe.match(basin, recipe)) {
            throw new IllegalStateException("C08 staged real inputs do not match live recipe");
        }
        mixer.basinChecker.scheduleUpdate();
        fixture.fed = true;
    }

    private static boolean matchesExactly(
            List<Ingredient> ingredients, ResourceId first, ResourceId second) {
        if (ingredients.size() != 2) return false;
        ItemStack firstStack = new ItemStack(item(first));
        ItemStack secondStack = new ItemStack(item(second));
        return (ingredients.get(0).test(firstStack) && ingredients.get(1).test(secondStack))
                || (ingredients.get(0).test(secondStack) && ingredients.get(1).test(firstStack));
    }

    private static void transferRealOutputs(BasinBlockEntity basin, ChestBlockEntity chest) {
        for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++) {
            ItemStack stack = basin.getOutputInventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (!ANDESITE_ALLOY.equals(itemId(stack)) || stack.hasTag()
                    || stack.hasCraftingRemainingItem()) {
                throw new IllegalStateException(
                        "C08 basin produced unexpected output " + itemId(stack));
            }
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    new InvWrapper(chest), stack.copy(), false);
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) basin.getOutputInventory().extractItem(slot, moved, false);
        }
        basin.notifyChangeOfContents();
        chest.setChanged();
    }

    private static void cleanupBestEffort(Fixture fixture) {
        BasinBlockEntity basin = basin(fixture.level, fixture.basin);
        if (basin != null) {
            for (int slot = 0; slot < basin.getInputInventory().getSlots(); slot++) {
                basin.getInputInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
            for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++) {
                basin.getOutputInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        ChestBlockEntity output = fixture.level.getBlockEntity(fixture.outputChest)
                instanceof ChestBlockEntity chest ? chest : null;
        if (output != null) output.clearContent();
        for (BlockPos position : fixture.changed) {
            fixture.level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        for (UUID entityId : fixture.inputEntities) {
            Entity entity = fixture.level.getEntity(entityId);
            if (entity != null) entity.discard();
        }
    }

    private static void place(
            ServerLevel level, BlockPos position, ResourceLocation key, Object orientation) {
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null) throw new IllegalStateException("Missing C08 block " + key);
        BlockState state = block.defaultBlockState();
        if (orientation instanceof Direction.Axis axis) {
            state = state.setValue(BlockStateProperties.AXIS, axis);
        } else if (orientation instanceof Direction facing) {
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Failed to place C08 block " + key);
        }
    }

    private static void placeFluid(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Failed to place C08 fluid");
        }
    }

    private static WaterWheelBlockEntity waterWheel(ServerLevel level, BlockPos position) {
        return level.getBlockEntity(position) instanceof WaterWheelBlockEntity value
                ? value : null;
    }

    private static MechanicalMixerBlockEntity mixer(ServerLevel level, BlockPos position) {
        return level.getBlockEntity(position) instanceof MechanicalMixerBlockEntity value
                ? value : null;
    }

    private static BasinBlockEntity basin(ServerLevel level, BlockPos position) {
        return level.getBlockEntity(position) instanceof BasinBlockEntity value ? value : null;
    }

    private static ChestBlockEntity chest(ServerLevel level, BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("C08 chest block entity is missing");
        }
        return chest;
    }

    private static void put(ChestBlockEntity chest, int slot, ResourceId itemId, int count) {
        chest.setItem(slot, new ItemStack(item(itemId), count));
    }

    private static void decrement(ChestBlockEntity chest, int slot, int count) {
        ItemStack stack = chest.getItem(slot);
        if (stack.isEmpty() || stack.getCount() != count) {
            throw new IllegalStateException("C08 source withdrawal mismatch");
        }
        chest.setItem(slot, ItemStack.EMPTY);
    }

    private static Item item(ResourceId id) {
        Item item = ForgeRegistries.ITEMS.getValue(key(id.namespace(), id.path()));
        if (item == null) throw new IllegalStateException("Missing C08 item " + id);
        return item;
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : id(key.toString());
    }

    private static int count(ChestBlockEntity chest, ResourceId item) {
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (item.equals(itemId(stack))) count += stack.getCount();
        }
        return count;
    }

    private static BlockPos3i blockPos(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static ResourceLocation key(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private record MaterialSpec(ResourceId item, int quantity, boolean consumed) {}

    private record MaterialWithdrawal(
            ResourceId transactionId, int slot, MaterialSpec spec) {}

    private static final class Fixture {
        private final ServerLevel level;
        private final ChestBlockEntity sourceChest;
        private final ProjectMaterialLedger ledger;
        private final List<BlockPos> changed;
        private final List<MaterialWithdrawal> withdrawals;
        private final BlockPos waterWheel;
        private final BlockPos gearbox;
        private final BlockPos verticalShaft;
        private final BlockPos firstLargeCog;
        private final BlockPos smallCog;
        private final BlockPos secondLargeCog;
        private final BlockPos mixer;
        private final BlockPos basin;
        private final BlockPos outputChest;
        private final List<UUID> inputEntities = new ArrayList<>();
        private boolean fed;
        private boolean mixerCycleObserved;
        private boolean completed;

        private Fixture(
                ServerLevel level, ChestBlockEntity sourceChest,
                ProjectMaterialLedger ledger, List<BlockPos> changed,
                List<MaterialWithdrawal> withdrawals, BlockPos waterWheel,
                BlockPos gearbox, BlockPos verticalShaft, BlockPos firstLargeCog,
                BlockPos smallCog, BlockPos secondLargeCog, BlockPos mixer,
                BlockPos basin, BlockPos outputChest) {
            this.level = level;
            this.sourceChest = sourceChest;
            this.ledger = ledger;
            this.changed = changed;
            this.withdrawals = withdrawals;
            this.waterWheel = waterWheel;
            this.gearbox = gearbox;
            this.verticalShaft = verticalShaft;
            this.firstLargeCog = firstLargeCog;
            this.smallCog = smallCog;
            this.secondLargeCog = secondLargeCog;
            this.mixer = mixer;
            this.basin = basin;
            this.outputChest = outputChest;
        }
    }

    private static final class Pending extends RuntimeException {
        private Pending(String message) { super(message); }
    }
}
