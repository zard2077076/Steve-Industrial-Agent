package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.saw.CuttingRecipe;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;

/**
 * Isolated real-Create C-07 candidate probe.
 *
 * <p>This is intentionally an unlisted candidate. It proves a water-wheel
 * network, the live upward saw and a real item-only cutting cycle while the
 * normal player mapping remains REVIEW_REQUIRED.</p>
 */
@PrefixGameTestTemplate(false)
public final class CreateC07SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId SAW = id("create:mechanical_saw");
    private static final ResourceId DEPOT = id("create:depot");
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId ANDESITE_ALLOY = id("create:andesite_alloy");
    private static final ResourceId PROJECT = id("steve_industrial:c07/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c07/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;
    private static final String BLOCK_HASH = "a".repeat(64);
    private static final String INVENTORY_HASH = "b".repeat(64);

    private CreateC07SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void c07WaterWheelMechanicalSawSurvivalCandidate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fixture fixture = buildFixture(level, helper.absolutePos(new BlockPos(2, 2, 2)));
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
        BlockPos sourceChest = origin.offset(10, 0, 0);
        place(level, sourceChest, key("minecraft", "chest"), null);
        ChestBlockEntity chest = chest(level, sourceChest);
        List<MaterialSpec> specs = List.of(
                new MaterialSpec(WATER_WHEEL, 1), new MaterialSpec(GEARBOX, 2),
                new MaterialSpec(SHAFT, 2), new MaterialSpec(SAW, 1),
                new MaterialSpec(DEPOT, 1), new MaterialSpec(CHEST, 1),
                new MaterialSpec(ANDESITE_ALLOY, 1));
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec spec = specs.get(index);
            put(chest, index, spec.item(), spec.quantity());
            slots.add(new MaterialSlotSnapshot(index,
                    new MaterialIdentity(spec.item(), EMPTY_PAYLOAD), spec.quantity()));
        }
        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c07/survival-power-plan"), PROJECT,
                id("create:c07/water-wheel-mechanical-saw"), 1, "forge-create-6.0.6",
                "c".repeat(64), specs.stream().map(spec -> new MaterialRequirement(
                        id("steve_industrial:c07/requirement/" + spec.item().path()),
                        id("create:c07/water-wheel-mechanical-saw"), 0,
                        RecipeIngredientKind.EXACT_RESOURCE, spec.item().toString(),
                        List.of(spec.item()), spec.quantity())).toList());
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(
                SOURCE, PROJECT, "c07-acceptance", id("minecraft:overworld"),
                blockPos(sourceChest), "up", "minecraft:chest", BLOCK_HASH, INVENTORY_HASH,
                0, specs.stream().mapToLong(MaterialSpec::quantity).sum(), 0, 100_000, slots), 1);
        List<MaterialWithdrawal> withdrawals = new ArrayList<>();
        long tick = 2;
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec spec = specs.get(index);
            MaterialRequirement requirement = plan.requirements().stream()
                    .filter(value -> value.acceptedResources().contains(spec.item())).findFirst().orElseThrow();
            ResourceId allocationId = id("steve_industrial:c07/allocation/" + index);
            ledger.reserve(new MaterialAllocation(allocationId, PROJECT, requirement.requirementId(),
                    SOURCE, index, new MaterialIdentity(spec.item(), EMPTY_PAYLOAD), spec.quantity()), tick++);
            ResourceId transactionId = id("steve_industrial:c07/transaction/" + index);
            ledger.prepare(transactionId, PROJECT, id("steve_industrial:c07/task/" + index),
                    allocationId, MaterialExecutorKind.DIRECT, spec.quantity(), tick++);
            decrement(chest, index, spec.quantity());
            ledger.advance(transactionId, MaterialTransactionState.WITHDRAWN, tick++);
            withdrawals.add(new MaterialWithdrawal(transactionId, index, spec));
        }

        List<BlockPos> changed = new ArrayList<>();
        List<BlockPos> supports = List.of(
                origin.offset(0, 1, -1), origin.offset(-1, 2, -1), origin.offset(1, 2, -1),
                origin.offset(0, 2, -2), origin.offset(0, 2, 0), origin.offset(-1, 4, -1),
                origin.offset(1, 4, -1), origin.offset(0, 4, -2), origin.offset(0, 4, 0),
                origin.offset(-1, 3, -1), origin.offset(1, 3, -1), origin.offset(0, 3, -2),
                origin.offset(0, 3, -3), origin.offset(1, 3, -3));
        for (BlockPos position : supports) {
            place(level, position, key("minecraft", "stone"), null);
            changed.add(position);
        }
        BlockPos waterWheel = origin.offset(0, 3, 0);
        BlockPos gearbox = origin.offset(1, 3, 0);
        BlockPos verticalShaft = origin.offset(1, 4, 0);
        BlockPos gearboxTop = origin.offset(1, 5, 0);
        BlockPos horizontalShaft = origin.offset(1, 5, -1);
        BlockPos sawPosition = origin.offset(1, 5, -2);
        BlockPos depotPosition = origin.offset(1, 4, -2);
        BlockPos outputChest = origin.offset(2, 5, -2);
        place(level, waterWheel, key("create", "water_wheel"), Direction.EAST);
        place(level, gearbox, key("create", "gearbox"), Direction.Axis.Z);
        place(level, verticalShaft, key("create", "shaft"), Direction.Axis.Y);
        place(level, gearboxTop, key("create", "gearbox"), Direction.Axis.X);
        place(level, horizontalShaft, key("create", "shaft"), Direction.Axis.Z);
        placeSaw(level, sawPosition);
        place(level, depotPosition, key("create", "depot"), null);
        place(level, outputChest, key("minecraft", "chest"), null);
        changed.addAll(List.of(waterWheel, gearbox, verticalShaft, gearboxTop,
                horizontalShaft, sawPosition, depotPosition, outputChest));
        BlockPos waterSource = origin.offset(0, 4, -1);
        BlockPos waterFlowOne = origin.offset(0, 3, -1);
        BlockPos waterFlowTwo = origin.offset(0, 2, -1);
        placeFluid(level, waterSource, net.minecraft.world.level.material.Fluids.WATER.defaultFluidState().createLegacyBlock());
        placeFluid(level, waterFlowOne, net.minecraft.world.level.material.Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        placeFluid(level, waterFlowTwo, net.minecraft.world.level.material.Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        changed.addAll(List.of(waterSource, waterFlowOne, waterFlowTwo, sourceChest));
        return new Fixture(level, origin, sourceChest, chest, ledger, PROJECT, changed, withdrawals,
                waterWheel, gearbox, verticalShaft, gearboxTop, horizontalShaft,
                sawPosition, depotPosition, outputChest, false, false, false);
    }

    private static void verifyAndCleanup(Fixture fixture) {
        WaterWheelBlockEntity waterWheel = fixture.waterWheel();
        if (waterWheel != null && waterWheel.flowScore == 0) waterWheel.determineAndApplyFlowScore();
        Map<String, KineticSnapshot> snapshots = captureKinetics(fixture);
        if (!fixture.fed) feedRealSaw(fixture);
        SawBlockEntity saw = saw(fixture.level, fixture.sawPosition);
        ChestBlockEntity output = chest(fixture.level, fixture.outputChest);
        if (saw == null || output == null) throw new IllegalStateException("C07 saw or output chest disappeared");
        if (saw.inventory.recipeDuration > 0 || saw.inventory.remainingTime >= 0 || saw.inventory.appliedRecipe) {
            fixture.sawCycleObserved = true;
        }
        transferOutputs(fixture, output);
        int outputCount = count(output, SHAFT);
        if (fixture.inputEntity != null && entityPresent(fixture.level, fixture.inputEntity)
                || !saw.inventory.isEmpty() || outputCount < 1) {
            throw new Pending("C07 real saw is still processing input or output");
        }
        if (!fixture.sawCycleObserved) throw new Pending("C07 live cutting cycle has not been observed");
        long settlementTick = 100;
        for (MaterialWithdrawal withdrawal : fixture.withdrawals) {
            fixture.ledger.advance(withdrawal.transactionId, MaterialTransactionState.RETURN_PENDING, settlementTick++);
            put(fixture.sourceChestEntity, withdrawal.slot, withdrawal.spec.item(), withdrawal.spec.quantity());
            fixture.ledger.advance(withdrawal.transactionId, MaterialTransactionState.RETURNED, settlementTick++);
        }
        MaterialBalanceReport balance = fixture.ledger.balance(fixture.project);
        if (!balance.materialLedgerBalanced() || balance.duplicateWithdrawals() != 0
                || balance.duplicateReturns() != 0 || balance.unaccountedItems() != 0) {
            throw new IllegalStateException("C07 candidate material ledger is not balanced: " + balance);
        }
        OptionalLong firstNetwork = snapshots.values().stream().findFirst().orElseThrow().networkId();
        String network = firstNetwork.isPresent()
                ? Long.toUnsignedString(firstNetwork.getAsLong()) : "none";
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations = new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role, new CreateSurvivalPowerEvidenceV1.RoleObservation(
                value.blockId(), network, value.gameTick(), value.speedRpm(), value.stressEnabled(),
                value.stressCapacity(), value.stressLoad(), value.overstressed())));
        CreateSurvivalPowerMappingV1.Entry mapping = CreateSurvivalPowerMappingV1.candidate(
                CreateCapabilityContractV1.C07, WATER_WHEEL,
                Map.of("water_wheel", WATER_WHEEL, "gearbox_bottom", GEARBOX,
                        "vertical_shaft", SHAFT, "gearbox_top", GEARBOX,
                        "horizontal_shaft", SHAFT, "mechanical_saw", SAW),
                "c07-water-wheel-mechanical-saw");
        CreateSurvivalPowerEvidenceV1.Validation validation = CreateSurvivalPowerEvidenceV1.validate(
                new CreateSurvivalPowerEvidenceV1.Candidate(mapping, WATER_WHEEL,
                        "c07:water_wheel[x]->gearbox[z]->shaft[y]->gearbox[z]->shaft[z]->saw[up,axis=z]",
                        observations, "c07-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || accepted.ordinaryPlayerEligible()) throw new IllegalStateException("C07 candidate gate changed: " + validation);
        cleanupBestEffort(fixture);
        if (fixture.changed.stream().anyMatch(position -> !fixture.level.getBlockState(position).isAir()))
            throw new IllegalStateException("C07 candidate baseline did not restore");
        LOGGER.info("C07_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} network={} speeds={}",
                WATER_WHEEL, observations.keySet(), network,
                observations.values().stream().map(value -> Double.toString(value.speedRpm())).toList());
        LOGGER.info("C07_SURVIVAL_POWER_PROCESS PASS recipe=create:cutting/andesite_alloy input=create:andesite_alloy x1 output=create:shaft x{} cycleObserved={} realInputEntity=true",
                outputCount, fixture.sawCycleObserved);
        LOGGER.info("C07_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} returned={} duplicateWithdrawals={} duplicateReturns={} unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.returned(), balance.duplicateWithdrawals(),
                balance.duplicateReturns(), balance.unaccountedItems(), balance.materialLedgerBalanced());
        LOGGER.info("C07_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} ordinaryPlayerEligible={} rollbackVerified=true baselineRestored=true reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        LOGGER.info("C07_SURVIVAL_POWER_GAMETEST PASS minecraft=1.20.1 forge={} create={} realWaterWheel=true realSaw=true realCuttingRecipe=true realInputEntity=true realOutput=true realMaterialLedger=true candidateOnly=true",
                snapshots.values().stream().findFirst().orElseThrow().runtime().loaderVersion(),
                snapshots.values().stream().findFirst().orElseThrow().runtime().industrialModVersions().get("create"));
    }

    private static Map<String, KineticSnapshot> captureKinetics(Fixture fixture) {
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(fixture.level);
        Map<String, BlockPos> positions = Map.of("water_wheel", fixture.waterWheelPosition,
                "gearbox_bottom", fixture.gearboxPosition,
                "vertical_shaft", fixture.verticalShaftPosition,
                "gearbox_top", fixture.gearboxTopPosition,
                "horizontal_shaft", fixture.horizontalShaftPosition,
                "mechanical_saw", fixture.sawPosition);
        Map<String, KineticSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> entry : positions.entrySet()) {
            AdapterResult<KineticSnapshot> result = adapter.captureKinetics(
                    new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(blockPos(entry.getValue())));
            if (result instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                if (failure.code() == dev.stevecreate.agent.adapter.api.AdapterFailureCode.LIFECYCLE_NOT_READY)
                    throw new Pending("C07 kinetic network is still settling: " + entry.getKey());
                throw new IllegalStateException("C07 kinetic capture failed: " + failure);
            }
            KineticSnapshot snapshot = ((AdapterResult.Success<KineticSnapshot>) result).value();
            if (snapshot.speedRpm() <= 0 || snapshot.overstressed())
                throw new Pending("C07 role is not positively powered: " + entry.getKey());
            snapshots.put(entry.getKey(), snapshot);
        }
        OptionalLong firstNetwork = snapshots.values().stream().findFirst().orElseThrow().networkId();
        if (firstNetwork.isEmpty() || snapshots.values().stream().anyMatch(value -> value.networkId().isEmpty()
                || value.networkId().getAsLong() != firstNetwork.getAsLong()))
            throw new Pending("C07 roles do not yet share one live Create network");
        return snapshots;
    }

    private static void feedRealSaw(Fixture fixture) {
        SawBlockEntity saw = saw(fixture.level, fixture.sawPosition);
        if (saw == null) throw new IllegalStateException("C07 saw block entity is missing before feed");
        var recipeHolder = fixture.level.getRecipeManager().byKey(key("create", "cutting/andesite_alloy"));
        if (recipeHolder.isEmpty() || !(recipeHolder.orElseThrow() instanceof CuttingRecipe recipe))
            throw new IllegalStateException("C07 live Create cutting recipe is missing");
        if (recipe.getType() != AllRecipeTypes.CUTTING.getType() || recipe.getRollableResults().size() != 1)
            throw new IllegalStateException("C07 live recipe is not one-output cutting");
        ProcessingOutput result = recipe.getRollableResults().get(0);
        if (!SHAFT.equals(itemId(result.getStack())) || result.getStack().getCount() != 6
                || result.getChance() != 1.0F)
            throw new IllegalStateException("C07 live recipe output is not guaranteed shaft x6");
        FilteringBehaviour filter = saw.getBehaviour(FilteringBehaviour.TYPE);
        Item output = item(SHAFT);
        if (filter == null || output == null) throw new IllegalStateException("C07 saw output filter unavailable");
        filter.setFilter(new ItemStack(output, 1));
        ItemEntity entity = new ItemEntity(fixture.level, fixture.sawPosition.getX() + 0.5D,
                fixture.sawPosition.getY() + 1.05D, fixture.sawPosition.getZ() + 0.5D,
                new ItemStack(item(ANDESITE_ALLOY), 1));
        if (!fixture.level.addFreshEntity(entity)) throw new IllegalStateException("C07 input entity was rejected");
        fixture.inputEntity = entity.getUUID();
        saw.insertItem(entity);
        if (entity.isAlive() || saw.inventory.isEmpty())
            throw new IllegalStateException("C07 real saw did not consume the input entity");
        fixture.fed = true;
    }

    private static void transferOutputs(Fixture fixture, ChestBlockEntity chest) {
        for (ItemEntity entity : fixture.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(fixture.sawPosition).inflate(2.0D, 2.0D, 2.0D), Entity::isAlive)) {
            if (!SHAFT.equals(itemId(entity.getItem())))
                throw new IllegalStateException("C07 item lane contains unexpected output " + itemId(entity.getItem()));
            ItemStack stack = entity.getItem().copy();
            ItemStack remainder = ItemHandlerHelper.insertItem(new InvWrapper(chest), stack, false);
            if (remainder.isEmpty()) entity.discard(); else entity.setItem(remainder);
        }
    }

    private static void cleanupBestEffort(Fixture fixture) {
        for (BlockPos position : fixture.changed) fixture.level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        fixture.sourceChestEntity.setChanged();
    }

    private static void placeSaw(ServerLevel level, BlockPos position) {
        Block block = ForgeRegistries.BLOCKS.getValue(key("create", "mechanical_saw"));
        if (block == null) throw new IllegalStateException("Missing C07 block create:mechanical_saw");
        BlockState state = block.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP)
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        if (!level.setBlockAndUpdate(position, state)) throw new IllegalStateException("Failed to place C07 saw");
    }

    private static void place(ServerLevel level, BlockPos position, ResourceLocation key, Object orientation) {
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null) throw new IllegalStateException("Missing C07 block " + key);
        BlockState state = block.defaultBlockState();
        if (orientation instanceof Direction.Axis axis) state = state.setValue(BlockStateProperties.AXIS, axis);
        else if (orientation instanceof Direction facing) state = state.setValue(BlockStateProperties.FACING, facing);
        if (!level.setBlockAndUpdate(position, state)) throw new IllegalStateException("Failed to place " + key);
    }

    private static void placeFluid(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) throw new IllegalStateException("Failed to place C07 fluid");
    }
    private static ChestBlockEntity chest(ServerLevel level, BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) throw new IllegalStateException("C07 chest BE missing");
        return chest;
    }
    private static SawBlockEntity saw(ServerLevel level, BlockPos position) {
        return level.getBlockEntity(position) instanceof SawBlockEntity saw ? saw : null;
    }
    private static void put(ChestBlockEntity chest, int slot, ResourceId itemId, int count) {
        chest.setItem(slot, new ItemStack(item(itemId), count));
    }
    private static void decrement(ChestBlockEntity chest, int slot, int count) {
        ItemStack stack = chest.getItem(slot);
        if (stack.isEmpty() || stack.getCount() != count) throw new IllegalStateException("C07 source withdrawal mismatch");
        chest.setItem(slot, ItemStack.EMPTY);
    }
    private static Item item(ResourceId id) {
        Item item = ForgeRegistries.ITEMS.getValue(key(id.namespace(), id.path()));
        if (item == null) throw new IllegalStateException("Missing C07 item " + id);
        return item;
    }
    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : id(key.toString());
    }
    private static int count(ChestBlockEntity chest, ResourceId item) {
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) if (item.equals(itemId(chest.getItem(slot)))) count += chest.getItem(slot).getCount();
        return count;
    }
    private static boolean entityPresent(ServerLevel level, java.util.UUID id) {
        Entity entity = level.getEntity(id); return entity != null && entity.isAlive();
    }
    private static BlockPos3i blockPos(BlockPos position) { return new BlockPos3i(position.getX(), position.getY(), position.getZ()); }
    private static ResourceLocation key(String namespace, String path) { return ResourceLocation.fromNamespaceAndPath(namespace, path); }
    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private record MaterialSpec(ResourceId item, int quantity) {}
    private record MaterialWithdrawal(ResourceId transactionId, int slot, MaterialSpec spec) {}
    private static final class Fixture {
        private final ServerLevel level; private final BlockPos origin; private final BlockPos sourceChest;
        private final ChestBlockEntity sourceChestEntity; private final ProjectMaterialLedger ledger;
        private final ResourceId project; private final List<BlockPos> changed;
        private final List<MaterialWithdrawal> withdrawals; private final BlockPos waterWheelPosition;
        private final BlockPos gearboxPosition; private final BlockPos verticalShaftPosition;
        private final BlockPos gearboxTopPosition; private final BlockPos horizontalShaftPosition;
        private final BlockPos sawPosition;
        private final BlockPos depotPosition; private final BlockPos outputChest; private boolean completed;
        private boolean fed; private boolean sawCycleObserved; private java.util.UUID inputEntity;
        private Fixture(ServerLevel level, BlockPos origin, BlockPos sourceChest, ChestBlockEntity sourceChestEntity,
                ProjectMaterialLedger ledger, ResourceId project, List<BlockPos> changed,
                List<MaterialWithdrawal> withdrawals, BlockPos waterWheelPosition, BlockPos gearboxPosition,
                BlockPos verticalShaftPosition, BlockPos gearboxTopPosition,
                BlockPos horizontalShaftPosition, BlockPos sawPosition, BlockPos depotPosition, BlockPos outputChest,
                boolean completed, boolean fed, boolean sawCycleObserved) {
            this.level = level; this.origin = origin; this.sourceChest = sourceChest; this.sourceChestEntity = sourceChestEntity;
            this.ledger = ledger; this.project = project; this.changed = changed; this.withdrawals = withdrawals;
            this.waterWheelPosition = waterWheelPosition; this.gearboxPosition = gearboxPosition;
            this.verticalShaftPosition = verticalShaftPosition; this.gearboxTopPosition = gearboxTopPosition;
            this.horizontalShaftPosition = horizontalShaftPosition;
            this.sawPosition = sawPosition; this.depotPosition = depotPosition; this.outputChest = outputChest;
            this.completed = completed; this.fed = fed; this.sawCycleObserved = sawCycleObserved;
        }
        private WaterWheelBlockEntity waterWheel() {
            return level.getBlockEntity(waterWheelPosition) instanceof WaterWheelBlockEntity value ? value : null;
        }
    }
    private static final class Pending extends RuntimeException { private Pending(String message) { super(message); } }
}
