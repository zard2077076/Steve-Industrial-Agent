package dev.stevecreate.agent.forge1201.acceptance;

import blusunrize.immersiveengineering.api.wires.Connection;
import blusunrize.immersiveengineering.api.wires.GlobalWireNetwork;
import blusunrize.immersiveengineering.api.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.wires.WireType;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringMultiblockCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringRecipe;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringRecipeCatalogSnapshot;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkVerifier;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.industrial.IndustrialCapability;
import dev.stevecreate.agent.core.industrial.IndustrialProductionPlanner;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020Adapter;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Live IE 10.2.0 recipe/template/read-only adapter proof in an isolated disposable server. */
public final class ImmersiveEngineeringV1020AdapterAcceptanceFixture {
    public static final String ENABLE_PROPERTY = "steve_industrial.test.ieAdapterAcceptance";

    private ImmersiveEngineeringV1020AdapterAcceptanceFixture() {}

    public static void run(MinecraftServer server, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("ImmersiveEngineeringV1020AdapterAcceptanceFixture");
        try {
            ServerLevel level = server.overworld();
            String version = ModList.get().getModContainerById("immersiveengineering")
                    .orElseThrow(() -> new IllegalStateException("IE is not loaded"))
                    .getModInfo().getVersion().toString();
            check(ImmersiveEngineeringV1020Adapter.SUPPORTED_VERSION.equals(version),
                    "Unexpected IE version " + version);
            RuntimeFingerprint runtime = new RuntimeFingerprint(
                    "1.20.1", "forge", FMLLoader.versionInfo().forgeVersion(),
                    Map.of("immersiveengineering", version),
                    ImmersiveEngineeringV1020Adapter.ADAPTER_ID.toString(), 1);
            ImmersiveEngineeringV1020Adapter adapter =
                    new ImmersiveEngineeringV1020Adapter(level, runtime, 0);
            var resourceRegistration = adapter.resourceRecoveryRegistrations().stream()
                    .filter(value -> value.supports(
                            ImmersiveEngineeringV1020Adapter.METAL_PRESS_ORDER_TYPE,
                            ImmersiveEngineeringV1020Adapter.REVIEWED_IRON_PLATE_RECIPE))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "reviewed Metal Press resource registration is absent"));
            check(resourceRegistration.exactEndpointReobservationRequired(),
                    "Metal Press recovery registration did not require exact reobservation");
            check(!resourceRegistration.grantsMutationAuthority(),
                    "Metal Press recovery registration granted mutation authority");
            BlockPos3i spawn = new BlockPos3i(level.getSharedSpawnPos().getX(),
                    level.getSharedSpawnPos().getY(), level.getSharedSpawnPos().getZ());
            ScanRequest request = new ScanRequest(spawn, 1);

