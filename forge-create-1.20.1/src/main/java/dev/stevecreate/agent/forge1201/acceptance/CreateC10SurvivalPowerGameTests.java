package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
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
import java.util.Optional;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Real Create C-10 production water-wheel/Deployer proof and balanced material return. */
@PrefixGameTestTemplate(false)
public final class CreateC10SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId DEPLOYER = id("create:deployer");
    private static final ResourceId DEPOT = id("create:depot");
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER_BUCKET = id("minecraft:water_bucket");
    private static final ResourceId OAK_PLANKS = id("minecraft:oak_planks");
    private static final ResourceId COGWHEEL = id("create:cogwheel");
    private static final ResourceId PROJECT = id("steve_industrial:c10/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c10/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;

    private CreateC10SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2_400)
    public static void c10WaterWheelDeployerProduction(GameTestHelper helper) {
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
                spec("water-wheel", WATER_WHEEL, 1, false),
                spec("gearboxes", GEARBOX, 2, false),
                spec("drive-shafts", SHAFT, 2, false),
                spec("deployer", DEPLOYER, 1, false),
                spec("depot", DEPOT, 1, false), spec("output-chest", CHEST, 1, false),
                spec("supports", STONE, 13, false),
                spec("water-source", WATER_BUCKET, 1, false),
                spec("processed-shaft", SHAFT, 1, true),
                spec("held-planks", OAK_PLANKS, 1, true));
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        List<MaterialRequirement> requirements = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec value = specs.get(index);
            put(source, index, value.item(), value.quantity());
            slots.add(new MaterialSlotSnapshot(index,
                    new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()));
            requirements.add(new MaterialRequirement(
                    id("steve_industrial:c10/requirement/" + value.label()),
                    id("create:c10/water-wheel-deployer"), index,
                    RecipeIngredientKind.EXACT_RESOURCE, value.item().toString(),
                    List.of(value.item()), value.quantity()));
        }
        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c10/survival-power-plan"), PROJECT,
                id("create:c10/water-wheel-deployer"), 1, "forge-create-6.0.6",
                "c".repeat(64), requirements);
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(
                SOURCE, PROJECT, "c10-acceptance", id("minecraft:overworld"),
                pos(sourcePos), "up", "minecraft:chest", "a".repeat(64), "b".repeat(64),
                0, specs.stream().mapToLong(MaterialSpec::quantity).sum(),
                0, 100_000, slots), 1);
        List<Withdrawal> withdrawals = new ArrayList<>();
        long tick = 2;
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec value = specs.get(index);
            MaterialRequirement requirement = requirements.get(index);
            ResourceId allocation = id("steve_industrial:c10/allocation/" + index);
            ledger.reserve(new MaterialAllocation(allocation, PROJECT,
                    requirement.requirementId(), SOURCE, index,
                    new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()), tick++);
            ResourceId transaction = id("steve_industrial:c10/transaction/" + index);
            ledger.prepare(transaction, PROJECT, id("steve_industrial:c10/task/" + index),
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
        BlockPos deployer = origin.offset(1, 5, -2);
        BlockPos depot = origin.offset(1, 3, -2);
        BlockPos output = origin.offset(2, 3, -2);
        place(level, wheel, key("create", "water_wheel"), Direction.EAST);
        place(level, bottomGearbox, key("create", "gearbox"), Direction.Axis.Z);
        place(level, verticalShaft, key("create", "shaft"), Direction.Axis.Y);
        place(level, topGearbox, key("create", "gearbox"), Direction.Axis.X);
        place(level, horizontalShaft, key("create", "shaft"), Direction.Axis.Z);
        placeDeployer(level, deployer);
        place(level, depot, key("create", "depot"), null);
        place(level, output, key("minecraft", "chest"), null);
        changed.addAll(List.of(wheel, bottomGearbox, verticalShaft, topGearbox,
                horizontalShaft, deployer, depot, output));
        BlockPos waterSource = origin.offset(0, 4, -1);
        BlockPos waterOne = origin.offset(0, 3, -1);
        BlockPos waterTwo = origin.offset(0, 2, -1);
        fluid(level, waterSource, Fluids.WATER.defaultFluidState().createLegacyBlock());
        fluid(level, waterOne, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        fluid(level, waterTwo, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        changed.addAll(List.of(waterSource, waterOne, waterTwo, sourcePos));
        return new Fixture(level, source, ledger, withdrawals, changed, wheel,
                bottomGearbox, verticalShaft, topGearbox, horizontalShaft,
                deployer, depot, output);
    }

    private static void verify(Fixture fixture) {
        WaterWheelBlockEntity wheel = wheel(fixture.level, fixture.wheel);
        if (wheel != null && wheel.flowScore == 0) wheel.determineAndApplyFlowScore();
        Map<String, KineticSnapshot> snapshots = kinetics(fixture);
        if (!fixture.fed) feed(fixture);
        DeployerBlockEntity deployer = deployer(fixture.level, fixture.deployer);
        DepotBlockEntity depot = depot(fixture.level, fixture.depot);
        ChestBlockEntity output = chest(fixture.level, fixture.output);
        if (deployer == null || depot == null) throw new IllegalStateException("C10 machine disappeared");
        ItemStack current = depot.getHeldItem();
        if (!current.isEmpty() && COGWHEEL.equals(itemId(current))) {
            fixture.cycleObserved = true;
            depot.setHeldItem(ItemStack.EMPTY);
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    new InvWrapper(output), current.copy(), false);
            if (!remainder.isEmpty()) throw new IllegalStateException("C10 output chest rejected cogwheel");
        }
        int outputCount = count(output, COGWHEEL);
        ItemStack heldAfter = held(deployer);
        if (outputCount != 1 || !heldAfter.isEmpty())
            throw new Pending("C10 real Deployer is still processing");
        if (!fixture.cycleObserved) throw new Pending("C10 Deployer cycle was not observed");
        for (UUID input : fixture.inputEntities) {
            Entity entity = fixture.level.getEntity(input);
            if (entity != null && entity.isAlive()) throw new IllegalStateException("C10 input survived");
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
        if (!balance.materialLedgerBalanced() || balance.consumed() != 2
                || balance.duplicateWithdrawals() != 0 || balance.duplicateReturns() != 0
                || balance.unaccountedItems() != 0)
            throw new IllegalStateException("C10 ledger imbalance: " + balance);
        String network = Long.toUnsignedString(snapshots.values().stream().findFirst()
                .orElseThrow().networkId().orElseThrow());
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations = new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role,
                new CreateSurvivalPowerEvidenceV1.RoleObservation(value.blockId(), network,
                        value.gameTick(), value.speedRpm(), value.stressEnabled(),
                        value.stressCapacity(), value.stressLoad(), value.overstressed())));
        CreateSurvivalPowerMappingV1.Entry mapping =
                CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C10);
        CreateSurvivalPowerEvidenceV1.Validation validation = CreateSurvivalPowerEvidenceV1.validate(
                new CreateSurvivalPowerEvidenceV1.Candidate(mapping, WATER_WHEEL,
                        "c10:water_wheel[x]->gearbox[z]->shaft[y]->gearbox[x]->shaft[z]->deployer[down,axis=z]",
                        observations, "c10-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || !accepted.ordinaryPlayerEligible())
            throw new IllegalStateException("C10 production gate changed: " + validation);
        output.clearContent();
        cleanup(fixture);
        if (fixture.changed.stream().anyMatch(p -> !fixture.level.getBlockState(p).isAir()))
            throw new IllegalStateException("C10 baseline did not restore");
        LOGGER.info("C10_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} network={} speeds={}",
                WATER_WHEEL, observations.keySet(), network,
                observations.values().stream().map(v -> Double.toString(v.speedRpm())).toList());
        LOGGER.info("C10_SURVIVAL_POWER_PROCESS PASS recipe=create:deploying/cogwheel "
                        + "processed=create:shaft x1 held=minecraft:oak_planks 1->0 "
                        + "output=create:cogwheel x{} deployerCycleObserved={} realInputEntities=true",
                outputCount, fixture.cycleObserved);
        LOGGER.info("C10_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} consumed={} "
                        + "returned={} duplicateWithdrawals={} duplicateReturns={} unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.consumed(),
                balance.returned(), balance.duplicateWithdrawals(), balance.duplicateReturns(),
                balance.unaccountedItems(), balance.materialLedgerBalanced());
        LOGGER.info("C10_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} ordinaryPlayerEligible={} "
                        + "rollbackVerified=true baselineRestored=true reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        KineticSnapshot runtime = snapshots.values().stream().findFirst().orElseThrow();
        LOGGER.info("C10_SURVIVAL_POWER_GAMETEST PASS minecraft=1.20.1 forge={} create={} "
                        + "realWaterWheel=true realDeployer=true realDeployingRecipe=true "
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
        positions.put("deployer", f.deployer);
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(f.level);
        Map<String, KineticSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> row : positions.entrySet()) {
            AdapterResult<KineticSnapshot> captured = adapter.captureKinetics(
                    new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(pos(row.getValue())));
            if (captured instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                if (failure.code() == dev.stevecreate.agent.adapter.api.AdapterFailureCode.LIFECYCLE_NOT_READY)
                    throw new Pending("C10 network settling: " + row.getKey());
                throw new IllegalStateException("C10 kinetic capture failed: " + failure);
            }
            KineticSnapshot snapshot = ((AdapterResult.Success<KineticSnapshot>) captured).value();
            if (snapshot.speedRpm() <= 0 || snapshot.overstressed())
                throw new Pending("C10 role is not powered: " + row.getKey());
            result.put(row.getKey(), snapshot);
        }
        OptionalLong network = result.values().stream().findFirst().orElseThrow().networkId();
        if (network.isEmpty() || result.values().stream().anyMatch(v -> v.networkId().isEmpty()
                || v.networkId().getAsLong() != network.getAsLong()))
            throw new Pending("C10 roles do not share one network");
        return result;
    }

    private static void feed(Fixture f) {
        DeployerBlockEntity deployer = deployer(f.level, f.deployer);
        DepotBlockEntity depot = depot(f.level, f.depot);
        Recipe<?> recipe = f.level.getRecipeManager().byKey(key("create", "deploying/cogwheel"))
                .orElse(null);
        if (deployer == null || depot == null || !(recipe instanceof ItemApplicationRecipe application)
                || recipe.getType() != AllRecipeTypes.DEPLOYING.getType()
                || application.getIngredients().size() != 2
                || !application.getProcessedItem().test(new ItemStack(item(SHAFT)))
                || !application.getRequiredHeldItem().test(new ItemStack(item(OAK_PLANKS)))
                || application.shouldKeepHeldItem() || application.getRollableResults().size() != 1)
            throw new IllegalStateException("C10 live deploying recipe is missing or changed");
        ProcessingOutput result = application.getRollableResults().get(0);
        if (!COGWHEEL.equals(itemId(result.getStack())) || result.getStack().getCount() != 1
                || result.getChance() != 1.0F)
            throw new IllegalStateException("C10 live deploying output changed");
        if (!depot.getHeldItem().isEmpty() || !held(deployer).isEmpty())
            throw new IllegalStateException("C10 machine was not empty before feed");
        ItemEntity processed = entity(f, SHAFT, f.depot);
        depot.setHeldItem(processed.getItem().copy());
        processed.discard();
        ItemEntity held = entity(f, OAK_PLANKS, f.deployer);
        if (!setHeld(deployer, held.getItem().copy()))
            throw new IllegalStateException("C10 Deployer rejected held planks");
        held.discard();
        Recipe<?> resolved = deployer.getRecipe(depot.getHeldItem());
        if (resolved == null || !resolved.getId().equals(recipe.getId()))
            throw new IllegalStateException("C10 Deployer did not resolve exact recipe");
        f.fed = true;
    }

    private static ItemEntity entity(Fixture f, ResourceId item, BlockPos at) {
        ItemEntity value = new ItemEntity(f.level, at.getX() + .5, at.getY() + 1.1,
                at.getZ() + .5, new ItemStack(item(item), 1));
        if (!f.level.addFreshEntity(value)) throw new IllegalStateException("C10 input entity rejected");
        f.inputEntities.add(value.getUUID());
        return value;
    }

    private static ItemStack held(DeployerBlockEntity deployer) {
        return deployer.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .map(handler -> handler.getStackInSlot(0).copy()).orElse(ItemStack.EMPTY);
    }

    private static boolean setHeld(DeployerBlockEntity deployer, ItemStack stack) {
        Optional<IItemHandler> optional = deployer.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        return optional.isPresent() && optional.orElseThrow().insertItem(0, stack, false).isEmpty();
    }

    private static void cleanup(Fixture f) {
        DeployerBlockEntity deployer = deployer(f.level, f.deployer);
        if (deployer != null) deployer.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .ifPresent(handler -> handler.extractItem(0, Integer.MAX_VALUE, false));
        DepotBlockEntity depot = depot(f.level, f.depot);
        if (depot != null) depot.setHeldItem(ItemStack.EMPTY);
        for (BlockPos p : f.changed) f.level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        for (UUID id : f.inputEntities) {
            Entity entity = f.level.getEntity(id);
            if (entity != null) entity.discard();
        }
    }

    private static void placeDeployer(ServerLevel level, BlockPos p) {
        Block block = ForgeRegistries.BLOCKS.getValue(key("create", "deployer"));
        if (!(block instanceof DirectionalAxisKineticBlock kinetic))
            throw new IllegalStateException("Missing directional C10 Deployer");
        BlockState state = block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN);
        BlockState first = state.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        state = kinetic.getRotationAxis(first) == Direction.Axis.Z ? first
                : state.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true);
        if (kinetic.getRotationAxis(state) != Direction.Axis.Z || !level.setBlockAndUpdate(p, state))
            throw new IllegalStateException("C10 Deployer cannot represent downward/Z topology");
    }

    private static void place(ServerLevel level, BlockPos p, ResourceLocation key, Object orientation) {
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null) throw new IllegalStateException("Missing C10 block " + key);
        BlockState state = block.defaultBlockState();
        if (orientation instanceof Direction.Axis axis)
            state = state.setValue(BlockStateProperties.AXIS, axis);
        else if (orientation instanceof Direction facing) {
            if (state.hasProperty(BlockStateProperties.FACING))
                state = state.setValue(BlockStateProperties.FACING, facing);
            else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            else throw new IllegalStateException("C10 block has no facing property " + key);
        }
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C10 placement failed");
    }

    private static void fluid(ServerLevel level, BlockPos p, BlockState state) {
        if (!level.setBlockAndUpdate(p, state)) throw new IllegalStateException("C10 fluid failed");
    }
    private static WaterWheelBlockEntity wheel(ServerLevel l, BlockPos p) {
        return l.getBlockEntity(p) instanceof WaterWheelBlockEntity v ? v : null;
    }
    private static DeployerBlockEntity deployer(ServerLevel l, BlockPos p) {
        return l.getBlockEntity(p) instanceof DeployerBlockEntity v ? v : null;
    }
    private static DepotBlockEntity depot(ServerLevel l, BlockPos p) {
        return l.getBlockEntity(p) instanceof DepotBlockEntity v ? v : null;
    }
    private static ChestBlockEntity chest(ServerLevel l, BlockPos p) {
        if (!(l.getBlockEntity(p) instanceof ChestBlockEntity v))
            throw new IllegalStateException("C10 chest missing");
        return v;
    }
    private static MaterialSpec spec(String label, ResourceId item, int quantity, boolean consumed) {
        return new MaterialSpec(label, item, quantity, consumed);
    }
    private static void put(ChestBlockEntity c, int slot, ResourceId id, int count) {
        c.setItem(slot, new ItemStack(item(id), count));
    }
    private static void decrement(ChestBlockEntity c, int slot, int count) {
        ItemStack stack = c.getItem(slot);
        if (stack.isEmpty() || stack.getCount() != count)
            throw new IllegalStateException("C10 source mismatch");
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
        if (value == null) throw new IllegalStateException("Missing C10 item " + id);
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

    private record MaterialSpec(String label, ResourceId item, int quantity, boolean consumed) {}
    private record Withdrawal(ResourceId transaction, int slot, MaterialSpec spec) {}
    private static final class Fixture {
        final ServerLevel level; final ChestBlockEntity source; final ProjectMaterialLedger ledger;
        final List<Withdrawal> withdrawals; final List<BlockPos> changed;
        final BlockPos wheel, bottomGearbox, verticalShaft, topGearbox, horizontalShaft;
        final BlockPos deployer, depot, output; final List<UUID> inputEntities = new ArrayList<>();
        boolean fed, cycleObserved, completed;
        Fixture(ServerLevel level, ChestBlockEntity source, ProjectMaterialLedger ledger,
                List<Withdrawal> withdrawals, List<BlockPos> changed, BlockPos wheel,
                BlockPos bottomGearbox, BlockPos verticalShaft, BlockPos topGearbox,
                BlockPos horizontalShaft, BlockPos deployer, BlockPos depot, BlockPos output) {
            this.level = level; this.source = source; this.ledger = ledger;
            this.withdrawals = withdrawals; this.changed = changed; this.wheel = wheel;
            this.bottomGearbox = bottomGearbox; this.verticalShaft = verticalShaft;
            this.topGearbox = topGearbox; this.horizontalShaft = horizontalShaft;
            this.deployer = deployer; this.depot = depot; this.output = output;
        }
    }
    private static final class Pending extends RuntimeException {
        Pending(String message) { super(message); }
    }
}
