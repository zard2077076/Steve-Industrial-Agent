package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
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
import dev.stevecreate.agent.core.execution.construction.MaterialTransaction;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Historical isolated real-Create probe that seeded the C-06 survival topology review.
 *
 * <p>The probe constructs its own review-only candidate and therefore remains useful
 * as independent source/material evidence after the larger production topology was
 * promoted. Production eligibility is proved by the ordinary three-mode, recovery
 * and fault suites, not inferred from this smaller upward-fan fixture.</p>
 */
@PrefixGameTestTemplate(false)
public final class CreateC06SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId FAN = id("create:encased_fan");
    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId GLASS = id("minecraft:glass");
    private static final ResourceId IRON_BARS = id("minecraft:iron_bars");
    private static final ResourceId PROJECT = id("steve_industrial:c06/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c06/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;
    private static final String BLOCK_HASH = "a".repeat(64);
    private static final String INVENTORY_HASH = "b".repeat(64);

    private CreateC06SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_200)
    public static void c06WaterWheelFanSurvivalCandidate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        Fixture fixture = buildFixture(level, origin);
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
        BlockPos sourceChest = origin.offset(8, 0, 0);
        place(level, sourceChest, key("minecraft", "chest"), null);
        ChestBlockEntity chest = chest(level, sourceChest);

        List<MaterialSpec> specs = List.of(
                new MaterialSpec(WATER_WHEEL, 1),
                new MaterialSpec(GEARBOX, 1),
                new MaterialSpec(SHAFT, 1),
                new MaterialSpec(FAN, 1),
                new MaterialSpec(STONE, 13),
                new MaterialSpec(GLASS, 2),
                new MaterialSpec(IRON_BARS, 1));
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec spec = specs.get(index);
            put(chest, index, spec.item(), spec.quantity());
            slots.add(new MaterialSlotSnapshot(
                    index, new MaterialIdentity(spec.item(), EMPTY_PAYLOAD), spec.quantity()));
        }

        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c06/survival-power-plan"), PROJECT,
                id("create:c06/water-wheel-fan"), 1, "forge-create-6.0.6", "c".repeat(64),
                specs.stream().map(spec -> new MaterialRequirement(
                        id("steve_industrial:c06/requirement/" + spec.item().path()),
                        id("create:c06/water-wheel-fan"), 0, RecipeIngredientKind.EXACT_RESOURCE,
                        spec.item().toString(), List.of(spec.item()), spec.quantity())).toList());
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(
                SOURCE, PROJECT, "c06-acceptance", id("minecraft:overworld"),
                blockPos(sourceChest), "up", "minecraft:chest", BLOCK_HASH, INVENTORY_HASH,
                0, specs.stream().mapToLong(MaterialSpec::quantity).sum(), 0, 100_000, slots), 1);

        List<MaterialWithdrawal> withdrawals = new ArrayList<>();
        long tick = 2;
        for (int index = 0; index < specs.size(); index++) {
            MaterialSpec spec = specs.get(index);
            MaterialRequirement requirement = plan.requirements().stream()
                    .filter(value -> value.acceptedResources().contains(spec.item())).findFirst().orElseThrow();
            ResourceId allocationId = id("steve_industrial:c06/allocation/" + index);
            MaterialIdentity identity = new MaterialIdentity(spec.item(), EMPTY_PAYLOAD);
            ledger.reserve(new MaterialAllocation(
                    allocationId, PROJECT, requirement.requirementId(), SOURCE, index,
                    identity, spec.quantity()), tick++);
            ResourceId transactionId = id("steve_industrial:c06/transaction/" + index);
            ledger.prepare(transactionId, PROJECT, id("steve_industrial:c06/task/" + index),
                    allocationId, MaterialExecutorKind.DIRECT, spec.quantity(), tick++);
            decrement(chest, index, spec.quantity());
            ledger.advance(transactionId, MaterialTransactionState.WITHDRAWN, tick++);
            withdrawals.add(new MaterialWithdrawal(transactionId, index, spec));
        }

        List<BlockPos> changed = new ArrayList<>();
        List<BlockPos> stone = List.of(
                origin.offset(0, 1, -1), origin.offset(-1, 2, -1), origin.offset(1, 2, -1),
                origin.offset(0, 2, -2), origin.offset(0, 2, 0), origin.offset(-1, 4, -1),
                origin.offset(1, 4, -1), origin.offset(0, 4, -2), origin.offset(0, 4, 0),
                origin.offset(-1, 3, -1), origin.offset(1, 3, -1), origin.offset(0, 3, -2),
                origin.offset(3, 4, 0));
        for (BlockPos position : stone) {
            place(level, position, key("minecraft", "stone"), null);
            changed.add(position);
        }
        place(level, origin.offset(3, 5, -1), key("minecraft", "glass"), null);
        place(level, origin.offset(3, 5, 1), key("minecraft", "glass"), null);
        place(level, origin.offset(4, 5, 0), key("minecraft", "iron_bars"), null);
        changed.addAll(List.of(origin.offset(3, 5, -1), origin.offset(3, 5, 1), origin.offset(4, 5, 0)));

        BlockPos waterWheel = origin.offset(0, 3, 0);
        BlockPos gearbox = origin.offset(1, 3, 0);
        BlockPos shaft = origin.offset(1, 4, 0);
        BlockPos fan = origin.offset(2, 5, 0);
        place(level, waterWheel, key("create", "water_wheel"), Direction.EAST);
        place(level, gearbox, key("create", "gearbox"), Direction.Axis.Z);
        place(level, shaft, key("create", "shaft"), Direction.Axis.Y);
        // An encased fan is itself a directional kinetic block.  FACING=UP makes
        // its lower shaft port connect to the vertical shaft immediately below;
        // inserting a horizontal gearbox here creates a visually plausible but
        // disconnected network, which the first real probe correctly rejected.
        fan = origin.offset(1, 5, 0);
        place(level, fan, key("create", "encased_fan"), Direction.UP);
        changed.addAll(List.of(waterWheel, gearbox, shaft, fan));

        BlockPos waterSource = origin.offset(0, 4, -1);
        BlockPos waterFlowOne = origin.offset(0, 3, -1);
        BlockPos waterFlowTwo = origin.offset(0, 2, -1);
        placeFluid(level, waterSource, Fluids.WATER.defaultFluidState().createLegacyBlock());
        placeFluid(level, waterFlowOne, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        placeFluid(level, waterFlowTwo, Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
        changed.addAll(List.of(waterSource, waterFlowOne, waterFlowTwo));
        BlockEntityAccess access = new BlockEntityAccess(level, waterWheel, fan);
        return new Fixture(level, origin, sourceChest, chest, ledger, PROJECT, changed,
                withdrawals, access, false);
    }

    private static void verifyAndCleanup(Fixture fixture) {
        ServerLevel level = fixture.level;
        WaterWheelBlockEntity waterWheel = fixture.access.waterWheel();
        if (waterWheel != null && waterWheel.flowScore == 0) {
            waterWheel.determineAndApplyFlowScore();
        }
        ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(level);
        Map<String, KineticSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> entry : Map.of(
                "water_wheel", fixture.origin.offset(0, 3, 0),
                "gearbox", fixture.origin.offset(1, 3, 0),
                "vertical_shaft", fixture.origin.offset(1, 4, 0),
                "fan_drive", fixture.origin.offset(1, 5, 0)).entrySet()) {
            AdapterResult<KineticSnapshot> result = adapter.captureKinetics(
                    new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(blockPos(entry.getValue())));
            if (result instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                if (failure.code() == dev.stevecreate.agent.adapter.api.AdapterFailureCode.LIFECYCLE_NOT_READY) {
                    throw new Pending("C06 kinetic network is still settling: " + entry.getKey());
                }
                throw new IllegalStateException("C06 kinetic capture failed: " + failure);
            }
            KineticSnapshot snapshot = ((AdapterResult.Success<KineticSnapshot>) result).value();
            if (snapshot.speedRpm() <= 0 || snapshot.overstressed()) {
                throw new Pending("C06 role is not positively powered: " + entry.getKey());
            }
            snapshots.put(entry.getKey(), snapshot);
        }
        OptionalLong firstNetwork = snapshots.values().stream().findFirst().orElseThrow().networkId();
        String network = firstNetwork.isPresent()
                ? Long.toUnsignedString(firstNetwork.getAsLong()) : "none";
        if (snapshots.values().stream().anyMatch(value -> value.networkId().isEmpty()
                || !network.equals(Long.toUnsignedString(value.networkId().orElseThrow())))) {
            throw new IllegalStateException("C06 roles did not share one live Create network");
        }
        EncasedFanBlockEntity fan = fixture.access.fan();
        if (fan == null) throw new IllegalStateException("C06 fan block entity disappeared");
        AirCurrent airflow = fan.getAirCurrent();
        if (airflow == null || airflow.bounds == null || airflow.segments == null
                || airflow.segments.isEmpty() || airflow.maxDistance < 1) {
            throw new Pending("C06 fan AirCurrent is not live yet");
        }

        long settlementTick = 100;
        for (MaterialWithdrawal withdrawal : fixture.withdrawals) {
            fixture.ledger.advance(withdrawal.transactionId, MaterialTransactionState.RETURN_PENDING,
                    settlementTick++);
            put(fixture.chest, withdrawal.slot, withdrawal.spec.item(), withdrawal.spec.quantity());
            fixture.ledger.advance(withdrawal.transactionId, MaterialTransactionState.RETURNED,
                    settlementTick++);
        }
        MaterialBalanceReport balance = fixture.ledger.balance(fixture.project);
        if (!balance.materialLedgerBalanced() || balance.duplicateWithdrawals() != 0
                || balance.duplicateReturns() != 0 || balance.unaccountedItems() != 0) {
            throw new IllegalStateException("C06 candidate material ledger is not balanced: " + balance);
        }
        cleanupBestEffort(fixture);
        if (fixture.changed.stream().anyMatch(position -> !level.getBlockState(position).isAir())) {
            throw new IllegalStateException("C06 candidate baseline did not restore");
        }
        CreateSurvivalPowerMappingV1.Entry mapping = CreateSurvivalPowerMappingV1.candidate(
                CreateCapabilityContractV1.C06, WATER_WHEEL,
                Map.of("water_wheel", WATER_WHEEL, "gearbox", GEARBOX,
                        "vertical_shaft", SHAFT, "fan_drive", FAN),
                "c06-water-wheel-fan");
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations = new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role,
                new CreateSurvivalPowerEvidenceV1.RoleObservation(
                        value.blockId(), network, value.gameTick(), value.speedRpm(),
                        value.stressEnabled(), value.stressCapacity(), value.stressLoad(), value.overstressed())));
        CreateSurvivalPowerEvidenceV1.Validation validation = CreateSurvivalPowerEvidenceV1.validate(
                new CreateSurvivalPowerEvidenceV1.Candidate(mapping, WATER_WHEEL,
                        "c06:water_wheel[x]->gearbox[z]->shaft[y]->fan[up]",
                        observations, "c06-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || accepted.ordinaryPlayerEligible()) {
            throw new IllegalStateException("C06 candidate evidence changed the review gate: " + validation);
        }
        LOGGER.info("C06_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} network={} speeds={} airflowReach={}",
                WATER_WHEEL, observations.keySet(), network,
                observations.values().stream().map(value -> Double.toString(value.speedRpm())).toList(),
                airflow.maxDistance);
        LOGGER.info("C06_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} returned={} "
                        + "duplicateWithdrawals={} duplicateReturns={} unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.returned(),
                balance.duplicateWithdrawals(), balance.duplicateReturns(), balance.unaccountedItems(),
                balance.materialLedgerBalanced());
        LOGGER.info("C06_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} ordinaryPlayerEligible={} "
                        + "rollbackVerified=true baselineRestored=true reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        LOGGER.info("C06_SURVIVAL_POWER_GAMETEST PASS minecraft=1.20.1 forge={} create={} "
                        + "realWaterWheel=true realFan=true realAirCurrent=true realMaterialLedger=true "
                        + "candidateOnly=true", snapshots.values().stream().findFirst().orElseThrow()
                                .runtime().loaderVersion(), snapshots.values().stream().findFirst().orElseThrow()
                                .runtime().industrialModVersions().get("create"));
    }

    private static void cleanupBestEffort(Fixture fixture) {
        for (BlockPos position : fixture.changed) {
            fixture.level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        fixture.chest.setChanged();
    }

    private static void place(ServerLevel level, BlockPos position, ResourceLocation key, Object orientation) {
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null) throw new IllegalStateException("Missing C06 block " + key);
        BlockState state = block.defaultBlockState();
        if (orientation instanceof Direction.Axis axis) {
            if (!state.hasProperty(BlockStateProperties.AXIS)) throw new IllegalStateException(key + " has no axis");
            state = state.setValue(BlockStateProperties.AXIS, axis);
        } else if (orientation instanceof Direction facing) {
            if (!state.hasProperty(BlockStateProperties.FACING)) throw new IllegalStateException(key + " has no facing");
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        if (!level.setBlockAndUpdate(position, state)) throw new IllegalStateException("Failed to place " + key);
    }

    private static void placeFluid(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) throw new IllegalStateException("Failed to place fluid");
    }

    private static ChestBlockEntity chest(ServerLevel level, BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("C06 source chest block entity is missing");
        }
        return chest;
    }

    private static void put(ChestBlockEntity chest, int slot, ResourceId itemId, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(key(itemId.namespace(), itemId.path()));
        if (item == null) throw new IllegalStateException("Missing C06 material item " + itemId);
        chest.setItem(slot, new ItemStack(item, count));
    }

    private static void decrement(ChestBlockEntity chest, int slot, int count) {
        ItemStack stack = chest.getItem(slot);
        if (stack.isEmpty() || stack.getCount() != count) throw new IllegalStateException("C06 source withdrawal mismatch");
        chest.setItem(slot, ItemStack.EMPTY);
    }

    private static BlockPos3i blockPos(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static ResourceLocation key(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private record MaterialSpec(ResourceId item, int quantity) {}
    private record MaterialWithdrawal(ResourceId transactionId, int slot, MaterialSpec spec) {}
    private record BlockEntityAccess(ServerLevel level, BlockPos waterWheelPosition, BlockPos fanPosition) {
        WaterWheelBlockEntity waterWheel() {
            return level.getBlockEntity(waterWheelPosition) instanceof WaterWheelBlockEntity value ? value : null;
        }
        EncasedFanBlockEntity fan() {
            return level.getBlockEntity(fanPosition) instanceof EncasedFanBlockEntity value ? value : null;
        }
    }
    private static final class Fixture {
        private final ServerLevel level;
        private final BlockPos origin;
        private final BlockPos sourceChest;
        private final ChestBlockEntity chest;
        private final ProjectMaterialLedger ledger;
        private final ResourceId project;
        private final List<BlockPos> changed;
        private final List<MaterialWithdrawal> withdrawals;
        private final BlockEntityAccess access;
        private boolean completed;
        private Fixture(ServerLevel level, BlockPos origin, BlockPos sourceChest, ChestBlockEntity chest,
                ProjectMaterialLedger ledger, ResourceId project, List<BlockPos> changed,
                List<MaterialWithdrawal> withdrawals, BlockEntityAccess access, boolean completed) {
            this.level = level; this.origin = origin; this.sourceChest = sourceChest; this.chest = chest;
            this.ledger = ledger; this.project = project; this.changed = changed;
            this.withdrawals = withdrawals; this.access = access; this.completed = completed;
        }
    }
    private static final class Pending extends RuntimeException {
        private Pending(String message) { super(message); }
    }
}
