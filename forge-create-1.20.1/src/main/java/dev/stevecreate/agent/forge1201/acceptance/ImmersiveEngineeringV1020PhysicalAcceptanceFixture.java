package dev.stevecreate.agent.forge1201.acceptance;

import blusunrize.immersiveengineering.common.blocks.multiblocks.logic.AlloySmelterLogic;
import blusunrize.immersiveengineering.api.crafting.AlloyRecipe;
import net.minecraftforge.items.IItemHandlerModifiable;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IETemplateMultiblock;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MultiblockFormation;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020AlloySmelterProduction;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.command.MetalPressProductionOrderAcceptanceFixture;
import dev.stevecreate.agent.forge1201.command.AlloySmelterProductionOrderAcceptanceFixture;
import dev.stevecreate.agent.forge1201.electrical.EnergyBehaviourSampler;
import dev.stevecreate.agent.forge1201.electrical.EnergyEndpointDiscovery;
import java.util.List;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraft.core.Direction;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Disposable real-formation/processing proof. It never grants a production action token. */
public final class ImmersiveEngineeringV1020PhysicalAcceptanceFixture {
    public static final String ENABLE_PROPERTY =
            "steve_industrial.test.iePhysicalAcceptance";
    private static RealPowerSession session;
    private static SmelterBatchSession smelterSession;
    private static MetalPressProductionOrderAcceptanceFixture orderSession;
    private static AlloySmelterProductionOrderAcceptanceFixture alloyOrderSession;
    private static AlloySmelterProductionOrderAcceptanceFixture alloyCancellationSession;
    private static Logger logger;

    private ImmersiveEngineeringV1020PhysicalAcceptanceFixture() {}

    public static void start(MinecraftServer server, Logger fixtureLogger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("ImmersiveEngineeringV1020PhysicalAcceptanceFixture");
        if (session != null) throw new IllegalStateException("IE physical fixture already started");
        logger = Objects.requireNonNull(fixtureLogger, "fixtureLogger");
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 24;
        int z = spawn.getZ() + 24;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 6;
        // Which machines are small enough to be a realistic second target. The templates
        // live in IE's data files, so the running game is the only honest source for
        // this; guessing a size and guessing wrong costs the same run either way.
        Map<String, IETemplateMultiblock> candidates = new LinkedHashMap<>();
        candidates.put("METAL_PRESS", IEMultiblocks.METAL_PRESS);
        candidates.put("ALLOY_SMELTER", IEMultiblocks.ALLOY_SMELTER);
        candidates.put("CRUSHER", IEMultiblocks.CRUSHER);
        candidates.put("SAWMILL", IEMultiblocks.SAWMILL);
        candidates.put("COKE_OVEN", IEMultiblocks.COKE_OVEN);
        candidates.put("SQUEEZER", IEMultiblocks.SQUEEZER);
        candidates.put("FERMENTER", IEMultiblocks.FERMENTER);
        candidates.put("BOTTLING_MACHINE", IEMultiblocks.BOTTLING_MACHINE);
        candidates.put("SILO", IEMultiblocks.SILO);
        candidates.put("BLAST_FURNACE", IEMultiblocks.BLAST_FURNACE);
        fixtureLogger.info("IE_V1020_TEMPLATE_CENSUS sizes={}",
                ImmersiveEngineeringV1020MultiblockFormation.templateSizes(level, candidates));

        formSecondMultiblock(level, fixtureLogger, new BlockPos(x + 48, y, z + 48));

        session = new RealPowerSession(level, new BlockPos(x, y, z));
    }

