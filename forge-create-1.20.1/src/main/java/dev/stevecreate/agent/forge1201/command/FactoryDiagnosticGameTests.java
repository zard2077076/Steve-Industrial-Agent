package dev.stevecreate.agent.forge1201.command;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.diagnostic.FactoryFaultCode;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthCategory;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthStatus;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApproval;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposalService;
import dev.stevecreate.agent.core.diagnostic.FactoryObservationState;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.industrial.MetalPressProductionOrder;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceApprovalSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceExecutionSavedData;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.BaselineBlock;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.StoredOrder;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseRuntimeSavedData;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Physical proof that the loader probes observe real state without changing it. */
@PrefixGameTestTemplate(false)
public final class FactoryDiagnosticGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID PLAYER_ID =
            UUID.fromString("435e320d-9b06-4ab0-9911-7ae99d14feac");
    private static final UUID PROJECT_ID =
            UUID.fromString("4b204059-9510-45e7-95bb-23744d8c2da1");

    private FactoryDiagnosticGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "factory_diagnostic_project_read_only", timeoutTicks = 400)
    public static void projectDiagnosisPreservesWorldContainerAndLedgers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos delivery = helper.absolutePos(new BlockPos(9, 2, 6));
        helper.setBlock(new BlockPos(6, 2, 6), Blocks.CHEST);
        helper.setBlock(new BlockPos(9, 2, 6), Blocks.CHEST);
        Container container = requireContainer(level, chest);
        Container output = requireContainer(level, delivery);
        container.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        for (int slot = 0; slot < output.getContainerSize(); slot++) {
            output.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        container.setChanged();
        output.setChanged();

        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(PLAYER_ID, "SteveFactoryDiagnosticFixture"));
        player.setPos(chest.getX() + 0.5D, chest.getY() + 1.0D, chest.getZ() + 0.5D);
        String planHash = "0".repeat(64);
        ResourceId iron = ResourceId.parse("minecraft:iron_ingot");
        Map<ResourceId, Long> requirements = Map.of(iron, 4L);
        check(PlayerMaterialService.openStandalonePlan(player, PROJECT_ID, requirements,
                planHash, "factory-diagnostic-gametest").success(), "material plan did not open");
        check(PlayerMaterialService.selectStandaloneSource(player, PROJECT_ID,
                cell(chest), Direction.UP).success(), "material source was not selected");
        check(PlayerMaterialService.confirmStandalone(player, PROJECT_ID,
                "world:factory-diagnostic-gametest").success(), "material source was not reserved");

        long now = Instant.now().toEpochMilli();
        ResourceId dimension = ResourceId.parse(level.dimension().location().toString());
        PlayerWorkflowSavedData.ProjectEntry project = new PlayerWorkflowSavedData.ProjectEntry(
                PROJECT_ID, PLAYER_ID, iron, 4, dimension, WorkflowStage.MATERIAL_RESERVED,
                ProductionMode.ONCE, PlayerExecutionMode.DIRECT, LayoutVariant.COMPACT,
                QuarterTurn.ZERO, cell(chest.above()), 1, now, now,
                "MATERIALS_RESERVED", planHash, planHash);
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(level);
        workflow.put(project);

        PlayerMaterialSavedData materials = PlayerMaterialSavedData.forLevel(level);
        PlayerMaterialSavedData.Entry bound = materials.entry(PROJECT_ID).orElseThrow()
                .withLogistics(new PlayerMaterialSavedData.Logistics(cell(chest), cell(delivery)),
                        now, "FACTORY_DIAGNOSTIC_LOGISTICS_BOUND");
        materials.put(bound);
        PlayerMaterialSavedData.Entry materialBefore = materials.entry(PROJECT_ID).orElseThrow();
        PlayerWorkflowSavedData.ProjectEntry projectBefore = workflow.entry(PLAYER_ID).orElseThrow();
        ItemStack itemBefore = container.getItem(0).copy();
        List<ItemStack> outputBefore = snapshot(output);
        BlockState blockBefore = level.getBlockState(chest);
        BlockState outputBlockBefore = level.getBlockState(delivery);

        int commandResult = level.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4),
                "industrialagent diagnose project");
        var first = FactoryDiagnosticService.diagnoseProject(player).orElseThrow();
        var second = FactoryDiagnosticService.diagnoseProject(player).orElseThrow();

        check(commandResult == 1, "registered project diagnosis command failed");
        check(first.equals(second), "the same tick produced a non-deterministic diagnosis");
        check(first.status() == FactoryHealthStatus.FAULTED,
                "the exact full output destination was not presented as a fault");
        check(first.findings().size() == 1
                        && first.findings().get(0).code()
                                == FactoryFaultCode.OUTPUT_CAPACITY_EXHAUSTED,
                "the exact full output destination did not produce its typed fault");
        check(first.unknownCategories().size() == 3
                        && first.unknownCategories().contains(FactoryHealthCategory.ENERGY_SUPPLY),
                "exactly three unprobed categories were not preserved as UNKNOWN");
        check(observation(first, FactoryHealthCategory.OUTPUT_CAPACITY).metrics()
                        .getOrDefault("available", -1L) == 0,
                "the live output-capacity evidence did not measure the exact full chest");
        check(first.worldMutations() == 0 && !first.automaticRepairAuthorized(),
                "diagnosis claimed mutation or repair authority");
        check(blockBefore.equals(level.getBlockState(chest)), "diagnosis changed the source block");
        check(outputBlockBefore.equals(level.getBlockState(delivery)),
                "diagnosis changed the output block");
        check(ItemStack.matches(itemBefore, container.getItem(0)),
                "diagnosis moved or changed source inventory");
        check(matches(outputBefore, output), "diagnosis moved or changed output inventory");
        check(projectBefore.equals(workflow.entry(PLAYER_ID).orElseThrow()),
                "diagnosis changed the player project");
        check(materialBefore.equals(materials.entry(PROJECT_ID).orElseThrow()),
                "diagnosis changed the material ledger");

        workflow.remove(PLAYER_ID);
        materials.remove(PROJECT_ID);
        LOGGER.info("FACTORY_DIAGNOSTIC_PROJECT PASS commandResult=1 state=FAULTED "
                + "fault=OUTPUT_CAPACITY_EXHAUSTED available=0 material=HEALTHY "
                + "reservation=HEALTHY unknown=3 worldMutations=0 containerMutations=0 "
                + "ledgerMutations=0 automaticRepairAuthorized=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "factory_diagnostic_ie_live_read_only", timeoutTicks = 600)
    public static void ieStructureAndFeDiagnosisPreservesWorldAndOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(6, 2, 6));
        List<BaselineBlock> baseline = ImmersiveEngineeringV1020MetalPressProduction
                .baselinePositions(level, origin).stream()
                .map(position -> new BaselineBlock(cell(position),
                        NbtUtils.writeBlockState(level.getBlockState(position))))
                .toList();

        var placed = ImmersiveEngineeringV1020MetalPressProduction.placeStructure(level, origin);
        check(placed.success() && placed.placedBlocks() == 7,
                "the real IE Metal Press structure was not placed");
        check(ImmersiveEngineeringV1020MetalPressProduction.form(
                level, origin, stack("immersiveengineering:hammer")).success(),
                "the real IE Metal Press structure was not formed");
        check(ImmersiveEngineeringV1020MetalPressProduction.insertMold(
                level, origin, stack("immersiveengineering:mold_plate")).success(),
                "the real IE plate mold was not installed");
        var power = ImmersiveEngineeringV1020MetalPressProduction.buildPowerNetwork(
                level, origin, stack("immersiveengineering:wirecoil_copper"));
        check(power.success() && power.externalConnections() == 1,
                "the real IE FE network was not connected");

        UUID orderId = UUID.fromString("1477ddaa-0606-48a8-82b1-e46f11c3cd12");
        UUID projectId = UUID.fromString("2df22b8e-844a-4831-ad49-a5a02436a93c");
        long now = Instant.now().toEpochMilli();
        MetalPressProductionOrder order = new MetalPressProductionOrder(
                orderId, projectId, PLAYER_ID,
                ResourceId.parse(level.dimension().location().toString()), cell(origin),
                cell(origin.offset(-4, 0, 0)), MetalPressProductionService.RECIPE,
                "0".repeat(64), "factory-diagnostic-ie-v1020", "1".repeat(64), 2_400,
                MetalPressOrderStage.POWER_NETWORK_BUILT, Set.of(), 0, 0, 0,
                "POWER_NETWORK_BUILT", now, now, 0, Optional.empty());
        MetalPressOrderSavedData data = MetalPressOrderSavedData.forLevel(level);
        StoredOrder stored = new StoredOrder(order, baseline);
        data.put(stored);

        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(PLAYER_ID, "SteveFactoryDiagnosticIeFixture"));
        Map<BlockPos, BlockState> worldBefore = snapshot(level,
                ImmersiveEngineeringV1020MetalPressProduction.baselinePositions(level, origin));
        StoredOrder orderBefore = data.order(orderId).orElseThrow();

        int commandResult = level.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4),
                "industrialagent diagnose project");
        var first = FactoryDiagnosticService.diagnoseProject(player).orElseThrow();
        var second = FactoryDiagnosticService.diagnoseProject(player).orElseThrow();

        check(commandResult == 1, "registered IE project diagnosis command failed");
        check(first.equals(second), "the same tick produced a non-deterministic IE diagnosis");
        var structure = observation(first, FactoryHealthCategory.STRUCTURE_INTEGRITY);
        var energy = observation(first, FactoryHealthCategory.ENERGY_SUPPLY);
        check(structure.state() == FactoryObservationState.HEALTHY
                        && structure.evidenceCode().equals(
                                "PLAN_STRUCTURE_AND_ORIENTATION_MATCH"),
                "the formed IE multiblock was not observed as structurally healthy");
        check(energy.state() == FactoryObservationState.HEALTHY
                        && energy.evidenceCode().equals("PLAN_FE_CHARGING_PROGRESS")
                        && energy.metrics().getOrDefault("observedConnections", 0L) == 1,
                "the exact live IE FE connection was not observed as charging");
        check(observation(first, FactoryHealthCategory.OUTPUT_CAPACITY).state()
                        == FactoryObservationState.UNKNOWN,
                "world-entity IE output was guessed to have container capacity");
        check(first.worldMutations() == 0 && !first.automaticRepairAuthorized(),
                "IE diagnosis claimed mutation or repair authority");
        check(worldBefore.equals(snapshot(level,
                        ImmersiveEngineeringV1020MetalPressProduction
                                .baselinePositions(level, origin))),
                "IE diagnosis changed the physical structure or FE topology");
        check(orderBefore.equals(data.order(orderId).orElseThrow()),
                "IE diagnosis transitioned or rewrote the production order");

        data.remove(orderId);
        LOGGER.info("FACTORY_DIAGNOSTIC_IE_LIVE PASS commandResult=1 "
                + "structure=HEALTHY structureEvidence=PLAN_STRUCTURE_AND_ORIENTATION_MATCH "
                + "energy=HEALTHY energyEvidence=PLAN_FE_CHARGING_PROGRESS connections=1 "
                + "outputCapacity=UNKNOWN worldMutations=0 orderTransitions=0 "
                + "automaticRepairAuthorized=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "factory_maintenance_ie_power_restore", timeoutTicks = 600)
    public static void approvedOwnedMetalPressPowerRestoreIsBoundedAndOneUse(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(6, 2, 6));
        List<BaselineBlock> baseline = ImmersiveEngineeringV1020MetalPressProduction
                .baselinePositions(level, origin).stream()
                .map(position -> new BaselineBlock(cell(position),
                        NbtUtils.writeBlockState(level.getBlockState(position))))
                .toList();
        var placed = ImmersiveEngineeringV1020MetalPressProduction.placeStructure(level, origin);
        check(placed.success() && placed.placedBlocks() == 7,
                "the maintenance fixture could not place the IE Metal Press");
        check(ImmersiveEngineeringV1020MetalPressProduction.form(
                level, origin, stack("immersiveengineering:hammer")).success(),
                "the maintenance fixture could not form the IE Metal Press");
        check(ImmersiveEngineeringV1020MetalPressProduction.insertMold(
                level, origin, stack("immersiveengineering:mold_plate")).success(),
                "the maintenance fixture could not install the plate mold");
        var power = ImmersiveEngineeringV1020MetalPressProduction.buildPowerNetwork(
                level, origin, stack("immersiveengineering:wirecoil_copper"));
        check(power.success(),
                "the maintenance fixture could not build the bound FE network");
        check(ImmersiveEngineeringV1020MetalPressProduction
                .stopThermalGeneration(level, origin).success(),
                "the maintenance fixture could not create the power fault");

        UUID orderId = UUID.fromString("9f35c9d1-4a7d-4f23-8128-29cb8c33bba1");
        UUID projectId = UUID.fromString("38dc9ff9-2d12-4cc2-a8fd-57a49f687f98");
        long now = Instant.now().toEpochMilli();
        MetalPressProductionOrder order = new MetalPressProductionOrder(
                orderId, projectId, PLAYER_ID,
                ResourceId.parse(level.dimension().location().toString()), cell(origin),
                cell(origin.offset(-4, 0, 0)), MetalPressProductionService.RECIPE,
                "2".repeat(64), "factory-maintenance-ie-v1020", "3".repeat(64), 2_400,
                MetalPressOrderStage.POWER_VERIFIED, Set.of(), 0, 0, 0,
                "POWER_VERIFIED", now, now, 0, Optional.empty());
        MetalPressOrderSavedData data = MetalPressOrderSavedData.forLevel(level);
        data.put(new StoredOrder(order, baseline));
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(PLAYER_ID, "SteveFactoryMaintenanceFixture"));
        List<ItemStack> inventoryBefore = player.getInventory().items.stream()
                .map(ItemStack::copy).toList();

        var diagnosis = FactoryDiagnosticService.diagnoseOwnedMetalPressPower(player,
                ResourceId.parse("steve_industrial:ie_order/"
                        + orderId.toString().replace("-", ""))).orElseThrow();
        check(diagnosis.status() == FactoryHealthStatus.FAULTED
                        && diagnosis.findings().size() == 1
                        && diagnosis.findings().get(0).evidenceCode()
                                .equals("PLAN_FE_POWER_INSUFFICIENT"),
                "the missing thermal source pair was not diagnosed as one exact FE fault");
        var commands = level.getServer().getCommands();
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance propose") == 1,
                "the player command could not persist a maintenance proposal");
        var proposal = FactoryMaintenanceService.currentProposal(player).orElseThrow();
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance status") == 1,
                "the player could not inspect the authority-free proposal");
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance approve") == 1,
                "the named player command did not record approval");
        check(!ImmersiveEngineeringV1020MetalPressProduction
                        .thermalGenerationActive(level, origin),
                "approval changed the world before explicit execution");
        level.setBlockAndUpdate(power.layout().coldSource(), Blocks.STONE.defaultBlockState());
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance execute") == 0
                        && level.getBlockState(power.layout().coldSource()).is(Blocks.STONE)
                        && FactoryMaintenanceApprovalSavedData.forLevel(level).ledger().approvals()
                                .get(proposal.proposalId()).state()
                                        == FactoryMaintenanceApproval.State.APPROVED,
                "site drift changed the world or consumed approval before mutation preflight");
        level.setBlockAndUpdate(power.layout().coldSource(), Blocks.AIR.defaultBlockState());

        // Recreate the exact durable crash window: authority was consumed and PREPARED
        // reached disk, but the server stopped after writing only the first source cell.
        var approvalData = FactoryMaintenanceApprovalSavedData.forLevel(level);
        var approvalLedger = approvalData.ledger();
        var consumed = approvalLedger.consume(proposal,
                FactoryDiagnosticService.diagnoseOwnedMetalPressPower(player,
                        proposal.subjectId()).orElseThrow(), player.getUUID(),
                level.getGameTime());
        check(consumed.success(), "the fixture could not create consumed maintenance authority");
        approvalData.put(approvalLedger);
        FactoryMaintenanceExecutionSavedData executionData =
                FactoryMaintenanceExecutionSavedData.forLevel(level);
        executionData.prepare(proposal.proposalId(), player.getUUID(),
                proposal.diagnosticHash(), level.getGameTime());
        level.getDataStorage().save();
        level.setBlockAndUpdate(power.layout().coldSource(), Blocks.BLUE_ICE.defaultBlockState());
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance status") == 1
                        && commands.performPrefixedCommand(player.createCommandSourceStack(),
                                "steveagent maintenance dismiss") == 0
                        && FactoryMaintenanceService.currentProposal(player).isPresent(),
                "an in-flight transaction was hidden or allowed to lose its recovery proposal");
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance execute") == 1
                        && ImmersiveEngineeringV1020MetalPressProduction
                                .thermalGenerationActive(level, origin),
                "the explicit player execution command did not recover the partial transaction");
        check(FactoryMaintenanceApprovalSavedData.forLevel(level).ledger().approvals()
                        .get(proposal.proposalId()).state()
                                == FactoryMaintenanceApproval.State.CONSUMED,
                "the physical action did not durably consume its one-use approval");
        check(executionData.entry(proposal.proposalId()).orElseThrow().state()
                        == FactoryMaintenanceExecutionSavedData.State.VERIFIED,
                "the recovered physical action did not reach VERIFIED");
        check(player.getInventory().items.equals(inventoryBefore),
                "power maintenance touched the player's private inventory");

        check(ImmersiveEngineeringV1020MetalPressProduction
                .stopThermalGeneration(level, origin).success(),
                "the replay fixture could not remove the thermal sources");
        check(commands.performPrefixedCommand(player.createCommandSourceStack(),
                        "steveagent maintenance execute") == 0
                        && ImmersiveEngineeringV1020MetalPressProduction
                                .thermalGenerationSourceCount(level, origin) == 0,
                "replay restored power or failed to expose consumed authority");

        data.remove(orderId);
        LOGGER.info("FACTORY_MAINTENANCE_IE_POWER PASS proposal=approved-once "
                + "transport=player-command-three-step freshDiagnosis=true "
                + "action=RESTORE_POWER exactSourceCells=2 "
                + "postDiagnosis=HEALTHY approval=CONSUMED replay=REFUSED "
                + "siteDrift=REFUSED_WITHOUT_CONSUMPTION duplicateMutations=0 "
                + "privateItemsTouched=0 nearbyScan=false rewiring=false "
                + "crashWindow=PREPARED_PARTIAL recoveryMutations=1 execution=VERIFIED");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "factory_diagnostic_warehouse_read_only", timeoutTicks = 400)
    public static void warehouseDiagnosisPreservesOrderAndRuntimeRegistration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos site = helper.absolutePos(new BlockPos(12, 2, 6));
        ResourceId warehouseId = ResourceId.parse("steve_industrial:warehouse/"
                + centre.getX() + "_" + centre.getY() + "_" + centre.getZ());
        ResourceId orderId = ResourceId.parse("steve_industrial:order/factory_diagnostic");
        ResourceId graph = ResourceId.parse("steve_industrial:test/factory_diagnostic");
        ResourceId target = ResourceId.parse("minecraft:iron_ingot");
        ProductionOrder order = new ProductionOrder(orderId,
                ResourceId.parse("steve_industrial:owner/factory_diagnostic"), warehouseId,
                new WarehouseResourceKey(GenericResourceType.ITEM, target,
                        WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                4, 1, 1, graph, Set.of(graph),
                Set.of(ResourceId.parse("steve_industrial:adapter/create_v606")),
                List.of(ResourceId.parse("steve_industrial:site/factory_diagnostic")),
                3, 1, 0, Optional.empty(), ProductionOrderStatus.PAUSED,
                "MOLD_INSTALLED_NOT_POWERED", 1);
        WarehouseRuntimeSavedData.Registration registration =
                new WarehouseRuntimeSavedData.Registration(warehouseId, cell(centre), 4,
                        cell(site), graph, ExecutionMode.DIRECT, Optional.empty());
        WarehouseOrderSavedData orders = WarehouseOrderSavedData.forLevel(level);
        WarehouseRuntimeSavedData runtimes = WarehouseRuntimeSavedData.forLevel(level);
        orders.put(order);
        runtimes.put(registration);
        BlockState centreBefore = level.getBlockState(centre);

        int commandResult = level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withLevel(level).withPermission(4),
                "industrialagent diagnose warehouse " + centre.getX() + " "
                        + centre.getY() + " " + centre.getZ());
        var first = FactoryDiagnosticService.diagnoseWarehouse(level, centre).orElseThrow();
        var second = FactoryDiagnosticService.diagnoseWarehouse(level, centre).orElseThrow();

        check(commandResult == 1, "registered warehouse diagnosis command failed");
        check(first.equals(second), "warehouse diagnosis was not deterministic in one tick");
        check(first.status() == FactoryHealthStatus.FAULTED
                        && first.findings().size() == 1
                        && first.findings().get(0).code() == FactoryFaultCode.ENERGY_INSUFFICIENT,
                "typed warehouse power failure was not diagnosed exactly");
        check(first.worldMutations() == 0 && !first.automaticRepairAuthorized(),
                "warehouse diagnosis claimed mutation or repair authority");
        check(order.equals(orders.order(orderId).orElseThrow()),
                "warehouse diagnosis transitioned or rewrote the order");
        check(registration.equals(runtimes.registrations().get(warehouseId)),
                "warehouse diagnosis rewrote the runtime registration");
        check(centreBefore.equals(level.getBlockState(centre)),
                "warehouse diagnosis changed the world");

        orders.remove(orderId);
        runtimes.remove(warehouseId);
        LOGGER.info("FACTORY_DIAGNOSTIC_WAREHOUSE PASS commandResult=1 state=FAULTED "
                + "fault=ENERGY_INSUFFICIENT evidence=MOLD_INSTALLED_NOT_POWERED "
                + "worldMutations=0 orderTransitions=0 runtimeMutations=0 "
                + "automaticRepairAuthorized=false");
        helper.succeed();
    }

    private static Container requireContainer(ServerLevel level, BlockPos position) {
        if (level.getBlockEntity(position) instanceof Container container) return container;
        throw new GameTestAssertException("expected container at " + position.toShortString());
    }

    private static List<ItemStack> snapshot(Container container) {
        java.util.ArrayList<ItemStack> result = new java.util.ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            result.add(container.getItem(slot).copy());
        }
        return List.copyOf(result);
    }

    private static boolean matches(List<ItemStack> expected, Container actual) {
        if (expected.size() != actual.getContainerSize()) return false;
        for (int slot = 0; slot < expected.size(); slot++) {
            if (!ItemStack.matches(expected.get(slot), actual.getItem(slot))) return false;
        }
        return true;
    }

    private static Map<BlockPos, BlockState> snapshot(
            ServerLevel level, List<BlockPos> positions) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        positions.forEach(position -> result.put(position, level.getBlockState(position)));
        return Map.copyOf(result);
    }

    private static dev.stevecreate.agent.core.diagnostic.FactoryHealthObservation observation(
            dev.stevecreate.agent.core.diagnostic.FactoryHealthReport report,
            FactoryHealthCategory category) {
        return report.observations().stream().filter(value -> value.category() == category)
                .findFirst().orElseThrow();
    }

    private static ItemStack stack(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item == null || item == Items.AIR) {
            throw new GameTestAssertException("missing fixture item " + id);
        }
        return new ItemStack(item);
    }

    private static BlockPos3i cell(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new GameTestAssertException(detail);
    }
}
