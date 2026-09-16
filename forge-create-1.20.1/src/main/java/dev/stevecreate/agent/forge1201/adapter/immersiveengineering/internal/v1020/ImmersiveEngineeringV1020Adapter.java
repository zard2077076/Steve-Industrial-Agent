package dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020;

import blusunrize.immersiveengineering.api.crafting.IERecipeTypes;
import blusunrize.immersiveengineering.api.crafting.MetalPressRecipe;
import blusunrize.immersiveengineering.api.multiblocks.TemplateMultiblock;
import blusunrize.immersiveengineering.api.wires.Connection;
import blusunrize.immersiveengineering.api.wires.ConnectionPoint;
import blusunrize.immersiveengineering.api.wires.GlobalWireNetwork;
import blusunrize.immersiveengineering.api.wires.localhandlers.EnergyTransferHandler.IEnergyWire;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import com.google.gson.JsonElement;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.ObservedComponent;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.adapter.api.WorldSnapshot;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringActionEvidence;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringActionRequest;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringMultiblockCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringRecipe;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringResourceRegistration;
import dev.stevecreate.agent.adapter.api.ie.ImmersiveEngineeringVersionAdapter;
import dev.stevecreate.agent.core.binding.ImplementationPortContract;
import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.binding.PortTemporalSemantics;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.industrial.IndustrialActionContract;
import dev.stevecreate.agent.core.industrial.IndustrialActionType;
import dev.stevecreate.agent.core.industrial.IndustrialCapability;
import dev.stevecreate.agent.core.industrial.IndustrialCapabilityProfile;
import dev.stevecreate.agent.core.industrial.IndustrialLifecycleContract;
import dev.stevecreate.agent.core.industrial.IndustrialPhysicalDescriptor;
import dev.stevecreate.agent.core.industrial.MultiblockComponentContract;
import dev.stevecreate.agent.core.industrial.MultiblockStructureContract;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Strict IE 10.2.0-183 adapter. All direct IE symbols remain inside this versioned package. */
public final class ImmersiveEngineeringV1020Adapter
        implements ImmersiveEngineeringVersionAdapter {
    // One definition, in a class that pulls no IE symbol with it. Callers that already
    // hold an IE runtime keep naming them here; callers that must ask first name
    // ImmersiveEngineeringOrderIdentities directly and stay loadable without the mod.
    public static final String MOD_ID = ImmersiveEngineeringOrderIdentities.MOD_ID;
    public static final String SUPPORTED_VERSION =
            ImmersiveEngineeringOrderIdentities.SUPPORTED_VERSION;
    public static final ResourceId ADAPTER_ID = ImmersiveEngineeringOrderIdentities.ADAPTER_ID;
    private static final ResourceId METAL_PRESS_TYPE =
            ImmersiveEngineeringOrderIdentities.METAL_PRESS_TYPE;
    public static final ResourceId METAL_PRESS_ORDER_TYPE =
            ImmersiveEngineeringOrderIdentities.METAL_PRESS_ORDER_TYPE;
    public static final ResourceId REVIEWED_IRON_PLATE_RECIPE =
            ImmersiveEngineeringOrderIdentities.REVIEWED_IRON_PLATE_RECIPE;
    private static final ResourceId HAMMER = ResourceId.parse("immersiveengineering:hammer");
    private final ServerLevel level;
    private final RuntimeFingerprint runtime;
    private final long reloadGeneration;

    public ImmersiveEngineeringV1020Adapter(
            ServerLevel level,
            RuntimeFingerprint runtime,
            long reloadGeneration) {
        this.level = Objects.requireNonNull(level, "level");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (reloadGeneration < 0) throw new IllegalArgumentException("reloadGeneration is negative");
        this.reloadGeneration = reloadGeneration;
    }

    @Override
    public ResourceId adapterId() { return ADAPTER_ID; }

    @Override
    public String targetModId() { return MOD_ID; }

    @Override
    public RuntimeFingerprint runtime() { return runtime; }

    @Override
    public String supportedImmersiveEngineeringVersion() { return SUPPORTED_VERSION; }

    @Override
    public IndustrialCapabilityProfile capabilityProfile() {
        return new IndustrialCapabilityProfile(ADAPTER_ID, Set.of(
                IndustrialCapability.ITEM_PROCESSING,
                IndustrialCapability.ELECTRICAL_POWER,
                IndustrialCapability.MULTIBLOCK,
                IndustrialCapability.LOGISTICS), runtime.canonicalIdentity(), true, false,
                "IE 10.2.0 recipe/template/FE discovery and planning are implemented; "
                        + "physical mutation remains gated and execute() refuses");
    }

    @Override
    public List<ImmersiveEngineeringResourceRegistration> resourceRecoveryRegistrations() {
        return List.of(metalPressResourceRegistration());
    }

    public static ImmersiveEngineeringResourceRegistration metalPressResourceRegistration() {
        return new ImmersiveEngineeringResourceRegistration(
                METAL_PRESS_ORDER_TYPE, METAL_PRESS_TYPE, List.of(REVIEWED_IRON_PLATE_RECIPE),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ELECTRICAL_ENERGY),
                Set.of(IndustrialCapability.ITEM_PROCESSING,
                        IndustrialCapability.ELECTRICAL_POWER,
                        IndustrialCapability.MULTIBLOCK, IndustrialCapability.LOGISTICS),
                true, false);
    }

    @Override
    public AdapterResult<WorldSnapshot> capture(ScanRequest request) {
        AdapterResult.Failure<WorldSnapshot> unavailable = unavailable();
        if (unavailable != null) return unavailable;
        if (!level.getServer().isSameThread()) return failure(
                AdapterFailureCode.WRONG_THREAD, "IE scan must run on the server thread");
        ArrayList<ObservedComponent> components = new ArrayList<>();
        BlockPos center = block(request.center());
        for (BlockPos position : BlockPos.betweenClosed(
                center.offset(-request.radius(), -request.radius(), -request.radius()),
                center.offset(request.radius(), request.radius(), request.radius()))) {
            if (!level.hasChunkAt(position)) return failure(
                    AdapterFailureCode.CHUNK_NOT_LOADED, "IE scan intersects an unloaded chunk");
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(
                    level.getBlockState(position).getBlock());
            if (blockId != null && MOD_ID.equals(blockId.getNamespace())) {
                components.add(new ObservedComponent(ResourceId.parse(blockId.toString()),
                        position(position), state(level.getBlockState(position))));
            }
        }
        components.sort(Comparator.comparing(ObservedComponent::position,
                Comparator.comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z)));
        return new AdapterResult.Success<>(new WorldSnapshot(
                UUID.randomUUID(), level.getGameTime(), runtime, request.center(),
                request.radius(), components));
    }

    @Override
    public AdapterResult<ImmersiveEngineeringRecipeCatalogSnapshot> captureRecipeCatalog(
            ScanRequest request) {
        AdapterResult.Failure<ImmersiveEngineeringRecipeCatalogSnapshot> unavailable = unavailable();
        if (unavailable != null) return unavailable;
        if (!level.getServer().isSameThread()) return failure(
                AdapterFailureCode.WRONG_THREAD, "IE recipes must be read on the server thread");
        try {
            List<ImmersiveEngineeringRecipe> recipes = level.getRecipeManager()
                    .getAllRecipesFor(IERecipeTypes.METAL_PRESS.get()).stream()
                    .map(this::mapRecipe).sorted(Comparator.comparing(
                            value -> value.recipeId().toString())).toList();
            String fingerprint = sha256(recipes.stream().map(value -> value.recipeId() + "|"
                    + value.inputs() + "|" + value.outputs() + "|"
                    + value.retainedMoldOrTool() + "|" + value.energyRequiredFe())
                    .reduce("", (left, right) -> left + right + "\n"));
            return new AdapterResult.Success<>(new ImmersiveEngineeringRecipeCatalogSnapshot(
                    runtime, reloadGeneration, fingerprint, recipes));
        } catch (RuntimeException failure) {
            return failure(AdapterFailureCode.INDUSTRIAL_API_FAILURE,
                    "IE Metal Press recipe capture failed closed: " + failure.getMessage());
        }
    }

    @Override
    public AdapterResult<ImmersiveEngineeringMultiblockCatalogSnapshot> captureMultiblockCatalog(
            ScanRequest request) {
        AdapterResult.Failure<ImmersiveEngineeringMultiblockCatalogSnapshot> unavailable = unavailable();
        if (unavailable != null) return unavailable;
        if (!level.getServer().isSameThread()) return failure(
                AdapterFailureCode.WRONG_THREAD, "IE multiblocks must be read on the server thread");
        try {
            IndustrialPhysicalDescriptor metalPress = metalPressDescriptor(
                    IEMultiblocks.METAL_PRESS);
            String fingerprint = metalPress.multiblock().orElseThrow().definitionFingerprint();
            return new AdapterResult.Success<>(new ImmersiveEngineeringMultiblockCatalogSnapshot(
                    runtime, reloadGeneration, fingerprint, List.of(metalPress)));
        } catch (RuntimeException failure) {
            return failure(AdapterFailureCode.MULTIBLOCK_NOT_FOUND,
                    "IE Metal Press structure capture failed closed: " + failure.getMessage());
        }
    }

    @Override
    public AdapterResult<ElectricalNetworkGraph> captureElectricalNetwork(ScanRequest request) {
        AdapterResult.Failure<ElectricalNetworkGraph> unavailable = unavailable();
        if (unavailable != null) return unavailable;
        if (!level.getServer().isSameThread()) return failure(
                AdapterFailureCode.WRONG_THREAD, "IE wire capture must run on the server thread");
        try {
            return captureElectricalNetworkChecked(request);
        } catch (RuntimeException failure) {
            return failure(AdapterFailureCode.INDUSTRIAL_API_FAILURE,
                    "IE wire-network capture failed closed: " + failure.getMessage());
        }
    }

    private AdapterResult<ElectricalNetworkGraph> captureElectricalNetworkChecked(
            ScanRequest request) {
        BlockPos center = block(request.center());
        int minimumChunkX = (center.getX() - request.radius()) >> 4;
        int maximumChunkX = (center.getX() + request.radius()) >> 4;
        int minimumChunkZ = (center.getZ() - request.radius()) >> 4;
        int maximumChunkZ = (center.getZ() + request.radius()) >> 4;
        GlobalWireNetwork global = GlobalWireNetwork.getNetwork(level);
        LinkedHashSet<ConnectionPoint> boundedPoints = new LinkedHashSet<>();
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                if (!level.hasChunk(chunkX, chunkZ)) return failure(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "IE wire scan intersects an unloaded chunk " + chunk);
                global.getAllConnectorsIn(chunk).stream()
                        .filter(point -> within(request, point.position()))
                        .sorted().forEach(boundedPoints::add);
            }
        }
        LinkedHashSet<Connection> connections = new LinkedHashSet<>();
        for (ConnectionPoint point : boundedPoints) {
            var local = global.getNullableLocalNet(point);
            if (local == null || !local.isValid(point)) continue;
            local.getConnections(point).stream()
                    .filter(value -> !value.isInternal() && value.type instanceof IEnergyWire)
                    .sorted(Comparator.comparing(ImmersiveEngineeringV1020Adapter::connectionKey))
                    .forEach(connections::add);
        }
        if (connections.isEmpty()) return failure(AdapterFailureCode.NETWORK_NOT_FOUND,
                "No live IE energy-wire connection intersects the bounded region");
        if (connections.size() > ElectricalNetworkGraph.MAX_EDGES) {
            throw new IllegalStateException("IE wire edge count exceeds the bounded graph");
        }
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        connections.forEach(value -> {
            positions.add(value.getEndA().position().immutable());
            positions.add(value.getEndB().position().immutable());
        });
        if (positions.size() > ElectricalNetworkGraph.MAX_NODES) {
            throw new IllegalStateException("IE connector count exceeds the bounded graph");
        }
        List<BlockPos> orderedPositions = positions.stream().sorted(Comparator
                .comparingInt((BlockPos value) -> value.getX())
                .thenComparingInt(value -> value.getY())
                .thenComparingInt(value -> value.getZ())).toList();
        Map<BlockPos, ResourceId> nodeIds = new LinkedHashMap<>();
        Map<BlockPos, Set<VoltageTier>> tiers = new LinkedHashMap<>();
        for (int index = 0; index < orderedPositions.size(); index++) {
            BlockPos position = orderedPositions.get(index);
            nodeIds.put(position, id("ie1020:wire_node_" + String.format("%04d", index)));
            tiers.put(position, EnumSet.noneOf(VoltageTier.class));
        }
        connections.forEach(value -> {
            VoltageTier tier = tier(value);
            tiers.get(value.getEndA().position()).add(tier);
            tiers.get(value.getEndB().position()).add(tier);
        });
        List<ElectricalNetworkNode> nodes = orderedPositions.stream()
                .map(value -> electricalNode(value, nodeIds.get(value), tiers.get(value)))
                .toList();
        ArrayList<ElectricalWireEdge> edges = new ArrayList<>();
        List<Connection> orderedConnections = connections.stream()
                .sorted(Comparator.comparing(ImmersiveEngineeringV1020Adapter::connectionKey))
                .toList();
        for (int index = 0; index < orderedConnections.size(); index++) {
            Connection connection = orderedConnections.get(index);
            BlockPos from = connection.getEndA().position();
            BlockPos to = connection.getEndB().position();
            if (from.equals(to)) continue;
            List<BlockPos3i> envelope = collisionEnvelope(connection);
            boolean collisionFree = envelope.stream().allMatch(cell -> {
                BlockPos position = block(cell);
                return position.equals(from) || position.equals(to)
                        || level.getBlockState(position).getCollisionShape(level, position).isEmpty();
            });
            IEnergyWire energyWire = (IEnergyWire) connection.type;
            edges.add(new ElectricalWireEdge(
                    id("ie1020:wire_edge_" + String.format("%04d", index)),
                    nodeIds.get(from), nodeIds.get(to), tier(connection),
                    Math.max(1, (int) Math.ceil(connection.getLength())),
                    connection.type.getMaxLength(), energyWire.getTransferRate(), envelope,
                    sha256(connection.toNBT().toString()), true, collisionFree, true));
        }
        if (edges.isEmpty()) return failure(AdapterFailureCode.NETWORK_NOT_FOUND,
                "Bounded IE wire scan contained no external energy edge");
        return new AdapterResult.Success<>(new ElectricalNetworkGraph(
                id("ie1020:wire_graph_" + sha256(request.center() + "|" + request.radius())
                        .substring(0, 20)),
                runtime.canonicalIdentity(),
                id(level.dimension().location().toString()), nodes, edges, reloadGeneration));
    }

    @Override
    public AdapterResult<ImmersiveEngineeringActionEvidence> execute(
            ImmersiveEngineeringActionRequest request) {
        AdapterResult.Failure<ImmersiveEngineeringActionEvidence> unavailable = unavailable();
        if (unavailable != null) return unavailable;
        return failure(AdapterFailureCode.ACTION_REFUSED,
                "IE mutation requires the physical Metal Press execution gate");
    }

    private ImmersiveEngineeringRecipe mapRecipe(MetalPressRecipe recipe) {
        ProcessResource input = new ProcessResource(ingredientIdentity(recipe.input.serialize(),
                recipe.input.getMatchingStacks()), GenericResourceType.ITEM,
                recipe.input.getCount());
        ItemStack outputStack = recipe.output.get();
        ResourceId output = itemId(outputStack);
        ResourceId mold = itemId(new ItemStack(recipe.mold));
        String fingerprint = sha256(recipe.getId() + "|" + recipe.input.serialize() + "|"
                + output + "|" + outputStack.getCount() + "|" + mold + "|"
                + recipe.getTotalProcessEnergy());
        return new ImmersiveEngineeringRecipe(
                ResourceId.parse(recipe.getId().toString()), METAL_PRESS_TYPE,
                List.of(input),
                List.of(new ProcessResource(output, GenericResourceType.ITEM,
                        outputStack.getCount())),
                List.of(), Optional.of(new ProcessResource(mold, GenericResourceType.ITEM, 1)),
                recipe.getTotalProcessEnergy(), true, fingerprint,
                List.of("input=" + recipe.input.serialize()));
    }

    private IndustrialPhysicalDescriptor metalPressDescriptor(TemplateMultiblock multiblock) {
        List<MultiblockComponentContract> components = new ArrayList<>();
        int index = 0;
        for (var block : multiblock.getStructure(level)) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block.state().getBlock());
            if (id == null || block.state().isAir()) continue;
            components.add(new MultiblockComponentContract(
                    ResourceId.parse("ie1020:metal_press_component_" + index++),
                    ResourceId.parse(id.toString()), position(block.pos()),
                    state(block.state()), false));
        }
        if (components.isEmpty()) throw new IllegalStateException("Metal Press template is empty");
        String definition = sha256(components.toString() + "|"
                + multiblock.getUniqueName() + "|" + multiblock.getTriggerOffset());
        MultiblockStructureContract structure = new MultiblockStructureContract(
                ResourceId.parse(multiblock.getUniqueName().toString()), components,
                Set.of(QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90,
                        QuarterTurn.CLOCKWISE_180, QuarterTurn.CLOCKWISE_270),
                multiblock.canBeMirrored(), id("ie1020:form_metal_press"),
                id("ie1020:metal_press_formed"), definition);
        IndustrialLifecycleContract lifecycle = new IndustrialLifecycleContract(
                Set.of(id("ie1020:structure_complete"), id("ie1020:mold_inserted"),
                        id("ie1020:electrical_energy_available")),
                Set.of(id("ie1020:formed"), id("ie1020:active"),
                        id("ie1020:energy_stored"), id("ie1020:input"), id("ie1020:output")),
                List.of(
                        action("place_metal_press", IndustrialActionType.PLACE_BLOCK,
                                Optional.empty(), Set.of(GenericResourceType.ITEM), true),
                        action("form_metal_press", IndustrialActionType.FORM_MULTIBLOCK,
                                Optional.of(HAMMER), Set.of(), true),
                        action("connect_metal_press", IndustrialActionType.CONNECT_RESOURCE,
                                Optional.of(id("immersiveengineering:wirecoil_copper")),
                                Set.of(GenericResourceType.ELECTRICAL_ENERGY), true),
                        action("insert_mold", IndustrialActionType.INSERT_RESOURCE,
                                Optional.of(id("immersiveengineering:mold_plate")),
                                Set.of(GenericResourceType.ITEM), true),
                        action("observe_metal_press", IndustrialActionType.OBSERVE_STATUS,
                                Optional.empty(), Set.of(GenericResourceType.ITEM,
                                        GenericResourceType.ELECTRICAL_ENERGY), true),
                        action("disconnect_metal_press", IndustrialActionType.DISCONNECT_RESOURCE,
                                Optional.of(id("immersiveengineering:wirecutter")),
                                Set.of(GenericResourceType.ELECTRICAL_ENERGY), true),
                        action("dismantle_metal_press", IndustrialActionType.DISMANTLE,
                                Optional.of(HAMMER), Set.of(GenericResourceType.ITEM), true)),
                true, true,
                "reload requires exact template, formed-state, mold, wire-network and material-ledger reconciliation");
        return new IndustrialPhysicalDescriptor(id("ie1020:metal_press"), ADAPTER_ID,
                Optional.empty(), List.of(
                        port("item_input", ImplementationPortRole.ITEM_INPUT, 1),
                        port("item_output", ImplementationPortRole.ITEM_OUTPUT, 1),
                        port("electrical_input", ImplementationPortRole.ELECTRICAL_ENERGY_INPUT, 2_400)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ELECTRICAL_ENERGY),
                Optional.of(structure), lifecycle, runtime.canonicalIdentity(), 1);
    }

    private static IndustrialActionContract action(
            String name,
            IndustrialActionType type,
            Optional<ResourceId> tool,
            Set<GenericResourceType> resources,
            boolean bots) {
        return new IndustrialActionContract(id("ie1020:" + name), type, tool, resources,
                true, bots, type == IndustrialActionType.OBSERVE_STATUS, 3,
                "IE 10.2.0 exact action followed by adapter-owned world-state verification");
    }

    private static ImplementationPortContract port(
            String name, ImplementationPortRole role, long minimum) {
        return new ImplementationPortContract(id("ie1020:" + name), role,
                role.expectedResourceType(), role.expectedMode(), minimum,
                OptionalLong.empty(), true, false, PortTemporalSemantics.CONTINUOUS,
                Set.of(), Set.of(VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE));
    }

    private ElectricalNetworkNode electricalNode(
            BlockPos position,
            ResourceId nodeId,
            Set<VoltageTier> observedTiers) {
        List<VoltageTier> orderedTiers = observedTiers.stream().sorted().toList();
        if (orderedTiers.isEmpty() || orderedTiers.size() > 2) {
            throw new IllegalStateException("IE connector has an unsupported voltage-tier set at "
                    + position + ": " + orderedTiers);
        }
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(
                level.getBlockState(position).getBlock());
        if (blockId == null) throw new IllegalStateException(
                "IE connector block is unregistered at " + position);
        ElectricalNodeKind kind = orderedTiers.size() == 2
                ? ElectricalNodeKind.TRANSFORMER
                : blockId.getPath().contains("relay")
                        ? ElectricalNodeKind.RELAY : ElectricalNodeKind.CONNECTOR;
        String canonical = blockId + "|" + state(level.getBlockState(position))
                + "|tiers=" + orderedTiers;
        return new ElectricalNetworkNode(nodeId, kind, position(position), orderedTiers.get(0),
                orderedTiers.size() == 2 ? Optional.of(orderedTiers.get(1)) : Optional.empty(),
                EnumSet.allOf(Direction6.class), EnumSet.allOf(Direction6.class),
                0, 0, 0, 0, sha256(canonical), true);
    }

    private List<BlockPos3i> collisionEnvelope(Connection connection) {
        int steps = Math.min(ElectricalWireEdge.MAX_COLLISION_CELLS - 1,
                Math.max(1, (int) Math.ceil(connection.getLength() * 4)));
        LinkedHashSet<BlockPos3i> cells = new LinkedHashSet<>();
        Vec3 origin = Vec3.atLowerCornerOf(connection.getEndA().position());
        for (int index = 0; index <= steps; index++) {
            double fraction = index / (double) steps;
            Vec3 point = origin.add(connection.getPoint(fraction, connection.getEndA()));
            BlockPos cell = BlockPos.containing(point);
            if (!level.hasChunkAt(cell)) {
                throw new IllegalStateException("IE wire collision envelope enters an unloaded chunk");
            }
            cells.add(position(cell));
        }
        return List.copyOf(cells);
    }

    private static VoltageTier tier(Connection connection) {
        return switch (connection.type.getCategory()) {
            case "LV" -> VoltageTier.LV;
            case "MV" -> VoltageTier.MV;
            case "HV" -> VoltageTier.HV;
            default -> throw new IllegalArgumentException(
                    "IE energy wire has an unknown voltage category "
                            + connection.type.getCategory());
        };
    }

    private static String connectionKey(Connection connection) {
        ConnectionPoint first = connection.getEndA().compareTo(connection.getEndB()) <= 0
                ? connection.getEndA() : connection.getEndB();
        ConnectionPoint second = first == connection.getEndA()
                ? connection.getEndB() : connection.getEndA();
        return first + "|" + second + "|" + connection.type.getUniqueName();
    }

    private static boolean within(ScanRequest request, BlockPos position) {
        BlockPos center = block(request.center());
        return Math.abs(position.getX() - center.getX()) <= request.radius()
                && Math.abs(position.getY() - center.getY()) <= request.radius()
                && Math.abs(position.getZ() - center.getZ()) <= request.radius();
    }

    private <T> AdapterResult.Failure<T> unavailable() {
        Optional<String> installed = ModList.get().getModContainerById(MOD_ID)
                .map(value -> value.getModInfo().getVersion().toString());
        if (installed.isEmpty()) return failure(AdapterFailureCode.UNSUPPORTED_RUNTIME,
                "Immersive Engineering is not installed");
        if (!SUPPORTED_VERSION.equals(installed.get())) return failure(
                AdapterFailureCode.UNSUPPORTED_RUNTIME,
                "Expected IE " + SUPPORTED_VERSION + " but found " + installed.get());
        return null;
    }

    private static ResourceId ingredientIdentity(JsonElement serialized, ItemStack[] candidates) {
        if (serialized != null && serialized.isJsonObject()) {
            var object = serialized.getAsJsonObject();
            if (object.has("tag")) return ResourceId.parse(object.get("tag").getAsString());
            if (object.has("item")) return ResourceId.parse(object.get("item").getAsString());
        }
        if (candidates.length < 1) throw new IllegalArgumentException("IE ingredient has no candidates");
        return itemId(candidates[0]);
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || stack.isEmpty()) throw new IllegalArgumentException("IE item is unregistered");
        return ResourceId.parse(id.toString());
    }

    private static Map<String, String> state(BlockState state) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        state.getValues().entrySet().stream().sorted(Comparator.comparing(
                        value -> value.getKey().getName()))
                .forEach(value -> result.put(value.getKey().getName(), property(value.getKey(), value.getValue())));
        return Map.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String property(Property property, Comparable value) {
        return property.getName(value);
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }
    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }
    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> AdapterResult.Failure<T> failure(
            AdapterFailureCode code, String detail) {
        return new AdapterResult.Failure<>(code, detail);
    }
}
