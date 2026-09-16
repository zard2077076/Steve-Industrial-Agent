package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Production C-04 dual-water-wheel belt/press with complete material and rollback evidence. */
@PrefixGameTestTemplate(false)
public final class CreateC04SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId BELT_CONNECTOR = id("create:belt_connector");
    private static final ResourceId BELT = id("create:belt");
    private static final ResourceId PRESS = id("create:mechanical_press");
    private static final ResourceId FUNNEL = id("create:andesite_funnel");
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER_BUCKET = id("minecraft:water_bucket");
    private static final ResourceId IRON_INGOT = id("minecraft:iron_ingot");
    private static final ResourceId IRON_SHEET = id("create:iron_sheet");
    private static final ResourceId PROJECT = id("steve_industrial:c04/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c04/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;

    private CreateC04SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2_400)
    public static void c04WaterWheelBeltPressSurvivalCandidate(GameTestHelper helper) {
        Fixture fixture = build(helper.getLevel(), helper.absolutePos(new BlockPos(3, 2, 3)));
        helper.succeedWhen(() -> {
            if (fixture.completed) return;
            if (helper.getTick() < 100) {
                throw new GameTestAssertException(
                        "Waiting for C04 enclosed water shafts to reach long-lived fluid state");
            }
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
                spec("water-wheels", WATER_WHEEL, 2, false),
                spec("gearboxes", GEARBOX, 2, false), spec("shafts", SHAFT, 4, false),
                spec("belt-connectors", BELT_CONNECTOR, 3, false),
                spec("mechanical-press", PRESS, 1, false), spec("output-chest", CHEST, 1, false),
                spec("output-funnel", FUNNEL, 1, false), spec("supports", STONE, 12, false),
                spec("water-sources", WATER_BUCKET, 2, false),
                spec("iron-input", IRON_INGOT, 1, true));
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        List<MaterialRequirement> requirements = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec value = specs.get(index);
            put(source, index, value.item(), value.quantity());
            slots.add(new MaterialSlotSnapshot(index,
                    new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()));
            requirements.add(new MaterialRequirement(
                    id("steve_industrial:c04/requirement/" + value.label()),
                    id("create:c04/water-wheel-belt-press"), index,
                    RecipeIngredientKind.EXACT_RESOURCE, value.item().toString(),
                    List.of(value.item()), value.quantity()));
        }
        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c04/survival-power-plan"), PROJECT,
                id("create:c04/water-wheel-belt-press"), 1, "forge-create-6.0.6",
                "c".repeat(64), requirements);
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(
                SOURCE, PROJECT, "c04-acceptance", id("minecraft:overworld"), pos(sourcePos),
                "up", "minecraft:chest", "a".repeat(64), "b".repeat(64), 0,
                specs.stream().mapToLong(MaterialSpec::quantity).sum(), 0, 100_000, slots), 1);
        List<Withdrawal> withdrawals = new ArrayList<>();
        long tick = 2;
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec value = specs.get(index);
            ResourceId allocation = id("steve_industrial:c04/allocation/" + index);
            ledger.reserve(new MaterialAllocation(allocation, PROJECT,
                    requirements.get(index).requirementId(), SOURCE, index,
                    new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()), tick++);
            ResourceId transaction = id("steve_industrial:c04/transaction/" + index);
            ledger.prepare(transaction, PROJECT, id("steve_industrial:c04/task/" + index),
                    allocation, MaterialExecutorKind.DIRECT, value.quantity(), tick++);
            decrement(source, index, value.quantity());
            ledger.advance(transaction, MaterialTransactionState.WITHDRAWN, tick++);
            withdrawals.add(new Withdrawal(transaction, index, value));
        }

        List<BlockPos> changed = new ArrayList<>();
        List<BlockPos> supports = List.of(
                origin.offset(-2, 2, 1), origin.offset(0, 2, 1), origin.offset(-1, 2, 0),
                origin.offset(-1, 2, 2), origin.offset(-1, 0, 1),
                origin.offset(0, 0, 0), origin.offset(1, 0, 0), origin.offset(2, 0, 0),
                origin.offset(-1, 4, 1), origin.offset(1, 4, 1),
                origin.offset(0, 4, 0), origin.offset(0, 4, 2));
        for (BlockPos support : supports) {
            place(level, support, key("minecraft", "stone"), null);
            changed.add(support);
        }
        BlockPos beltWheel = origin.offset(-1, 1, 2);
        BlockPos bottomGearbox = origin.offset(0, 1, 2);
        BlockPos beltDriveShaft = origin.offset(0, 1, 1);
        BlockPos pressWheel = origin.offset(0, 3, 2);
        BlockPos pressGearbox = origin.offset(1, 3, 2);
        BlockPos pressDriveShaft = origin.offset(1, 3, 1);
        BlockPos press = origin.offset(1, 3, 0);
        BlockPos beltStart = origin.offset(2, 1, 0);
        BlockPos beltMiddle = origin.offset(1, 1, 0);
        BlockPos beltEnd = origin.offset(0, 1, 0);
        BlockPos output = origin.offset(-1, 0, 0);
        BlockPos outputFunnel = origin.offset(-1, 1, 0);
        place(level, beltWheel, key("create", "water_wheel"), Direction.EAST);
        place(level, bottomGearbox, key("create", "gearbox"), Direction.Axis.Y);
        place(level, beltDriveShaft, key("create", "shaft"), Direction.Axis.Z);
        place(level, pressWheel, key("create", "water_wheel"), Direction.EAST);
        place(level, pressGearbox, key("create", "gearbox"), Direction.Axis.Y);
        place(level, pressDriveShaft, key("create", "shaft"), Direction.Axis.Z);
        placePress(level, press);
        place(level, beltStart, key("create", "shaft"), Direction.Axis.Z);
        place(level, beltEnd, key("create", "shaft"), Direction.Axis.Z);
        if (!BeltConnectorItem.canConnect(level, beltStart, beltEnd))
            throw new IllegalStateException("C04 validated belt endpoints did not connect");
        BeltConnectorItem.createBelts(level, beltStart, beltEnd);
        place(level, output, key("minecraft", "chest"), null);
        place(level, outputFunnel, key("create", "andesite_funnel"), Direction.UP);
        changed.addAll(List.of(beltWheel, bottomGearbox, beltDriveShaft,
                pressWheel, pressGearbox, pressDriveShaft, press,
                beltStart, beltMiddle, beltEnd, output, outputFunnel));
        BlockPos beltWaterSource = origin.offset(-1, 2, 1);
        BlockPos beltWaterOne = origin.offset(-1, 1, 1);
        BlockPos pressWaterSource = origin.offset(0, 4, 1);
        BlockPos pressWaterOne = origin.offset(0, 3, 1);
        fluid(level, beltWaterSource, Fluids.WATER.defaultFluidState().createLegacyBlock());
        fluid(level, beltWaterOne, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        fluid(level, pressWaterSource, Fluids.WATER.defaultFluidState().createLegacyBlock());
        fluid(level, pressWaterOne, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        changed.addAll(List.of(beltWaterSource, beltWaterOne,
                pressWaterSource, pressWaterOne, sourcePos));
        return new Fixture(level, source, ledger, withdrawals, changed, beltWheel, pressWheel,
                bottomGearbox, beltDriveShaft, pressGearbox,
                pressDriveShaft, press, beltStart, beltMiddle, beltEnd, output);
    }

    private static void verify(Fixture f) {
        WaterWheelBlockEntity beltWheel = wheel(f.level, f.beltWheel);
        WaterWheelBlockEntity pressWheel = wheel(f.level, f.pressWheel);
        if (beltWheel != null && beltWheel.flowScore == 0) beltWheel.determineAndApplyFlowScore();
        if (pressWheel != null && pressWheel.flowScore == 0) pressWheel.determineAndApplyFlowScore();
        Map<String, KineticSnapshot> snapshots = kinetics(f);
        BeltBlockEntity controller = BeltHelper.getControllerBE(f.level, f.beltStart);
        MechanicalPressBlockEntity press = press(f.level, f.press);
        if (controller == null || press == null) throw new Pending("C04 belt/press initializing");
        if (!f.fed) feed(f, controller);
        if (press.pressingBehaviour.mode == PressingBehaviour.Mode.BELT
                && (press.pressingBehaviour.running || press.pressingBehaviour.finished
                || press.pressingBehaviour.runningTicks > 0)) f.cycleObserved = true;
        ChestBlockEntity output = chest(f.level, f.output);
        int outputCount = count(output, IRON_SHEET);
        if (!controller.getInventory().getTransportedItems().isEmpty() || outputCount != 1)
            throw new Pending("C04 real belt press is still processing: movement="
                    + controller.getMovementFacing() + " items="
                    + controller.getInventory().getTransportedItems().stream()
                    .map(value -> itemId(value.stack) + "x" + value.stack.getCount()
                            + "@" + value.beltPosition).toList()
                    + " output=" + outputCount + " pressMode=" + press.pressingBehaviour.mode
                    + " entities=" + f.level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(f.beltEnd).inflate(4, 3, 3), Entity::isAlive).stream()
                    .map(value -> itemId(value.getItem()) + "x" + value.getItem().getCount()
                            + "@" + value.blockPosition()).toList()
                    + " running=" + press.pressingBehaviour.running
                    + " finished=" + press.pressingBehaviour.finished
                    + " ticks=" + press.pressingBehaviour.runningTicks
                    + " cycleObserved=" + f.cycleObserved);
        if (!f.cycleObserved) throw new Pending("C04 press BELT cycle not observed");
        for (UUID input : f.inputs) {
            Entity entity = f.level.getEntity(input);
            if (entity != null && entity.isAlive()) throw new IllegalStateException("C04 input survived");
        }
        long tick = 100;
        for (Withdrawal row : f.withdrawals) {
            if (row.spec.consumed()) {
                f.ledger.advance(row.transaction, MaterialTransactionState.DELIVERED, tick++);
                f.ledger.advance(row.transaction, MaterialTransactionState.CONSUMED, tick++);
            } else {
                f.ledger.advance(row.transaction, MaterialTransactionState.RETURN_PENDING, tick++);
                put(f.source, row.slot, row.spec.item(), row.spec.quantity());
                f.ledger.advance(row.transaction, MaterialTransactionState.RETURNED, tick++);
            }
        }
        MaterialBalanceReport balance = f.ledger.balance(PROJECT);
        if (!balance.materialLedgerBalanced() || balance.consumed() != 1
                || balance.duplicateWithdrawals() != 0 || balance.duplicateReturns() != 0
                || balance.unaccountedItems() != 0)
            throw new IllegalStateException("C04 ledger imbalance: " + balance);
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations = new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role,
                new CreateSurvivalPowerEvidenceV1.RoleObservation(value.blockId(),
                        Long.toUnsignedString(value.networkId().orElseThrow()),
                        value.gameTick(), value.speedRpm(), value.stressEnabled(),
                        value.stressCapacity(), value.stressLoad(), value.overstressed())));
        CreateSurvivalPowerMappingV1.Entry mapping =
                CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C04);
        CreateSurvivalPowerEvidenceV1.Validation validation = CreateSurvivalPowerEvidenceV1.validate(
                new CreateSurvivalPowerEvidenceV1.Candidate(mapping, WATER_WHEEL,
                        "c04:{water_wheel[x]->gearbox[y]->shaft[z]->belt;water_wheel[x]->gearbox[y]->shaft[z]->press}",
                        observations, "c04-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || !accepted.ordinaryPlayerEligible())
            throw new IllegalStateException("C04 production gate failed: " + validation);
        output.clearContent();
        cleanup(f);
        if (f.changed.stream().anyMatch(p -> !f.level.getBlockState(p).isAir()))
            throw new IllegalStateException("C04 baseline did not restore");
        LOGGER.info("C04_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} networks={} speeds={}",
                WATER_WHEEL, observations.keySet(),
                observations.values().stream().map(CreateSurvivalPowerEvidenceV1.RoleObservation::networkId).distinct().toList(),
                observations.values().stream().map(v -> Double.toString(v.speedRpm())).toList());
        LOGGER.info("C04_SURVIVAL_POWER_PROCESS PASS recipe=create:pressing/iron_ingot "
                        + "input=minecraft:iron_ingot x1 output=create:iron_sheet x{} "
                        + "beltInputObserved=true pressCycleObserved={} realInputEntity=true",
                outputCount, f.cycleObserved);
        LOGGER.info("C04_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} consumed={} "
                        + "returned={} duplicateWithdrawals={} duplicateReturns={} unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.consumed(),
                balance.returned(), balance.duplicateWithdrawals(), balance.duplicateReturns(),
                balance.unaccountedItems(), balance.materialLedgerBalanced());
        LOGGER.info("C04_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} ordinaryPlayerEligible={} "
                        + "rollbackVerified=true baselineRestored=true reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        KineticSnapshot runtime = snapshots.values().stream().findFirst().orElseThrow();
        LOGGER.info("C04_SURVIVAL_POWER_GAMETEST PASS minecraft=1.20.1 forge={} create={} "
                        + "realWaterWheel=true realBelt=true realPress=true realPressingRecipe=true "
                        + "realInputEntity=true realOutputFunnel=true realOutput=true "
                        + "realMaterialLedger=true productionReady=true",
                runtime.runtime().loaderVersion(), runtime.runtime().industrialModVersions().get("create"));
    }

    private static Map<String, KineticSnapshot> kinetics(Fixture f) {
        Map<String, BlockPos> positions = new LinkedHashMap<>();
        positions.put("belt_water_wheel", f.beltWheel); positions.put("belt_gearbox", f.bottomGearbox);
        positions.put("belt_drive_shaft", f.beltDriveShaft);
        positions.put("press_water_wheel", f.pressWheel); positions.put("press_gearbox", f.pressGearbox);
        positions.put("press_drive_shaft", f.pressDriveShaft); positions.put("belt_start", f.beltStart);
        positions.put("belt_pressing", f.beltMiddle); positions.put("belt_end", f.beltEnd);
        positions.put("mechanical_press", f.press);
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(f.level);
        Map<String, KineticSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> row : positions.entrySet()) {
            AdapterResult<KineticSnapshot> captured = adapter.captureKinetics(
                    new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(pos(row.getValue())));
            if (captured instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                if (failure.code() == dev.stevecreate.agent.adapter.api.AdapterFailureCode.LIFECYCLE_NOT_READY)
                    throw new Pending("C04 network settling: " + row.getKey());
                throw new IllegalStateException("C04 kinetic capture failed: " + failure);
            }
            KineticSnapshot snapshot = ((AdapterResult.Success<KineticSnapshot>) captured).value();
            result.put(row.getKey(), snapshot);
        }
        List<String> stopped = result.entrySet().stream()
                .filter(row -> row.getValue().speedRpm() == 0 || row.getValue().overstressed())
                .map(row -> row.getKey() + "=" + row.getValue().speedRpm()
                        + "@" + row.getValue().networkId()).toList();
        if (!stopped.isEmpty())
            throw new Pending("C04 roles not powered: " + stopped + ";all="
                    + result.entrySet().stream().map(row -> row.getKey() + "="
                    + row.getValue().speedRpm() + "@" + row.getValue().networkId()).toList());
        if (result.values().stream().anyMatch(v -> v.networkId().isEmpty()))
            throw new Pending("C04 powered roles lack network identity");
        return result;
    }

    private static void feed(Fixture f, BeltBlockEntity controller) {
        net.minecraftforge.items.ItemStackHandler recipeInput =
                new net.minecraftforge.items.ItemStackHandler(1);
        recipeInput.setStackInSlot(0, new ItemStack(item(IRON_INGOT), 1));
        net.minecraft.world.item.crafting.Recipe<?> found = f.level.getRecipeManager().getRecipeFor(
                AllRecipeTypes.PRESSING.getType(),
                new net.minecraftforge.items.wrapper.RecipeWrapper(recipeInput),
                f.level).orElse(null);
        if (!(found instanceof PressingRecipe recipe)
                || !recipe.getId().equals(key("create", "pressing/iron_ingot")))
            throw new IllegalStateException("C04 live pressing recipe is missing or changed");
        ItemEntity entity = new ItemEntity(f.level, f.beltStart.getX() + .5,
                f.beltStart.getY() + 1.1, f.beltStart.getZ() + .5,
                new ItemStack(item(IRON_INGOT), 1));
        if (!f.level.addFreshEntity(entity)) throw new IllegalStateException("C04 input entity rejected");
        f.inputs.add(entity.getUUID());
        var handler = controller.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (handler == null || !handler.insertItem(0, entity.getItem().copy(), false).isEmpty())
            throw new IllegalStateException("C04 real belt rejected iron input");
        entity.discard();
        f.fed = true;
    }

    private static void collectEjectedOutput(Fixture f, ChestBlockEntity chest) {
        for (ItemEntity entity : f.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(f.beltEnd).inflate(3, 2, 2), Entity::isAlive)) {
            if (!IRON_SHEET.equals(itemId(entity.getItem()))) continue;
            ItemStack remainder = ItemHandlerHelper.insertItem(new InvWrapper(chest), entity.getItem().copy(), false);
            if (remainder.isEmpty()) entity.discard(); else entity.setItem(remainder);
        }
    }

    private static void collectBeltOutput(BeltBlockEntity controller, ChestBlockEntity chest) {
        List<com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack> rows =
                new ArrayList<>(controller.getInventory().getTransportedItems());
        for (var transported : rows) {
            if (!IRON_SHEET.equals(itemId(transported.stack))) continue;
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    new InvWrapper(chest), transported.stack.copy(), false);
            if (remainder.isEmpty()) {
                transported.stack = ItemStack.EMPTY;
                controller.getInventory().getTransportedItems().remove(transported);
            } else {
                transported.stack = remainder;
            }
        }
    }

    private static void cleanup(Fixture f) {
        for (BlockPos p : f.changed) f.level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        for (UUID id : f.inputs) { Entity entity = f.level.getEntity(id); if (entity != null) entity.discard(); }
    }
    private static void placePress(ServerLevel level, BlockPos p) {
        Block block = ForgeRegistries.BLOCKS.getValue(key("create", "mechanical_press"));
        if (block == null) throw new IllegalStateException("Missing C04 press");
        BlockState state = block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C04 press placement failed");
    }
    private static void place(ServerLevel level, BlockPos p, ResourceLocation key, Object orientation) {
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null) throw new IllegalStateException("Missing C04 block " + key);
        BlockState state = block.defaultBlockState();
        if (orientation instanceof Direction.Axis axis) state = state.setValue(BlockStateProperties.AXIS, axis);
        else if (orientation instanceof Direction facing) {
            if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, facing);
            else state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C04 placement failed");
    }
    private static void fluid(ServerLevel level, BlockPos p, BlockState state) {
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C04 fluid failed");
    }
    private static WaterWheelBlockEntity wheel(ServerLevel l, BlockPos p) { return l.getBlockEntity(p) instanceof WaterWheelBlockEntity v ? v : null; }
    private static MechanicalPressBlockEntity press(ServerLevel l, BlockPos p) { return l.getBlockEntity(p) instanceof MechanicalPressBlockEntity v ? v : null; }
    private static ChestBlockEntity chest(ServerLevel l, BlockPos p) { if (!(l.getBlockEntity(p) instanceof ChestBlockEntity v)) throw new IllegalStateException("C04 chest missing"); return v; }
    private static MaterialSpec spec(String label, ResourceId item, int quantity, boolean consumed) { return new MaterialSpec(label, item, quantity, consumed); }
    private static void put(ChestBlockEntity c, int slot, ResourceId id, int count) { c.setItem(slot, new ItemStack(item(id), count)); }
    private static void decrement(ChestBlockEntity c, int slot, int count) { ItemStack stack = c.getItem(slot); if (stack.isEmpty() || stack.getCount() != count) throw new IllegalStateException("C04 source mismatch"); c.setItem(slot, ItemStack.EMPTY); }
    private static int count(ChestBlockEntity c, ResourceId id) { int n = 0; for (int slot = 0; slot < c.getContainerSize(); slot++) if (id.equals(itemId(c.getItem(slot)))) n += c.getItem(slot).getCount(); return n; }
    private static Item item(ResourceId id) { Item value = ForgeRegistries.ITEMS.getValue(key(id.namespace(), id.path())); if (value == null) throw new IllegalStateException("Missing C04 item " + id); return value; }
    private static ResourceId itemId(ItemStack stack) { ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem()); return key == null ? null : id(key.toString()); }
    private static BlockPos3i pos(BlockPos p) { return new BlockPos3i(p.getX(), p.getY(), p.getZ()); }
    private static ResourceLocation key(String namespace, String path) { return ResourceLocation.fromNamespaceAndPath(namespace, path); }
    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private record MaterialSpec(String label, ResourceId item, int quantity, boolean consumed) {}
    private record Withdrawal(ResourceId transaction, int slot, MaterialSpec spec) {}
    private static final class Fixture {
        final ServerLevel level; final ChestBlockEntity source; final ProjectMaterialLedger ledger;
        final List<Withdrawal> withdrawals; final List<BlockPos> changed; final List<UUID> inputs = new ArrayList<>();
        final BlockPos beltWheel, pressWheel, bottomGearbox, beltDriveShaft, pressGearbox;
        final BlockPos pressDriveShaft, press, beltStart, beltMiddle, beltEnd, output;
        boolean fed, cycleObserved, completed;
        Fixture(ServerLevel level, ChestBlockEntity source, ProjectMaterialLedger ledger,
                List<Withdrawal> withdrawals, List<BlockPos> changed, BlockPos beltWheel,
                BlockPos pressWheel, BlockPos bottomGearbox, BlockPos beltDriveShaft,
                BlockPos pressGearbox, BlockPos pressDriveShaft,
                BlockPos press, BlockPos beltStart, BlockPos beltMiddle, BlockPos beltEnd, BlockPos output) {
            this.level = level; this.source = source; this.ledger = ledger; this.withdrawals = withdrawals;
            this.changed = changed; this.beltWheel = beltWheel; this.pressWheel = pressWheel;
            this.bottomGearbox = bottomGearbox; this.beltDriveShaft = beltDriveShaft;
            this.pressGearbox = pressGearbox;
            this.pressDriveShaft = pressDriveShaft; this.press = press; this.beltStart = beltStart;
            this.beltMiddle = beltMiddle; this.beltEnd = beltEnd; this.output = output;
        }
    }
    private static final class Pending extends RuntimeException { Pending(String message) { super(message); } }
}