            ImmersiveEngineeringRecipeCatalogSnapshot recipes = success(
                    adapter.captureRecipeCatalog(request));
            ImmersiveEngineeringRecipe ironPlate = recipes.recipes().stream()
                    .filter(value -> value.recipeId().toString().equals(
                            "immersiveengineering:metalpress/plate_iron"))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "official iron plate Metal Press recipe is absent"));
            check(ironPlate.energyRequiredFe() == 2_400, "iron plate FE requirement changed");
            check(ironPlate.retainedMoldOrTool().orElseThrow().resourceId().toString()
                            .equals("immersiveengineering:mold_plate"),
                    "iron plate mold identity changed");
            check(ironPlate.inputs().get(0).resourceId().toString().equals("forge:ingots/iron"),
                    "iron plate input tag identity was erased");
            check(ironPlate.outputs().get(0).resourceId().path().contains("plate_iron"),
                    "iron plate output did not resolve to a registered plate");

            ImmersiveEngineeringMultiblockCatalogSnapshot structures = success(
                    adapter.captureMultiblockCatalog(request));
            var metalPress = structures.implementations().stream()
                    .filter(value -> value.implementationId().toString().equals("ie1020:metal_press"))
                    .findFirst().orElseThrow();
            var componentContracts = metalPress.multiblock().orElseThrow().components();
            int components = componentContracts.size();
            check(components == 7,
                    "IE 1.20.1-10.2.0-183 Metal Press template changed: " + componentContracts);
            check(componentContracts.stream().map(value -> value.relativePosition()).distinct().count()
                            == components,
                    "Metal Press template contains overlapping component positions");
            check(metalPress.lifecycle().actions().stream().anyMatch(value ->
                            value.requiredTool().equals(OptionalResource.HAMMER)),
                    "Metal Press lifecycle did not retain the Engineer's Hammer");
            check(adapter.capabilityProfile().readOnlyDiscovery()
                            && !adapter.capabilityProfile().physicalExecutionImplemented()
                            && adapter.capabilityProfile().supports(IndustrialCapability.MULTIBLOCK)
                            && adapter.capabilityProfile().supports(
                                    IndustrialCapability.ELECTRICAL_POWER),
                    "IE capability profile blurred discovery and mutation authority");

            Set<BlockPos3i> buildable = componentContracts.stream().map(value ->
                    new BlockPos3i(spawn.x() + value.relativePosition().x(),
                            spawn.y() + value.relativePosition().y(),
                            spawn.z() + value.relativePosition().z()))
                    .collect(java.util.stream.Collectors.toSet());
            var planning = new IndustrialProductionPlanner().plan(
                    new IndustrialProductionPlanner.Request(
                            ResourceId.parse("acceptance:ie_metal_press"),
                            ironPlate.outputs().get(0).resourceId(), 2,
                            ironPlate.toGenericProcessSpec(metalPress.implementationId()),
                            metalPress, spawn, QuarterTurn.ZERO, buildable,
                            Set.of(ironPlate.retainedMoldOrTool().orElseThrow().resourceId()),
                            ironPlate.energyRequiredFe(), plannerElectricalNetwork(),
                            Optional.empty(), true, 3));
            check(planning instanceof IndustrialProductionPlanner.Ready,
                    "live IE recipe/template did not produce a verified industrial plan: "
                            + planning);
            var metalPressPlan = ((IndustrialProductionPlanner.Ready) planning).plan();
            check(metalPressPlan.batches() == 2
                            && metalPressPlan.requiredEnergyFe() == 4_800
                            && metalPressPlan.botWorkers() == 3
                            && metalPressPlan.components().size() == components
                            && metalPressPlan.executionSteps().size() >= 5,
                    "verified IE Metal Press plan lost batches, FE, components or lifecycle");

            AdapterResult<?> network = adapter.captureElectricalNetwork(request);
            check(network instanceof AdapterResult.Failure<?> failure
                            && failure.code() == AdapterFailureCode.NETWORK_NOT_FOUND,
                    "empty-region wire capture did not fail typed");
            check(success(adapter.capture(request)).components().isEmpty(),
                    "read-only spawn scan unexpectedly found IE blocks");
            int wireEdges = verifyTemporaryLvWireCapture(level, runtime);
            logger.info("IE_V1020_ADAPTER_ACCEPTANCE PASS version={} recipes={} ironEnergyFe={} "
                            + "mold={} multiblockComponents={} emptyNetwork=NETWORK_NOT_FOUND "
                            + "liveLvWireEdges={} plannedMetalPressBatches={} plannedEnergyFe={} "
                            + "plannedBotWorkers={} adapterWorldMutations=0 fixtureSetupRestored=true "
                            + "actionExecutions=0",
                    version, recipes.recipes().size(), ironPlate.energyRequiredFe(),
                    ironPlate.retainedMoldOrTool().orElseThrow().resourceId(), components, wireEdges,
                    metalPressPlan.batches(), metalPressPlan.requiredEnergyFe(),
                    metalPressPlan.botWorkers());
            server.halt(false);
        } catch (RuntimeException failure) {
            server.halt(false);
            throw failure;
        }
    }

    private static final class OptionalResource {
        private static final java.util.Optional<ResourceId> HAMMER = java.util.Optional.of(
                ResourceId.parse("immersiveengineering:hammer"));
    }

    private static int verifyTemporaryLvWireCapture(
            ServerLevel level,
            RuntimeFingerprint runtime) {
        BlockPos spawn = level.getSharedSpawnPos();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                spawn.getX() + 4, spawn.getZ() + 4) + 4;
        BlockPos first = new BlockPos(spawn.getX() + 4, y, spawn.getZ() + 4);
        BlockPos second = first.offset(4, 0, 0);
        Map<BlockPos, BlockState> prior = new LinkedHashMap<>();
        for (BlockPos position : java.util.List.of(first.below(), first,
                second.below(), second)) {
            prior.put(position.immutable(), level.getBlockState(position));
        }
        GlobalWireNetwork global = GlobalWireNetwork.getNetwork(level);
        Connection connection = null;
        try {
            Block connector = ForgeRegistries.BLOCKS.getValue(
                    ResourceLocation.fromNamespaceAndPath(
                            "immersiveengineering", "connector_lv"));
            check(connector != null, "IE LV connector block is absent");
            check(level.setBlockAndUpdate(first.below(), net.minecraft.world.level.block.Blocks.STONE
                            .defaultBlockState())
                            && level.setBlockAndUpdate(second.below(),
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                            && level.setBlockAndUpdate(first, connector.defaultBlockState())
                            && level.setBlockAndUpdate(second, connector.defaultBlockState()),
                    "temporary LV wire fixture could not place connectors");
            check(level.getBlockEntity(first) instanceof IImmersiveConnectable
                            && level.getBlockEntity(second) instanceof IImmersiveConnectable,
                    "temporary LV connector block entities are not wire endpoints");
            IImmersiveConnectable firstConnector =
                    (IImmersiveConnectable) level.getBlockEntity(first);
            IImmersiveConnectable secondConnector =
                    (IImmersiveConnectable) level.getBlockEntity(second);
            global.onConnectorLoad(firstConnector, level);
            global.onConnectorLoad(secondConnector, level);
            var firstPoint = firstConnector.getConnectionPoints().iterator().next();
            var secondPoint = secondConnector.getConnectionPoints().iterator().next();
            connection = new Connection(WireType.COPPER, firstPoint, secondPoint, global);
            global.addConnection(connection);
            ImmersiveEngineeringV1020Adapter adapter =
                    new ImmersiveEngineeringV1020Adapter(level, runtime, 1);
            var graph = success(adapter.captureElectricalNetwork(
                    new ScanRequest(new BlockPos3i(first.getX(), first.getY(), first.getZ()), 8)));
            check(graph.nodes().size() == 2 && graph.edges().size() == 1,
                    "temporary LV graph did not capture two endpoints and one edge");
            check(graph.edges().values().stream().allMatch(value ->
                            value.voltageTier() == VoltageTier.LV
                                    && value.capacityPerTick() > 0
                                    && value.endpointsConnected()
                                    && value.serverObserved()),
                    "temporary LV graph lost tier/capacity/live endpoint evidence");
            check(new ElectricalNetworkVerifier().verify(graph).accepted(),
                    "temporary unloaded LV topology failed the deterministic verifier");
            return graph.edges().size();
        } finally {
            if (connection != null) global.removeConnection(connection);
            prior.forEach(level::setBlockAndUpdate);
            check(prior.entrySet().stream().allMatch(value ->
                            level.getBlockState(value.getKey()).equals(value.getValue())),
                    "temporary LV wire fixture did not restore its exact block states");
        }
    }

    private static ElectricalNetworkGraph plannerElectricalNetwork() {
        ElectricalNetworkNode generator = new ElectricalNetworkNode(
                ResourceId.parse("acceptance:ie_generator"), ElectricalNodeKind.GENERATOR,
                new BlockPos3i(0, 0, 0), VoltageTier.LV, Optional.empty(), Set.of(),
                Set.of(Direction6.EAST), 128, 0, 0, 0, "a".repeat(64), true);
        ElectricalNetworkNode consumer = new ElectricalNetworkNode(
                ResourceId.parse("acceptance:ie_metal_press_consumer"),
                ElectricalNodeKind.CONSUMER, new BlockPos3i(1, 0, 0), VoltageTier.LV,
                Optional.empty(), Set.of(Direction6.WEST), Set.of(), 0, 32,
                0, 0, "b".repeat(64), true);
        ElectricalWireEdge wire = new ElectricalWireEdge(
                ResourceId.parse("acceptance:ie_lv_wire"), generator.nodeId(), consumer.nodeId(),
                VoltageTier.LV, 1, 16, 128, List.of(generator.position(), consumer.position()),
                "c".repeat(64), true, true, true);
        return new ElectricalNetworkGraph(ResourceId.parse("acceptance:ie_planner_network"),
                "isolated-acceptance", ResourceId.parse("minecraft:overworld"),
                List.of(generator, consumer), List.of(wire), 0);
    }

    private static <T> T success(AdapterResult<T> result) {
        if (result instanceof AdapterResult.Success<T> success) return success.value();
        throw new IllegalStateException("IE adapter returned failure: " + result);
    }

    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
    }
}
