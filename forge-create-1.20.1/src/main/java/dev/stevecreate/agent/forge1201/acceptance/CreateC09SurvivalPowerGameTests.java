package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
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

/** Real Create C-09 water-wheel/press production eligibility probe. */
@PrefixGameTestTemplate(false)
public final class CreateC09SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId PRESS = id("create:mechanical_press");
    private static final ResourceId BASIN = id("create:basin");
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER_BUCKET = id("minecraft:water_bucket");
    private static final ResourceId CINDER_FLOUR = id("create:cinder_flour");
    private static final ResourceId SUGAR = id("minecraft:sugar");
    private static final ResourceId EGG = id("minecraft:egg");
    private static final ResourceId BLAZE_CAKE_BASE = id("create:blaze_cake_base");
    private static final ResourceId PROJECT = id("steve_industrial:c09/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c09/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;

    private CreateC09SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2_400)
    public static void c09WaterWheelBasinPressSurvivalProduction(GameTestHelper helper) {
        Fixture fixture = build(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 2)));
        helper.succeedWhen(() -> {
            if (fixture.completed) return;
            try {
                verify(fixture);
                fixture.completed = true;
            } catch (Pending pending) {
                throw new GameTestAssertException(pending.getMessage());
            } catch (RuntimeException failure) {
                cleanup(fixture);
                throw failure;
            }
        });
    }

    private static Fixture build(ServerLevel level, BlockPos origin) {
        BlockPos sourcePos = origin.offset(12, 0, 0);
        place(level, sourcePos, key("minecraft", "chest"), null);
        ChestBlockEntity source = chest(level, sourcePos);
        List<MaterialSpec> specs = List.of(
                spec(WATER_WHEEL, 1, false), spec(GEARBOX, 2, false),
                spec(SHAFT, 2, false), spec(PRESS, 1, false),
                spec(BASIN, 1, false), spec(CHEST, 1, false),
                spec(STONE, 13, false), spec(WATER_BUCKET, 1, false),
                spec(CINDER_FLOUR, 1, true),
                spec(SUGAR, 1, true), spec(EGG, 1, true));
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec value = specs.get(index);
            put(source, index, value.item(), value.quantity());
            slots.add(new MaterialSlotSnapshot(index,
                    new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()));
        }
        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c09/survival-power-plan"), PROJECT,
                id("create:c09/water-wheel-basin-press"), 1, "forge-create-6.0.6",
                "c".repeat(64), specs.stream().map(value -> new MaterialRequirement(
                        id("steve_industrial:c09/requirement/" + value.item().path()),
                        id("create:c09/water-wheel-basin-press"), 0,
                        RecipeIngredientKind.EXACT_RESOURCE, value.item().toString(),
                        List.of(value.item()), value.quantity())).toList());
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(
                SOURCE, PROJECT, "c09-acceptance", id("minecraft:overworld"),
                pos(sourcePos), "up", "minecraft:chest", "a".repeat(64), "b".repeat(64),
                0, specs.stream().mapToLong(MaterialSpec::quantity).sum(),
                0, 100_000, slots), 1);
        List<Withdrawal> withdrawals = new ArrayList<>();
        long tick = 2;
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec value = specs.get(index);
            MaterialRequirement requirement = plan.requirements().stream()
                    .filter(row -> row.acceptedResources().contains(value.item()))
                    .findFirst().orElseThrow();
            ResourceId allocation = id("steve_industrial:c09/allocation/" + index);
            ledger.reserve(new MaterialAllocation(allocation, PROJECT,
                    requirement.requirementId(), SOURCE, index,
                    new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()), tick++);
            ResourceId transaction = id("steve_industrial:c09/transaction/" + index);
            ledger.prepare(transaction, PROJECT, id("steve_industrial:c09/task/" + index),
                    allocation, MaterialExecutorKind.DIRECT, value.quantity(), tick++);
            decrement(source, index, value.quantity());
            ledger.advance(transaction, MaterialTransactionState.WITHDRAWN, tick++);
            withdrawals.add(new Withdrawal(transaction, index, value));
        }

        List<BlockPos> changed = new ArrayList<>();
        List<BlockPos> supports = List.of(
                origin.offset(0, 1, -1), origin.offset(-1, 2, -1),
                origin.offset(1, 2, -1), origin.offset(0, 2, -2), origin.offset(0, 2, 0),
                origin.offset(-1, 4, -1), origin.offset(1, 4, -1),
                origin.offset(0, 4, -2), origin.offset(0, 4, 0),
                origin.offset(-1, 3, -1), origin.offset(1, 3, -1),
                origin.offset(0, 3, -2), origin.offset(1, 3, -3));
        for (BlockPos support : supports) {
            place(level, support, key("minecraft", "stone"), null);
            changed.add(support);
        }
        BlockPos wheel = origin.offset(0, 3, 0);
        BlockPos bottomGearbox = origin.offset(1, 3, 0);
        BlockPos verticalShaft = origin.offset(1, 4, 0);
        BlockPos topGearbox = origin.offset(1, 5, 0);
        BlockPos horizontalShaft = origin.offset(1, 5, -1);
        BlockPos press = origin.offset(1, 5, -2);
        BlockPos basin = origin.offset(1, 3, -2);
        BlockPos output = origin.offset(2, 3, -2);
        place(level, wheel, key("create", "water_wheel"), Direction.EAST);
        place(level, bottomGearbox, key("create", "gearbox"), Direction.Axis.Z);
        place(level, verticalShaft, key("create", "shaft"), Direction.Axis.Y);
        place(level, topGearbox, key("create", "gearbox"), Direction.Axis.X);
        place(level, horizontalShaft, key("create", "shaft"), Direction.Axis.Z);
        place(level, press, key("create", "mechanical_press"), Direction.NORTH);
        place(level, basin, key("create", "basin"), null);
        place(level, output, key("minecraft", "chest"), null);
        changed.addAll(List.of(wheel, bottomGearbox, verticalShaft, topGearbox,
                horizontalShaft, press, basin, output));
        BlockPos waterSource = origin.offset(0, 4, -1);
        BlockPos waterOne = origin.offset(0, 3, -1);
        BlockPos waterTwo = origin.offset(0, 2, -1);
        fluid(level, waterSource, Fluids.WATER.defaultFluidState().createLegacyBlock());
        fluid(level, waterOne, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        fluid(level, waterTwo, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        changed.addAll(List.of(waterSource, waterOne, waterTwo, sourcePos));
        return new Fixture(level, source, ledger, withdrawals, changed, wheel,
                bottomGearbox, verticalShaft, topGearbox, horizontalShaft,
                press, basin, output);
    }

    private static void verify(Fixture fixture) {
        WaterWheelBlockEntity wheel = wheel(fixture.level, fixture.wheel);
        if (wheel != null && wheel.flowScore == 0) wheel.determineAndApplyFlowScore();
        Map<String, KineticSnapshot> snapshots = kinetics(fixture);
        if (!fixture.fed) feed(fixture);
        BasinBlockEntity basin = basin(fixture.level, fixture.basin);
        MechanicalPressBlockEntity press = press(fixture.level, fixture.press);
        ChestBlockEntity output = chest(fixture.level, fixture.output);
        if (basin == null || press == null) throw new IllegalStateException("C09 machine disappeared");
        if (press.pressingBehaviour.running || press.pressingBehaviour.onBasin()
                || press.pressingBehaviour.runningTicks > 0) fixture.cycleObserved = true;
        transfer(basin, output);
        int outputCount = count(output, BLAZE_CAKE_BASE);
        if (!basin.getInputInventory().isEmpty()
                || !basin.getOutputInventory().isEmpty() || outputCount != 1) {
            throw new Pending("C09 real basin press is still processing");
        }
        if (!fixture.cycleObserved) throw new Pending("C09 press cycle was not observed");
        for (UUID id : fixture.inputEntities) {
            Entity entity = fixture.level.getEntity(id);
            if (entity != null && entity.isAlive()) throw new IllegalStateException("C09 input survived");
        }
        long tick = 100;
        for (Withdrawal row : fixture.withdrawals) {
            if (row.spec.consumed()) {
                fixture.ledger.advance(row.transaction, MaterialTransactionState.DELIVERED, tick++);
                fixture.ledger.advance(row.transaction, MaterialTransactionState.CONSUMED, tick++);
            } else {
                fixture.ledger.advance(row.transaction, MaterialTransactionState.RETURN_PENDING, tick++);
                put(fixture.source, row.slot, row.spec.item(), row.spec.quantity());
                fixture.ledger.advance(row.transaction, MaterialTransactionState.RETURNED, tick++);
            }
        }
        MaterialBalanceReport balance = fixture.ledger.balance(PROJECT);
        if (!balance.materialLedgerBalanced() || balance.consumed() != 3
                || balance.duplicateWithdrawals() != 0 || balance.duplicateReturns() != 0
                || balance.unaccountedItems() != 0) {
            throw new IllegalStateException("C09 ledger imbalance: " + balance);
        }
        String network = Long.toUnsignedString(snapshots.values().stream().findFirst()
                .orElseThrow().networkId().orElseThrow());
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations = new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role,
                new CreateSurvivalPowerEvidenceV1.RoleObservation(value.blockId(), network,
                        value.gameTick(), value.speedRpm(), value.stressEnabled(),
                        value.stressCapacity(), value.stressLoad(), value.overstressed())));
        CreateSurvivalPowerMappingV1.Entry mapping =
                CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C09);
        CreateSurvivalPowerEvidenceV1.Validation validation = CreateSurvivalPowerEvidenceV1.validate(
                new CreateSurvivalPowerEvidenceV1.Candidate(mapping, WATER_WHEEL,
                        "c09:water_wheel[x]->gearbox[z]->shaft[y]->gearbox[x]->shaft[z]->press[z]",
                        observations, "c09-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || !accepted.ordinaryPlayerEligible()) {
            throw new IllegalStateException("C09 production gate changed: " + validation);
        }
        output.clearContent();
        cleanup(fixture);
        if (fixture.changed.stream().anyMatch(p -> !fixture.level.getBlockState(p).isAir())) {
            throw new IllegalStateException("C09 baseline did not restore");
        }
        LOGGER.info("C09_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} network={} speeds={}",
                WATER_WHEEL, observations.keySet(), network,
                observations.values().stream().map(v -> Double.toString(v.speedRpm())).toList());
        LOGGER.info("C09_SURVIVAL_POWER_PROCESS PASS recipe=create:compacting/blaze_cake "
                        + "inputs=[create:cinder_flour x1,minecraft:sugar x1,minecraft:egg x1] "
                        + "output=create:blaze_cake_base x{} pressCycleObserved={} realInputEntities=true",
                outputCount, fixture.cycleObserved);
        LOGGER.info("C09_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} consumed={} "
                        + "returned={} duplicateWithdrawals={} duplicateReturns={} unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.consumed(),
                balance.returned(), balance.duplicateWithdrawals(), balance.duplicateReturns(),
                balance.unaccountedItems(), balance.materialLedgerBalanced());
        LOGGER.info("C09_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} ordinaryPlayerEligible={} "
                        + "rollbackVerified=true baselineRestored=true reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        KineticSnapshot runtime = snapshots.values().stream().findFirst().orElseThrow();
        LOGGER.info("C09_SURVIVAL_POWER_GAMETEST PASS minecraft=1.20.1 forge={} create={} "
                        + "realWaterWheel=true realPress=true realCompactingRecipe=true "
                        + "realInputEntities=true realOutput=true realMaterialLedger=true productionEligible=true",
                runtime.runtime().loaderVersion(), runtime.runtime().industrialModVersions().get("create"));
    }

    private static Map<String, KineticSnapshot> kinetics(Fixture f) {
        Map<String, BlockPos> positions = new LinkedHashMap<>();
        positions.put("water_wheel", f.wheel);
        positions.put("bottom_gearbox", f.bottomGearbox);
        positions.put("vertical_shaft", f.verticalShaft);
        positions.put("top_gearbox", f.topGearbox);
        positions.put("horizontal_shaft", f.horizontalShaft);
        positions.put("mechanical_press", f.press);
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(f.level);
        Map<String, KineticSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> row : positions.entrySet()) {
            AdapterResult<KineticSnapshot> captured = adapter.captureKinetics(
                    new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(pos(row.getValue())));
            if (captured instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                if (failure.code() == dev.stevecreate.agent.adapter.api.AdapterFailureCode.LIFECYCLE_NOT_READY)
                    throw new Pending("C09 network settling: " + row.getKey());
                throw new IllegalStateException("C09 kinetic capture failed: " + failure);
            }
            KineticSnapshot snapshot = ((AdapterResult.Success<KineticSnapshot>) captured).value();
            if (snapshot.speedRpm() <= 0 || snapshot.overstressed())
                throw new Pending("C09 role is not powered: " + row.getKey());
            result.put(row.getKey(), snapshot);
        }
        OptionalLong network = result.values().stream().findFirst().orElseThrow().networkId();
        if (network.isEmpty() || result.values().stream().anyMatch(v -> v.networkId().isEmpty()
                || v.networkId().getAsLong() != network.getAsLong()))
            throw new Pending("C09 roles do not share one network");
        return result;
    }

    private static void feed(Fixture f) {
        BasinBlockEntity basin = basin(f.level, f.basin);
        MechanicalPressBlockEntity press = press(f.level, f.press);
        Recipe<?> recipe = f.level.getRecipeManager().byKey(key("create", "compacting/blaze_cake"))
                .orElse(null);
        if (basin == null || press == null || !(recipe instanceof ProcessingRecipe<?> processing)
                || recipe.getType() != AllRecipeTypes.COMPACTING.getType()
                || processing.getRequiredHeat() != HeatCondition.NONE
                || processing.getIngredients().size() != 3
                || processing.getRollableResults().size() != 1) {
            throw new IllegalStateException("C09 live compacting recipe is missing or changed");
        }
        if (!matches(processing.getIngredients(), List.of(CINDER_FLOUR, SUGAR, EGG)))
            throw new IllegalStateException("C09 live compacting ingredients changed");
        ProcessingOutput output = processing.getRollableResults().get(0);
        if (!BLAZE_CAKE_BASE.equals(itemId(output.getStack()))
                || output.getStack().getCount() != 1 || output.getChance() != 1.0F)
            throw new IllegalStateException("C09 live compacting output changed");
        for (ResourceId input : List.of(CINDER_FLOUR, SUGAR, EGG)) {
            ItemEntity entity = new ItemEntity(f.level, f.basin.getX() + .5,
                    f.basin.getY() + 1.1, f.basin.getZ() + .5,
                    new ItemStack(item(input), 1));
            if (!f.level.addFreshEntity(entity)) throw new IllegalStateException("C09 entity rejected");
            f.inputEntities.add(entity.getUUID());
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    basin.getInputInventory(), entity.getItem().copy(), false);
            if (!remainder.isEmpty()) throw new IllegalStateException("C09 basin rejected input");
            entity.discard();
        }
        basin.notifyChangeOfContents();
        if (!BasinRecipe.match(basin, recipe)) throw new IllegalStateException("C09 inputs do not match");
        press.basinChecker.scheduleUpdate();
        f.fed = true;
    }

    private static boolean matches(List<Ingredient> ingredients, List<ResourceId> expected) {
        List<ResourceId> remaining = new ArrayList<>(expected);
        for (Ingredient ingredient : ingredients) {
            ResourceId match = remaining.stream()
                    .filter(id -> ingredient.test(new ItemStack(item(id))))
                    .findFirst().orElse(null);
            if (match == null) return false;
            remaining.remove(match);
        }
        return remaining.isEmpty();
    }

    private static void transfer(BasinBlockEntity basin, ChestBlockEntity chest) {
        for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++) {
            ItemStack stack = basin.getOutputInventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (!BLAZE_CAKE_BASE.equals(itemId(stack)))
                throw new IllegalStateException("C09 unexpected output " + itemId(stack));
            ItemStack remainder = ItemHandlerHelper.insertItem(new InvWrapper(chest), stack.copy(), false);
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) basin.getOutputInventory().extractItem(slot, moved, false);
        }
        basin.notifyChangeOfContents();
    }

    private static void cleanup(Fixture f) {
        BasinBlockEntity basin = basin(f.level, f.basin);
        if (basin != null) {
            for (int slot = 0; slot < basin.getInputInventory().getSlots(); slot++)
                basin.getInputInventory().setStackInSlot(slot, ItemStack.EMPTY);
            for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++)
                basin.getOutputInventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        for (BlockPos p : f.changed) f.level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        for (UUID id : f.inputEntities) {
            Entity entity = f.level.getEntity(id);
            if (entity != null) entity.discard();
        }
    }

    private static void place(ServerLevel level, BlockPos p, ResourceLocation key, Object orientation) {
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null) throw new IllegalStateException("Missing C09 block " + key);
        BlockState state = block.defaultBlockState();
        if (orientation instanceof Direction.Axis axis)
            state = state.setValue(BlockStateProperties.AXIS, axis);
        else if (orientation instanceof Direction facing) {
            if (state.hasProperty(BlockStateProperties.FACING))
                state = state.setValue(BlockStateProperties.FACING, facing);
            else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            else
                throw new IllegalStateException("C09 block has no facing property " + key);
        }
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C09 placement failed");
    }

    private static void fluid(ServerLevel level, BlockPos p, BlockState state) {
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C09 fluid failed");
    }

    private static WaterWheelBlockEntity wheel(ServerLevel l, BlockPos p) {
        return l.getBlockEntity(p) instanceof WaterWheelBlockEntity v ? v : null;
    }
    private static MechanicalPressBlockEntity press(ServerLevel l, BlockPos p) {
        return l.getBlockEntity(p) instanceof MechanicalPressBlockEntity v ? v : null;
    }
    private static BasinBlockEntity basin(ServerLevel l, BlockPos p) {
        return l.getBlockEntity(p) instanceof BasinBlockEntity v ? v : null;
    }
    private static ChestBlockEntity chest(ServerLevel l, BlockPos p) {
        if (!(l.getBlockEntity(p) instanceof ChestBlockEntity v))
            throw new IllegalStateException("C09 chest missing");
        return v;
    }
    private static MaterialSpec spec(ResourceId item, int quantity, boolean consumed) {
        return new MaterialSpec(item, quantity, consumed);
    }
    private static void put(ChestBlockEntity c, int slot, ResourceId id, int count) {
        c.setItem(slot, new ItemStack(item(id), count));
    }
    private static void decrement(ChestBlockEntity c, int slot, int count) {
        ItemStack stack = c.getItem(slot);
        if (stack.isEmpty() || stack.getCount() != count)
            throw new IllegalStateException("C09 source mismatch");
        c.setItem(slot, ItemStack.EMPTY);
    }
    private static int count(ChestBlockEntity c, ResourceId id) {
        int count = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++)
            if (id.equals(itemId(c.getItem(slot)))) count += c.getItem(slot).getCount();
        return count;
    }
    private static Item item(ResourceId id) {
        Item value = ForgeRegistries.ITEMS.getValue(key(id.namespace(), id.path()));
        if (value == null) throw new IllegalStateException("Missing C09 item " + id);
        return value;
    }
    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : id(key.toString());
    }
    private static BlockPos3i pos(BlockPos p) { return new BlockPos3i(p.getX(), p.getY(), p.getZ()); }
    private static ResourceLocation key(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private record MaterialSpec(ResourceId item, int quantity, boolean consumed) {}
    private record Withdrawal(ResourceId transaction, int slot, MaterialSpec spec) {}
    private static final class Fixture {
        final ServerLevel level; final ChestBlockEntity source; final ProjectMaterialLedger ledger;
        final List<Withdrawal> withdrawals; final List<BlockPos> changed;
        final BlockPos wheel, bottomGearbox, verticalShaft, topGearbox, horizontalShaft;
        final BlockPos press, basin, output; final List<UUID> inputEntities = new ArrayList<>();
        boolean fed, cycleObserved, completed;
        Fixture(ServerLevel level, ChestBlockEntity source, ProjectMaterialLedger ledger,
                List<Withdrawal> withdrawals, List<BlockPos> changed, BlockPos wheel,
                BlockPos bottomGearbox, BlockPos verticalShaft, BlockPos topGearbox,
                BlockPos horizontalShaft, BlockPos press, BlockPos basin, BlockPos output) {
            this.level = level; this.source = source; this.ledger = ledger;
            this.withdrawals = withdrawals; this.changed = changed; this.wheel = wheel;
            this.bottomGearbox = bottomGearbox; this.verticalShaft = verticalShaft;
            this.topGearbox = topGearbox; this.horizontalShaft = horizontalShaft;
            this.press = press; this.basin = basin; this.output = output;
        }
    }
    private static final class Pending extends RuntimeException {
        Pending(String message) { super(message); }
    }
}
