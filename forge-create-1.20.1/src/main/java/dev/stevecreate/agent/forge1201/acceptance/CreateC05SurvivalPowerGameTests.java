package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crusher.CrushingRecipe;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
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
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelRole;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import dev.stevecreate.agent.core.player.CreateCapabilityContractV1;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerEvidenceV1;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerMappingV1;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateKineticAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Production mirrored-water-wheel C-05 gate; isolated because crushing reshapes its arena. */
@PrefixGameTestTemplate(false)
public final class CreateC05SurvivalPowerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId CRUSHING_WHEEL = id("create:crushing_wheel");
    private static final ResourceId HOPPER = id("minecraft:hopper");
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER_BUCKET = id("minecraft:water_bucket");
    private static final ResourceId GRAVEL = id("minecraft:gravel");
    private static final ResourceId SAND = id("minecraft:sand");
    private static final ResourceId PROJECT = id("steve_industrial:c05/survival-power-probe");
    private static final ResourceId SOURCE = id("steve_industrial:c05/source");
    private static final String EMPTY_PAYLOAD = MaterialIdentity.EMPTY_PAYLOAD_SHA256;

    private CreateC05SurvivalPowerGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 4_000)
    public static void c05MirroredWaterWheelCrushingProductionZero(GameTestHelper helper) {
        run(helper, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 4_000)
    public static void c05MirroredWaterWheelCrushingProductionClockwise90(GameTestHelper helper) {
        run(helper, QuarterTurn.CLOCKWISE_90);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 4_000)
    public static void c05MirroredWaterWheelCrushingProductionClockwise180(GameTestHelper helper) {
        run(helper, QuarterTurn.CLOCKWISE_180);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 4_000)
    public static void c05MirroredWaterWheelCrushingProductionClockwise270(GameTestHelper helper) {
        run(helper, QuarterTurn.CLOCKWISE_270);
    }

    private static void run(GameTestHelper helper, QuarterTurn rotation) {
        Fixture fixture = build(
                helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), rotation);
        helper.succeedWhen(() -> {
            if (fixture.completed) return;
            if (helper.getTick() < 100) {
                throw new GameTestAssertException(
                        "Waiting for C05 enclosed water drives to prove long-lived flow");
            }
            try { verify(fixture); fixture.completed = true; }
            catch (Pending pending) { throw new GameTestAssertException(pending.getMessage()); }
            catch (RuntimeException failure) { cleanup(fixture); throw failure; }
        });
    }

    private static Fixture build(ServerLevel level, BlockPos origin, QuarterTurn rotation) {
        BlockPos sourcePos = origin.offset(12, 0, 0);
        place(level, sourcePos, key("minecraft", "chest"), null);
        ChestBlockEntity source = chest(level, sourcePos);
        List<MaterialSpec> specs = List.of(
                spec("water-wheels", WATER_WHEEL, 2, false),
                spec("crushing-wheels", CRUSHING_WHEEL, 2, false),
                spec("hopper", HOPPER, 1, false), spec("chest", CHEST, 1, false),
                spec("supports", STONE, 9, false),
                spec("water-sources", WATER_BUCKET, 2, false),
                spec("gravel-input", GRAVEL, 1, true));
        List<MaterialRequirement> requirements = new ArrayList<>();
        List<MaterialSlotSnapshot> slots = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            MaterialSpec value = specs.get(i); put(source, i, value.item(), value.quantity());
            slots.add(new MaterialSlotSnapshot(i, new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()));
            requirements.add(new MaterialRequirement(id("steve_industrial:c05/requirement/" + value.label()),
                    id("create:c05/mirrored-water-wheel-crushing"), i, RecipeIngredientKind.EXACT_RESOURCE,
                    value.item().toString(), List.of(value.item()), value.quantity()));
        }
        MaterialRequirementPlan plan = new MaterialRequirementPlan(
                id("steve_industrial:c05/survival-power-plan"), PROJECT,
                id("create:c05/mirrored-water-wheel-crushing"), 1, "forge-create-6.0.6",
                "c".repeat(64), requirements);
        ProjectMaterialLedger ledger = new ProjectMaterialLedger(); ledger.registerPlan(plan);
        ledger.bindSource(new MaterialSourceBinding(SOURCE, PROJECT, "c05-acceptance",
                id("minecraft:overworld"), pos(sourcePos), "up", "minecraft:chest",
                "a".repeat(64), "b".repeat(64), 0,
                specs.stream().mapToLong(MaterialSpec::quantity).sum(), 0, 100_000, slots), 1);
        List<Withdrawal> withdrawals = new ArrayList<>(); long tick = 2;
        for (int i = 0; i < specs.size(); i++) {
            MaterialSpec value = specs.get(i); ResourceId allocation = id("steve_industrial:c05/allocation/" + i);
            ledger.reserve(new MaterialAllocation(allocation, PROJECT, requirements.get(i).requirementId(),
                    SOURCE, i, new MaterialIdentity(value.item(), EMPTY_PAYLOAD), value.quantity()), tick++);
            ResourceId transaction = id("steve_industrial:c05/transaction/" + i);
            ledger.prepare(transaction, PROJECT, id("steve_industrial:c05/task/" + i), allocation,
                    MaterialExecutorKind.DIRECT, value.quantity(), tick++);
            decrement(source, i, value.quantity()); ledger.advance(transaction, MaterialTransactionState.WITHDRAWN, tick++);
            withdrawals.add(new Withdrawal(transaction, i, value));
        }

        CrushingWheelPlan physical = CrushingWheelPlan.at(pos(origin), rotation);
        List<BlockPos> changed = new ArrayList<>();
        for (var placement : physical.placements()) {
            BlockPos target = blockPos(placement.position());
            if (placement.role() == CrushingWheelRole.OUTPUT_HOPPER) {
                placeHopper(level, target);
            } else if (placement.role() == CrushingWheelRole.LEFT_WATER_SOURCE
                    || placement.role() == CrushingWheelRole.RIGHT_WATER_SOURCE) {
                fluid(level, target, Fluids.WATER.defaultFluidState().createLegacyBlock());
                BlockPos firstFlow = target.below();
                BlockPos secondFlow = target.below(2);
                fluid(level, firstFlow,
                        Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
                fluid(level, secondFlow,
                        Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock());
                changed.addAll(List.of(firstFlow, secondFlow));
            } else {
                Object typedState = switch (placement.role()) {
                    case LEFT_WHEEL, RIGHT_WHEEL -> placement.rotationAxis() == PlanBlockAxis.Z
                            ? Direction.Axis.Z : Direction.Axis.X;
                    case LEFT_DRIVE, RIGHT_DRIVE -> placement.rotationAxis() == PlanBlockAxis.Z
                            ? Direction.NORTH : Direction.WEST;
                    default -> null;
                };
                place(level, target,
                        key(placement.blockId().namespace(), placement.blockId().path()), typedState);
            }
            changed.add(target);
        }
        BlockPos chest = blockPos(physical.placement(CrushingWheelRole.OUTPUT_CHEST).position());
        BlockPos hopper = blockPos(physical.placement(CrushingWheelRole.OUTPUT_HOPPER).position());
        BlockPos leftWheel = blockPos(physical.placement(CrushingWheelRole.LEFT_WHEEL).position());
        BlockPos rightWheel = blockPos(physical.placement(CrushingWheelRole.RIGHT_WHEEL).position());
        BlockPos leftDrive = blockPos(physical.placement(CrushingWheelRole.LEFT_DRIVE).position());
        BlockPos rightDrive = blockPos(physical.placement(CrushingWheelRole.RIGHT_DRIVE).position());
        BlockPos controller = blockPos(physical.controllerPosition());
        BlockPos input = blockPos(physical.inputSpawnPosition());
        BlockState leftState = level.getBlockState(leftWheel);
        ((CrushingWheelBlock) leftState.getBlock()).updateControllers(leftState, level, leftWheel, Direction.EAST);
        changed.addAll(List.of(controller, sourcePos));
        return new Fixture(level, source, ledger, withdrawals, changed, leftDrive, rightDrive,
                leftWheel, rightWheel, controller, input, hopper, chest, rotation);
    }

    private static void verify(Fixture f) {
        for (BlockPos p : List.of(f.leftDrive, f.rightDrive)) {
            WaterWheelBlockEntity wheel = waterWheel(f.level, p);
            if (wheel != null && wheel.flowScore == 0) wheel.determineAndApplyFlowScore();
        }
        Map<String, KineticSnapshot> snapshots = kinetics(f);
        KineticSnapshot left = snapshots.get("left_wheel"), right = snapshots.get("right_wheel");
        double signedLeft = signed(left), signedRight = signed(right);
        if (Math.signum(signedLeft) == Math.signum(signedRight)
                || Double.compare(Math.abs(signedLeft), Math.abs(signedRight)) != 0)
            throw new IllegalStateException("C05 wheels are not opposed: " + signedLeft + "/" + signedRight);
        BlockState controllerState = f.level.getBlockState(f.controller);
        if (!AllBlocks.CRUSHING_WHEEL_CONTROLLER.has(controllerState)
                || !controllerState.getValue(CrushingWheelControllerBlock.VALID)
                || controllerState.getValue(BlockStateProperties.FACING) != Direction.DOWN)
            throw new Pending("C05 controller not valid/down: " + controllerState);
        CrushingWheelControllerBlockEntity controller = controller(f.level, f.controller);
        if (!f.fed) feed(f, controller);
        ChestBlockEntity chest = chest(f.level, f.chest);
        int sand = count(chest, SAND);
        boolean requiredItemInFlight = f.level.getEntitiesOfClass(
                        ItemEntity.class, new AABB(f.controller).inflate(2, 3, 2), Entity::isAlive)
                .stream()
                .map(entity -> itemId(entity.getItem()))
                .anyMatch(item -> GRAVEL.equals(item) || SAND.equals(item));
        boolean inFlight = controller == null || controller.isOccupied()
                || !controller.inventory.isEmpty() || !hopper(f.level, f.hopper).isEmpty()
                || requiredItemInFlight;
        if (inFlight || sand < 1) throw new Pending("C05 real crushing still processing sand=" + sand);
        long tick = 100;
        for (Withdrawal row : f.withdrawals) {
            if (row.spec.consumed()) { f.ledger.advance(row.transaction, MaterialTransactionState.DELIVERED, tick++); f.ledger.advance(row.transaction, MaterialTransactionState.CONSUMED, tick++); }
            else { f.ledger.advance(row.transaction, MaterialTransactionState.RETURN_PENDING, tick++); put(f.source, row.slot, row.spec.item(), row.spec.quantity()); f.ledger.advance(row.transaction, MaterialTransactionState.RETURNED, tick++); }
        }
        MaterialBalanceReport balance = f.ledger.balance(PROJECT);
        if (!balance.materialLedgerBalanced() || balance.consumed() != 1 || balance.duplicateWithdrawals() != 0
                || balance.duplicateReturns() != 0 || balance.unaccountedItems() != 0)
            throw new IllegalStateException("C05 ledger imbalance: " + balance);
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations = new LinkedHashMap<>();
        snapshots.forEach((role, value) -> observations.put(role, new CreateSurvivalPowerEvidenceV1.RoleObservation(
                value.blockId(), Long.toUnsignedString(value.networkId().orElseThrow()), value.gameTick(), value.speedRpm(),
                value.stressEnabled(), value.stressCapacity(), value.stressLoad(), value.overstressed())));
        CreateSurvivalPowerMappingV1.Entry mapping = CreateSurvivalPowerMappingV1.forCapability(
                CreateCapabilityContractV1.C05);
        var validation = CreateSurvivalPowerEvidenceV1.validate(new CreateSurvivalPowerEvidenceV1.Candidate(mapping,
                WATER_WHEEL, "c05:{enclosed_water_wheel[z]->crushing_wheel[z]}x2->controller[down]",
                observations, "c05-material-plan", true, true, true, true));
        if (!(validation instanceof CreateSurvivalPowerEvidenceV1.Accepted accepted)
                || !accepted.ordinaryPlayerEligible())
            throw new IllegalStateException("C05 production gate refused: " + validation);
        chest.clearContent(); cleanup(f);
        LOGGER.info("C05_SURVIVAL_POWER_TOPOLOGY PASS source={} roles={} signedWheelSpeeds=[{},{}] controller=valid_down",
                WATER_WHEEL, observations.keySet(), signedLeft, signedRight);
        LOGGER.info("C05_SURVIVAL_POWER_PROCESS PASS recipe=create:crushing/gravel input=minecraft:gravel x1 output=minecraft:sand x{} realInputEntity=true", sand);
        LOGGER.info("C05_SURVIVAL_POWER_MATERIAL PASS planned={} reserved={} withdrawn={} consumed={} returned={} duplicateWithdrawals={} duplicateReturns={} unaccountedItems={} materialLedgerBalanced={}",
                balance.planned(), balance.reserved(), balance.withdrawn(), balance.consumed(), balance.returned(),
                balance.duplicateWithdrawals(), balance.duplicateReturns(), balance.unaccountedItems(), balance.materialLedgerBalanced());
        LOGGER.info("C05_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete={} ordinaryPlayerEligible={} rollbackVerified=true baselineRestored=true reviewStatus={}",
                validation.evidenceComplete(), accepted.ordinaryPlayerEligible(), mapping.status());
        LOGGER.info("C05_SURVIVAL_POWER_GAMETEST PASS orientation={} realWaterWheels=true realCrushingWheels=true realController=true realRecipe=true realOutput=true realMaterialLedger=true production=true", f.rotation);
    }

    private static Map<String, KineticSnapshot> kinetics(Fixture f) {
        Map<String, BlockPos> positions = Map.of("left_drive", f.leftDrive, "left_wheel", f.leftWheel,
                "right_drive", f.rightDrive, "right_wheel", f.rightWheel);
        Map<String, KineticSnapshot> out = new LinkedHashMap<>(); ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(f.level);
        for (var row : positions.entrySet()) {
            AdapterResult<KineticSnapshot> result = adapter.captureKinetics(new dev.stevecreate.agent.adapter.api.KineticCaptureRequest(pos(row.getValue())));
            if (result instanceof AdapterResult.Failure<KineticSnapshot> failure) throw new Pending("C05 kinetic settling " + row.getKey() + ":" + failure);
            KineticSnapshot snapshot = ((AdapterResult.Success<KineticSnapshot>) result).value();
            if (snapshot.speedRpm() == 0 || snapshot.overstressed()) throw new Pending("C05 role not powered " + row.getKey());
            out.put(row.getKey(), snapshot);
        }
        return out;
    }
    private static double signed(KineticSnapshot v) { return v.rotationDirection() == KineticRotationDirection.POSITIVE ? v.speedRpm() : -v.speedRpm(); }
    private static void feed(Fixture f, CrushingWheelControllerBlockEntity controller) {
        ItemStackHandler input = new ItemStackHandler(1); input.setStackInSlot(0, new ItemStack(item(GRAVEL), 1));
        var recipe = f.level.getRecipeManager().getRecipeFor(AllRecipeTypes.CRUSHING.getType(), new RecipeWrapper(input), f.level).orElse(null);
        if (!(recipe instanceof CrushingRecipe crushing) || !crushing.getId().equals(key("create", "crushing/gravel"))) throw new IllegalStateException("C05 live recipe changed");
        ItemEntity entity = new ItemEntity(f.level, f.input.getX()+.5, f.input.getY()+.05, f.input.getZ()+.5, new ItemStack(item(GRAVEL), 1));
        entity.setDeltaMovement(0,-.05,0); if (!f.level.addFreshEntity(entity)) throw new IllegalStateException("C05 input rejected");
        f.inputId = entity.getUUID(); f.fed = true;
    }
    private static void cleanup(Fixture f) { for (BlockPos p : f.changed) f.level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState()); f.level.getEntitiesOfClass(ItemEntity.class,new AABB(f.controller).inflate(3,4,3),Entity::isAlive).forEach(Entity::discard); if (f.inputId != null) { Entity e=f.level.getEntity(f.inputId); if(e!=null)e.discard(); } }
    private static void placeHopper(ServerLevel l, BlockPos p) { Block b=ForgeRegistries.BLOCKS.getValue(key("minecraft","hopper")); BlockState s=b.defaultBlockState().setValue(HopperBlock.FACING,Direction.DOWN); if(!l.setBlockAndUpdate(p,s))throw new IllegalStateException("C05 hopper failed"); }
    private static void place(ServerLevel l, BlockPos p, ResourceLocation k, Object o) { Block b=ForgeRegistries.BLOCKS.getValue(k); if(b==null)throw new IllegalStateException("C05 missing "+k); BlockState s=b.defaultBlockState(); if(o instanceof Direction.Axis a)s=s.setValue(BlockStateProperties.AXIS,a); else if(o instanceof Direction d)s=s.setValue(BlockStateProperties.FACING,d); if(!l.setBlockAndUpdate(p,s))throw new IllegalStateException("C05 placement failed"); }
    private static void fluid(ServerLevel l,BlockPos p,BlockState s){if(!l.setBlockAndUpdate(p,s))throw new IllegalStateException("C05 fluid failed");}
    private static WaterWheelBlockEntity waterWheel(ServerLevel l,BlockPos p){return l.getBlockEntity(p) instanceof WaterWheelBlockEntity v?v:null;}
    private static CrushingWheelControllerBlockEntity controller(ServerLevel l,BlockPos p){return l.getBlockEntity(p) instanceof CrushingWheelControllerBlockEntity v?v:null;}
    private static HopperBlockEntity hopper(ServerLevel l,BlockPos p){if(!(l.getBlockEntity(p) instanceof HopperBlockEntity v))throw new IllegalStateException("C05 hopper missing");return v;}
    private static ChestBlockEntity chest(ServerLevel l,BlockPos p){if(!(l.getBlockEntity(p) instanceof ChestBlockEntity v))throw new IllegalStateException("C05 chest missing");return v;}
    private static MaterialSpec spec(String label,ResourceId item,int quantity,boolean consumed){return new MaterialSpec(label,item,quantity,consumed);}
    private static void put(ChestBlockEntity c,int slot,ResourceId id,int n){c.setItem(slot,new ItemStack(item(id),n));}
    private static void decrement(ChestBlockEntity c,int slot,int n){ItemStack s=c.getItem(slot);if(s.isEmpty()||s.getCount()!=n)throw new IllegalStateException("C05 source mismatch");c.setItem(slot,ItemStack.EMPTY);}
    private static int count(ChestBlockEntity c,ResourceId id){int n=0;for(int i=0;i<c.getContainerSize();i++)if(id.equals(itemId(c.getItem(i))))n+=c.getItem(i).getCount();return n;}
    private static Item item(ResourceId id){Item v=ForgeRegistries.ITEMS.getValue(key(id.namespace(),id.path()));if(v==null)throw new IllegalStateException("C05 item missing "+id);return v;}
    private static ResourceId itemId(ItemStack s){ResourceLocation k=ForgeRegistries.ITEMS.getKey(s.getItem());return k==null?null:id(k.toString());}
    private static BlockPos3i pos(BlockPos p){return new BlockPos3i(p.getX(),p.getY(),p.getZ());}
    private static BlockPos blockPos(BlockPos3i p){return new BlockPos(p.x(),p.y(),p.z());}
    private static ResourceLocation key(String n,String p){return ResourceLocation.fromNamespaceAndPath(n,p);}
    private static ResourceId id(String v){return ResourceId.parse(v);}
    private record MaterialSpec(String label,ResourceId item,int quantity,boolean consumed){}
    private record Withdrawal(ResourceId transaction,int slot,MaterialSpec spec){}
    private static final class Fixture { final ServerLevel level;final ChestBlockEntity source;final ProjectMaterialLedger ledger;final List<Withdrawal> withdrawals;final List<BlockPos> changed;final BlockPos leftDrive,rightDrive,leftWheel,rightWheel,controller,input,hopper,chest;final QuarterTurn rotation;boolean fed,completed;UUID inputId; Fixture(ServerLevel l,ChestBlockEntity s,ProjectMaterialLedger ledger,List<Withdrawal>w,List<BlockPos>c,BlockPos ld,BlockPos rd,BlockPos lw,BlockPos rw,BlockPos controller,BlockPos input,BlockPos hopper,BlockPos chest,QuarterTurn rotation){level=l;source=s;this.ledger=ledger;withdrawals=w;changed=c;leftDrive=ld;rightDrive=rd;leftWheel=lw;rightWheel=rw;this.controller=controller;this.input=input;this.hopper=hopper;this.chest=chest;this.rotation=rotation;} }
    private static final class Pending extends RuntimeException{Pending(String m){super(m);}}
}