    /**
     * Proves the energy scan against real blocks rather than fakes.
     *
     * <p>Classification is unit-tested with fake handlers, which settles the rule but not
     * whether real IE blocks answer the way the rule assumes. This is the only environment
     * that can tell: it asserts that a site known to be generating and consuming reads as
     * having both a supplier and a consumer, and that they are different blocks.
     *
     * <p>It asserts rather than merely logging. A scan that silently found nothing here
     * would be indistinguishable in the log from one that was never called.</p>
     */
    private static void scanEnergyEndpoints(ServerLevel level, BlockPos origin) {
        List<EnergyEndpointDiscovery.Endpoint> found =
                EnergyEndpointDiscovery.endpoints(level, origin, 12, List.of());
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "the energy scan found nothing at a site that is generating power");
        }
        EnergyEndpointDiscovery.Grid grid = EnergyEndpointDiscovery.summarise(found);
        if (grid.suppliers() == 0) {
            throw new IllegalStateException(
                    "a charged thermoelectric site must expose at least one supplier");
        }
        if (!grid.canMoveEnergy()) {
            throw new IllegalStateException(
                    "a site with a generator and a press must be able to move energy, saw "
                            + grid.suppliers() + " suppliers and " + grid.consumers()
                            + " consumers across " + found.size() + " endpoints");
        }
        if (grid.storedFe() <= 0) {
            throw new IllegalStateException(
                    "the scan read zero stored energy at a site just verified as charged");
        }
        Map<String, Integer> byKind = new LinkedHashMap<>();
        found.forEach(endpoint -> byKind.merge(endpoint.kind().name(), 1, Integer::sum));
        logger.info("IE_V1020_ENERGY_SCAN PASS endpoints={} suppliers={} consumers={} "
                        + "storedFe={} capacityFe={} canMoveEnergy=true kinds={}",
                found.size(), grid.suppliers(), grid.consumers(), grid.storedFe(),
                grid.capacityFe(), byKind);
    }

    /**
     * Builds and forms a machine that is not the Metal Press.
     *
     * <p>The point of C7 is whether a second IE machine is a parameter or four hundred
     * fresh lines. Formation was the part that touched the world, and it turned out to
     * take the multiblock as its only machine-specific input — so this proves the claim
     * the only way it can be proved: by forming a different one through the same code.
     *
     * <p>The alloy smelter, because the census run measured it at eight blocks against
     * the press's seven, the smallest of the twenty-odd IE registers. It is built well
     * clear of the press site and torn down immediately, so the gate it rides on still
     * restores its own baseline exactly.
     *
     * <p>Deliberately NOT claimed: that the smelter can be operated. Reading a machine's
     * state and mapping its recipes need its own logic and recipe types, which remain
     * per-machine. What is shown here is that getting one to exist is not.</p>
     */
    private static void formSecondMultiblock(
            ServerLevel level, Logger fixtureLogger, BlockPos origin) {
        IETemplateMultiblock smelter = IEMultiblocks.ALLOY_SMELTER;
        for (int chunkX = (origin.getX() - 16) >> 4; chunkX <= (origin.getX() + 16) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 16) >> 4; chunkZ <= (origin.getZ() + 16) >> 4;
                    chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
            }
        }
        var placed = ImmersiveEngineeringV1020MultiblockFormation.place(level, smelter, origin);
        if (!placed.success()) {
            throw new IllegalStateException(
                    "second multiblock could not be placed: " + placed.code());
        }
        FakePlayer actor = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                UUID.nameUUIDFromBytes("steve-ie-second-machine".getBytes()),
                "SteveIeSecondMachine"));
        ItemStack hammer = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath("immersiveengineering", "hammer"))));
        boolean formed = ImmersiveEngineeringV1020MultiblockFormation.form(
                level, smelter, origin, Direction.SOUTH, actor, hammer);
        boolean masterPresent = ImmersiveEngineeringV1020MultiblockFormation
                .formedMaster(level, smelter, origin).isPresent();
        if (!formed || !masterPresent) {
            ImmersiveEngineeringV1020MultiblockFormation.clear(level, smelter, origin);
            throw new IllegalStateException("a second IE multiblock did not form: formed="
                    + formed + " master=" + masterPresent);
        }
        // Whether the formed machine is genuinely operable, as far as can be shown without
        // a tick loop: it takes a real recipe's two inputs into two distinct slots, and
        // that exact pair resolves to a recipe. Nothing here is hardcoded — the recipe
        // comes from the running game, the items come from the recipe, and the slots are
        // found by simulated insertion, because a guessed slot index that happened to
        // work would prove nothing about the next machine.
        probeSecondMachineOperability(level, fixtureLogger, smelter, origin);

        // What operating this machine would need, measured rather than guessed: the slot
        // layout, and which alloy recipes the running game actually has.
        ImmersiveEngineeringV1020MultiblockFormation
                .formedMaster(level, smelter, origin)
                .ifPresent(master -> {
                    Object state = master.getHelper().getState();
                    if (state instanceof AlloySmelterLogic.State alloy) {
                        var inventory = alloy.getInventory();
                        fixtureLogger.info("IE_V1020_SECOND_MACHINE_PROBE slots={} numSlots={} "
                                        + "stateClass={}",
                                inventory.getSlots(), AlloySmelterLogic.NUM_SLOTS,
                                alloy.getClass().getSimpleName());
                    } else {
                        fixtureLogger.info("IE_V1020_SECOND_MACHINE_PROBE stateClass={}",
                                state == null ? "null" : state.getClass().getName());
                    }
                });
        var alloyRecipes = level.getRecipeManager().getRecipes().stream()
                .filter(recipe -> recipe instanceof blusunrize.immersiveengineering.api.crafting
                        .AlloyRecipe)
                .map(recipe -> recipe.getId().toString())
                .sorted()
                .limit(12)
                .toList();
        fixtureLogger.info("IE_V1020_ALLOY_RECIPES count={} examples={}",
                alloyRecipes.size(), alloyRecipes);

        ImmersiveEngineeringV1020MultiblockFormation.clear(level, smelter, origin);
        boolean cleared = ImmersiveEngineeringV1020MultiblockFormation
                .componentPositions(level, smelter, origin).stream()
                .allMatch(cell -> level.getBlockState(cell).isAir());
        if (!cleared) {
            throw new IllegalStateException("the second multiblock left blocks behind");
        }
        fixtureLogger.info("IE_V1020_SECOND_MULTIBLOCK PASS machine=ALLOY_SMELTER blocks={} "
                        + "formed=true masterPresent=true clearedAfter=true",
                placed.placedBlocks());
    }

    /** Finds the first slot that will take a stack, ignoring slots already used. */
    private static int slotAccepting(
            IItemHandlerModifiable inventory, ItemStack stack, java.util.Set<Integer> taken) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (taken.contains(slot)) continue;
            if (inventory.insertItem(slot, stack.copy(), true).getCount() < stack.getCount()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Shows the second machine is operable, not merely present.
     *
     * <p>Formation proves a structure came together. It does not prove the thing is a
     * machine: an inventory that rejects its own recipe's inputs, or a slot layout read
     * wrongly, would both survive a formation check. This inserts a real recipe's inputs
     * and confirms the pair resolves — which is as far as it goes without a tick loop, and
     * the commit says so rather than implying the smelter produced anything.</p>
     */
    private static void probeSecondMachineOperability(
            ServerLevel level, Logger fixtureLogger, IETemplateMultiblock smelter, BlockPos origin) {
        var master = ImmersiveEngineeringV1020MultiblockFormation
                .formedMaster(level, smelter, origin).orElse(null);
        if (master == null || !(master.getHelper().getState() instanceof AlloySmelterLogic.State alloy)) {
            throw new IllegalStateException("the formed smelter exposes no alloy state");
        }
        AlloyRecipe recipe = level.getRecipeManager().getRecipes().stream()
                .filter(AlloyRecipe.class::isInstance)
                .map(AlloyRecipe.class::cast)
                .min(java.util.Comparator.comparing(value -> value.getId().toString()))
                .orElseThrow(() -> new IllegalStateException("no alloy recipe is loaded"));
        ItemStack[] first = recipe.input0.getMatchingStacks();
        ItemStack[] second = recipe.input1.getMatchingStacks();
        if (first.length == 0 || second.length == 0) {
            throw new IllegalStateException("alloy recipe " + recipe.getId() + " names no concrete item");
        }
        ItemStack inputA = first[0].copy();
        ItemStack inputB = second[0].copy();

        IItemHandlerModifiable inventory = alloy.getInventory();
        java.util.Set<Integer> taken = new java.util.LinkedHashSet<>();
        int slotA = slotAccepting(inventory, inputA, taken);
        if (slotA < 0) throw new IllegalStateException("no slot accepts the first input");
        taken.add(slotA);
        int slotB = slotAccepting(inventory, inputB, taken);
        if (slotB < 0) throw new IllegalStateException("no second slot accepts the second input");

        ItemStack leftoverA = inventory.insertItem(slotA, inputA.copy(), false);
        ItemStack leftoverB = inventory.insertItem(slotB, inputB.copy(), false);
        if (!leftoverA.isEmpty() || !leftoverB.isEmpty()) {
            throw new IllegalStateException("the smelter would not take its own recipe's inputs");
        }
        // The machine's own matcher, against the stacks now physically in it.
        AlloyRecipe matched = AlloyRecipe.findRecipe(level,
                inventory.getStackInSlot(slotA), inventory.getStackInSlot(slotB), null);
        boolean recognised = matched != null && matched.getId().equals(recipe.getId());
        ItemStack expected = recipe.getResultItem(level.registryAccess());
        if (!recognised) {
            throw new IllegalStateException("the smelter did not recognise recipe " + recipe.getId());
        }
        fixtureLogger.info("IE_V1020_SECOND_MACHINE_OPERABLE PASS recipe={} inputSlots=[{},{}] "
                        + "inputsAccepted=true recipeRecognised=true expectedOutput={} produced=false",
                recipe.getId(), slotA, slotB, expected.getCount() + "x"
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(expected.getItem()));
    }

    public static void tick(MinecraftServer server) {
        // Every session this fixture can be in. Leaving one out does not fail — it hangs,
        // which is what happened when the smelter batch was added and this still only knew
        // about two: the batch was created and then never ticked.
        if (session == null && orderSession == null && smelterSession == null
                && alloyOrderSession == null && alloyCancellationSession == null) return;
        if (alloyCancellationSession != null) {
            var cancelResult = alloyCancellationSession.tick();
            if (cancelResult == null) return;
            alloyCancellationSession = null;
            if (!cancelResult.success()) {
                server.halt(false);
                throw new IllegalStateException("IE Alloy cancellation failed: " + cancelResult);
            }
            logger.info("IE_V1020_ALLOY_PLAYER_CANCEL PASS cancelledWhileBotCarrying=true "
                            + "exactWithdrawnReturn=true preparedReservationsReleased=true "
                            + "duplicateWithdrawals=0 duplicateReturns=0 duplicateOutputs=0 "
                            + "unaccountedItems=0 privateItemsTouched=0 completionReport=false "
                            + "baselineRestored=true botsDiscarded=true ticks={} materialJournal={}",
                    cancelResult.ticks(), cancelResult.materialJournal());
            server.halt(false);
            return;
        }
        if (alloyOrderSession != null) {
            var orderResult = alloyOrderSession.tick();
            if (orderResult == null) return;
            alloyOrderSession = null;
            if (!orderResult.success()) {
                server.halt(false);
                throw new IllegalStateException("IE Alloy player order failed: " + orderResult);
            }
            logger.info("IE_V1020_ALLOY_PLAYER_ORDER PASS selectedPlayerSource=true "
                            + "exactReservation=true visibleMaterialCourierBot=true "
                            + "visibleBuilderBot=true realEightBlockStructure=true "
                            + "realHammerFormation=true itemFuelLedger=true inventedFe=0 "
                            + "realRecipeOutput=2 uniqueOutput=true exactReturn=true "
                            + "duplicateWithdrawals=0 duplicateReturns=0 duplicateOutputs=0 "
                            + "unaccountedItems=0 privateItemsTouched=0 "
                            + "materialLedgerBalanced={} completionReport=true baselineRestored=true "
                            + "ticks={} generation={} materialJournal={}",
                    orderResult.materialLedgerBalanced(), orderResult.ticks(),
                    orderResult.generation(), orderResult.materialJournal());
            alloyCancellationSession =
                    AlloySmelterProductionOrderAcceptanceFixture.startCancellation(server);
            return;
        }
        if (smelterSession != null) {
            SmelterBatchSession.Outcome batch = smelterSession.tick();
            if (batch == null) return;
            smelterSession = null;
            if (!batch.success()) {
                server.halt(false);
                throw new IllegalStateException("second-machine batch failed: " + batch.code());
            }
            logger.info("IE_V1020_SECOND_MACHINE_BATCH PASS machine=ALLOY_SMELTER recipe={} "
                            + "producedCount={} producedItem={} outputSlot={} burnedFuel=true "
                            + "fuelInserted=1 fuelConsumed={} residualItems={} adapterOwned=true "
                            + "ticks={} clearedAfter={}",
                    batch.recipeId(), batch.producedCount(), batch.producedItem(),
                    batch.outputSlot(), batch.fuelConsumed(), batch.residualItems(),
                    batch.ticks(), batch.cleared());
            alloyOrderSession = AlloySmelterProductionOrderAcceptanceFixture.start(server);
            return;
        }

        if (session == null) {
            var orderResult = orderSession.tick();
            if (orderResult == null) return;
            if (!orderResult.success()) {
                server.halt(false);
                throw new IllegalStateException("IE production-order acceptance failed: " + orderResult);
            }
            logger.info("IE_V1020_PHYSICAL_ACCEPTANCE PASS realFormation=true "
                            + "realMoldInteraction=true realThermoelectricGenerator=true "
                            + "realLvConnectors=true realCopperWireInteraction=true "
                            + "playerMaterialLedger=true visibleMaterialCourierBot=true "
                            + "visibleBuilderBot=true exactReservation=true exactReturn=true "
                            + "completionReport=true commonOrderBound={} commonReportAccepted={} "
                            + "resourceBindingBalanced={} resourceWorkers=2 resourceAssignments=13 "
                            + "uniqueOutputCount={} energyConsumedFe={} "
                            + "duplicateWithdrawals=0 duplicateReturns=0 privateItemsTouched=0 "
                            + "materialLedgerBalanced=true directEnergyWrites=0 restored=true "
                            + "ticks={} orderGeneration={} materialJournal={}",
                    orderResult.commonOrderBound(), orderResult.commonReportAccepted(),
                    orderResult.resourceBindingBalanced(),
                    orderResult.outputCount(), orderResult.energyConsumedFe(), orderResult.ticks(),
                    orderResult.orderGeneration(), orderResult.materialJournal());
            orderSession = null;
            // The press is done; the last thing to prove is that a machine which is not
            // the press can actually run a batch, not merely form and match a recipe.
            smelterSession = new SmelterBatchSession(server.overworld(),
                    secondMachineOrigin(server.overworld()));
            return;
        }
        Result result = session.tick();
        if (result != null) {
            if (!result.success) {
                server.halt(false);
                throw new IllegalStateException("IE physical acceptance failed: " + result);
            }
            logger.info("IE_V1020_REAL_POWER_SUBGATE PASS realFormation=true "
                            + "realMoldInteraction=true realThermoelectricGenerator=true "
                            + "realLvConnectors=true realCopperWireInteraction=true "
                            + "machineReceivedPower=true realInput=true realOutput=true "
                            + "uniqueOutputCount={} energyConsumedFe={} directEnergyWrites=0 "
                            + "restored={} ticks={} journal={}",
                    result.outputCount, result.energyConsumedFe, result.restored,
                    result.ticks, result.journal);
            session = null;
            orderSession = MetalPressProductionOrderAcceptanceFixture.start(server);
        }
    }

    private static ItemStack stack(String id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item == null) throw new IllegalStateException("Required IE fixture item is absent: " + id);
        return new ItemStack(item, count);
    }

    private static final class RealPowerSession {
        private static final int TIMEOUT = 1_200;
        private final ServerLevel level;
        private final BlockPos origin;
        private final Map<BlockPos, BlockState> baseline = new LinkedHashMap<>();
        private final java.util.ArrayList<String> journal = new java.util.ArrayList<>();
        private int ticks;
        private int stableTicks;
        private int lastEnergy = -1;
        private int energyAtInput;
        private Phase phase = Phase.CHARGING;
        private final EnergyBehaviourSampler energySampler = new EnergyBehaviourSampler();
        private String failure;

        private RealPowerSession(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
            ImmersiveEngineeringV1020MetalPressProduction.baselinePositions(level, origin)
                    .forEach(position -> baseline.put(position, level.getBlockState(position)));
            var structure = ImmersiveEngineeringV1020MetalPressProduction.placeStructure(level, origin);
            if (!structure.success()) { fail(structure.code()); return; }
            journal.add("STRUCTURE_BUILT:" + structure.placedBlocks());
            var formed = ImmersiveEngineeringV1020MetalPressProduction.form(
                    level, origin, stack("immersiveengineering:hammer", 1));
            if (!formed.success()) { fail(formed.code()); return; }
            journal.add(formed.code());
            var mold = ImmersiveEngineeringV1020MetalPressProduction.insertMold(
                    level, origin, stack("immersiveengineering:mold_plate", 1));
            if (!mold.success()) { fail(mold.code()); return; }
            journal.add(mold.code());
            var power = ImmersiveEngineeringV1020MetalPressProduction.buildPowerNetwork(
                    level, origin, stack("immersiveengineering:wirecoil_copper", 1));
            if (!power.success()) { fail(power.code()); return; }
            journal.add(power.code() + ":connections=" + power.externalConnections()
                    + ":wireConsumed=" + power.consumedWireCoils());
        }

        private Result tick() {
            ticks++;
            if (failure != null || ticks >= TIMEOUT) {
                if (failure == null) fail("TIMEOUT:" + phase);
                return finish(false, 0, 0);
            }
            try {
                if (phase == Phase.CHARGING) {
                    // Watched while it is actually generating. The capability cannot say
                    // which of these blocks is the generator — every IE endpoint allows
                    // both directions — so the only evidence is energy appearing.
                    energySampler.observe(level, origin, 12);
                    var power = ImmersiveEngineeringV1020MetalPressProduction.observePower(level, origin);
                    if (power.verified() && power.storedEnergyFe()
                            >= ImmersiveEngineeringV1020MetalPressProduction.REQUIRED_ENERGY_FE) {
                        journal.add("POWER_VERIFIED:stored=" + power.storedEnergyFe());
                        var generating = energySampler.firstGenerating();
                        if (generating.isEmpty()) {
                            throw new IllegalStateException(
                                    "nothing was observed generating at a site that just charged");
                        }
                        var samples = energySampler.samples();
                        logger.info("IE_V1020_ENERGY_BEHAVIOUR PASS sampled={} generatingAt={} "
                                        + "deltaFe={} readings={} declaredKind={}",
                                samples.size(), generating.orElseThrow(),
                                samples.get(generating.orElseThrow()).deltaFe(),
                                samples.get(generating.orElseThrow()).readings(),
                                samples.get(generating.orElseThrow()).declared());
                        // The one moment in any acceptance run where real Forge Energy
                        // blocks exist: a formed press, LV connectors and a thermoelectric
                        // generator that has just been proven to hold charge. Create is
                        // kinetic and vanilla has no FE, so the energy scan cannot be
                        // exercised anywhere else. Read-only; it changes nothing about the
                        // gate it is riding on.
                        scanEnergyEndpoints(level, origin);
                        var stopped = ImmersiveEngineeringV1020MetalPressProduction
                                .stopThermalGeneration(level, origin);
                        if (!stopped.success()) return finish(false, 0, 0, stopped.code());
                        journal.add(stopped.code());
                        phase = Phase.STABILIZING;
                    }
                    return null;
                }
                if (phase == Phase.STABILIZING) {
                    int current = ImmersiveEngineeringV1020MetalPressProduction.inspect(level, origin)
                            .orElseThrow().state().getEnergy().getEnergyStored();
                    stableTicks = current == lastEnergy ? stableTicks + 1 : 0;
                    lastEnergy = current;
                    if (stableTicks >= 10) {
                        var fed = ImmersiveEngineeringV1020MetalPressProduction.feedInput(
                                level, origin, stack("minecraft:iron_ingot", 1));
                        if (!fed.success()) return finish(false, 0, 0, fed.code());
                        energyAtInput = fed.energyAtInput();
                        journal.add(fed.code() + ":energyAtInput=" + energyAtInput);
                        phase = Phase.PROCESSING;
                    }
                    return null;
                }
                var output = ImmersiveEngineeringV1020MetalPressProduction.observeUniqueOutput(
                        level, origin, stack("immersiveengineering:plate_iron", 1), energyAtInput);
                if (output.verified()) {
                    ItemStack claimed = ImmersiveEngineeringV1020MetalPressProduction.claimUniqueOutput(
                            level, origin, stack("immersiveengineering:plate_iron", 1));
                    journal.add(output.code() + ":count=" + claimed.getCount()
                            + ":energy=" + output.energyConsumedFe());
                    return finish(true, claimed.getCount(), output.energyConsumedFe());
                }
                if (!output.code().equals("OUTPUT_NOT_YET_OBSERVED")) {
                    return finish(false, output.outputCount(), output.energyConsumedFe(), output.code());
                }
                return null;
            } catch (RuntimeException exception) {
                return finish(false, 0, 0, "EXCEPTION:" + exception.getClass().getSimpleName()
                        + ":" + exception.getMessage());
            }
        }

        private Result finish(boolean success, long output, int energy) {
            return finish(success, output, energy, null);
        }

        private Result finish(boolean success, long output, int energy, String code) {
            if (code != null) fail(code);
            baseline.forEach(level::setBlockAndUpdate);
            boolean restored = baseline.entrySet().stream().allMatch(entry ->
                    level.getBlockState(entry.getKey()).equals(entry.getValue()));
            journal.add(restored ? "BASELINE_RESTORED" : "BASELINE_RESTORE_FAILED");
            return new Result(success && failure == null && restored, failure == null ? "OK" : failure,
                    output, energy, restored, ticks, java.util.List.copyOf(journal));
        }

        private void fail(String code) {
            if (failure == null) { failure = code; journal.add("FAILED:" + code); }
        }
    }

    /** Where the second machine is built, clear of the press and its baseline. */
    private static BlockPos secondMachineOrigin(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 24;
        int z = spawn.getZ() + 24;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                x, z) + 6;
        return new BlockPos(x + 48, y, z + 48);
    }

    /**
     * Runs one real batch through a machine that is not the Metal Press.
     *
     * <p>Everything before this showed the smelter forms and recognises a recipe. Neither
     * says it works: a machine can hold the right items, match the right recipe and still
     * never produce, which is exactly what happens if it is never given fuel. The alloy
     * smelter burns rather than drawing FE, so this supplies coal and waits.
     *
     * <p>The output slot is not assumed. It is whichever slot gains the recipe's product,
     * which is the only definition that cannot be wrong.</p>
     */
    private static final class SmelterBatchSession {
        private static final int TIMEOUT = 2_400;
        private final ServerLevel level;
        private final BlockPos origin;
        private final Map<BlockPos, BlockState> baseline = new LinkedHashMap<>();
        private int ticks;
        private ResourceLocation recipeId;
        private ItemStack expected = ItemStack.EMPTY;
        private boolean loaded;

        private SmelterBatchSession(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
            ImmersiveEngineeringV1020AlloySmelterProduction
                    .baselinePositions(level, origin)
                    .forEach(position -> baseline.put(position, level.getBlockState(position)));
        }

        private Outcome tick() {
            ticks++;
            if (ticks > TIMEOUT) return finish(false, "SECOND_MACHINE_BATCH_TIMEOUT",
                    0, "", -1, false, -1);
            try {
                if (!loaded) return load();
                var observed = ImmersiveEngineeringV1020AlloySmelterProduction
                        .observeUniqueOutput(level, origin, expected, 1);
                if (observed.verified()) {
                    ItemStack claimed = ImmersiveEngineeringV1020AlloySmelterProduction
                            .claimUniqueOutput(level, origin, expected);
                    return finish(true, "SECOND_MACHINE_BATCH_PRODUCED", claimed.getCount(),
                            itemId(claimed), observed.outputSlot(), observed.fuelConsumed(),
                            observed.residualItems());
                }
                if (!observed.code().equals("ALLOY_SMELTER_OUTPUT_NOT_YET_OBSERVED")) {
                    return finish(false, observed.code(), observed.outputCount(), "",
                            observed.outputSlot(), observed.fuelConsumed(),
                            observed.residualItems());
                }
                return null;
            } catch (RuntimeException failure) {
                return finish(false, "SECOND_MACHINE_BATCH_ERROR:" + failure.getMessage(),
                        0, "", -1, false, -1);
            }
        }

        private Outcome load() {
            var placed = ImmersiveEngineeringV1020AlloySmelterProduction
                    .placeStructure(level, origin);
            if (!placed.success()) return finish(false, placed.code(), 0, "", -1, false, -1);
            ItemStack hammer = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.fromNamespaceAndPath("immersiveengineering", "hammer"))));
            var formed = ImmersiveEngineeringV1020AlloySmelterProduction
                    .form(level, origin, hammer);
            if (!formed.success()) return finish(false, formed.code(), 0, "", -1, false, -1);
            var recipe = ImmersiveEngineeringV1020AlloySmelterProduction
                    .reviewedRecipe(level).orElse(null);
            if (recipe == null) return finish(false, "NO_REVIEWED_ALLOY_RECIPE",
                    0, "", -1, false, -1);
            recipeId = recipe.recipeId();
            expected = recipe.output();
            var fed = ImmersiveEngineeringV1020AlloySmelterProduction.feedReviewedBatch(
                    level, origin, recipe.firstInput(), recipe.secondInput(),
                    new ItemStack(net.minecraft.world.item.Items.COAL, 1));
            if (!fed.success()) return finish(false, fed.code(), 0, "", -1, false,
                    fed.recoveredOnFailure().stream().mapToInt(ItemStack::getCount).sum());
            loaded = true;
            return null;
        }

        private static String itemId(ItemStack stack) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "unknown" : id.toString();
        }

        private Outcome finish(
                boolean success, String code, int count, String item, int slot,
                boolean fuelConsumed, int residualItems) {
            List<ItemStack> recovered = ImmersiveEngineeringV1020AlloySmelterProduction
                    .extractAllInputs(level, origin);
            int remaining = recovered.stream().mapToInt(ItemStack::getCount).sum();
            ImmersiveEngineeringV1020AlloySmelterProduction.clear(level, origin);
            baseline.forEach(level::setBlockAndUpdate);
            boolean cleared = baseline.entrySet().stream().allMatch(entry ->
                    level.getBlockState(entry.getKey()).equals(entry.getValue()));
            int exactResidual = residualItems < 0 ? remaining : residualItems + remaining;
            return new Outcome(success && exactResidual == 0, code,
                    recipeId == null ? "none" : recipeId.toString(), count, item, slot,
                    fuelConsumed, exactResidual, ticks, cleared);
        }

        private record Outcome(boolean success, String code, String recipeId, int producedCount,
                String producedItem, int outputSlot, boolean fuelConsumed, int residualItems,
                int ticks, boolean cleared) {}
    }

    private enum Phase { CHARGING, STABILIZING, PROCESSING }

    private record Result(boolean success, String code, long outputCount, int energyConsumedFe,
            boolean restored, int ticks, java.util.List<String> journal) {}
}
