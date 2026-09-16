package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticCaptureRequest;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.core.diagnostic.FactoryEvidenceSource;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthAnalyzer;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthCategory;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthObservation;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthReport;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthSnapshot;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.ForgeEnergyProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.LogisticsProgressProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.OutputCapacityProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.RotationalPowerProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.StructureProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryObservationState;
import dev.stevecreate.agent.core.diagnostic.FactoryRuntimeFaultClassifier;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskClass;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.industrial.MetalPressDurableEffect;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.industrial.MetalPressProductionOrder;
import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Transaction;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateKineticAdapter;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ThreeModeExecution;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseRuntimeSavedData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Loader-facing read-only probes for one player project or durable warehouse order. */
public final class FactoryDiagnosticService {
    private static final FactoryHealthAnalyzer ANALYZER = new FactoryHealthAnalyzer();
    private static final FactoryLiveProbeEvaluator LIVE = new FactoryLiveProbeEvaluator();

    private FactoryDiagnosticService() {}

    /** Diagnoses exactly the project owned by {@code player}; no nearby inventory is discovered. */
    public static Optional<FactoryHealthReport> diagnoseProject(ServerPlayer player) {
        PlayerWorkflowSavedData.ProjectEntry project = PlayerWorkflowSavedData
                .forLevel(player.serverLevel()).entry(player.getUUID()).orElse(null);
        MetalPressOrderSavedData.StoredOrder metalPress = latestMetalPress(player);
        if (project == null || metalPress != null
                && metalPress.order().updatedAt() > project.updatedAt()) {
            return metalPress == null ? Optional.empty()
                    : Optional.of(diagnoseMetalPress(player, metalPress));
        }

        EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations = unknowns(
                "PROJECT_PROBE_UNAVAILABLE", "No conclusive evidence was available for this category");
        if (terminal(project.stage())) {
            for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
                observations.put(category, observation(category, FactoryObservationState.NOT_APPLICABLE,
                        FactoryEvidenceSource.DURABLE_RUNTIME_STATUS, "PROJECT_TERMINAL",
                        Map.of(), Set.of(), "The project is " + project.stage().name()));
            }
        } else if (beforeMaterialSelection(project.stage())) {
            observations.put(FactoryHealthCategory.MATERIAL_SUPPLY,
                    notApplicable(FactoryHealthCategory.MATERIAL_SUPPLY,
                            "PROJECT_HAS_NOT_REQUESTED_MATERIALS"));
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    notApplicable(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            "PROJECT_HAS_NO_RESERVATION_YET"));
        }

        Entry material = PlayerMaterialSavedData.forLevel(player.serverLevel())
                .entry(project.projectId()).orElse(null);
        if (material != null) {
            inspectMaterials(player, material, observations);
            inspectProjectLive(player.serverLevel(), project, material, observations);
            applyRuntimeStatus(material.statusCode(), observations);
        } else if (expectsMaterialLedger(project.stage())) {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    fault(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            FactoryEvidenceSource.MATERIAL_LEDGER, "MATERIAL_LEDGER_MISSING",
                            Map.of(), Set.of(), "This project stage requires a material ledger"));
        }
        applyRuntimeStatus(project.statusCode(), observations);

        ResourceId subject = new ResourceId("steve_industrial", "project/"
                + project.projectId().toString().replace("-", ""));
        return Optional.of(ANALYZER.analyze(new FactoryHealthSnapshot(subject,
                player.serverLevel().getGameTime(), List.copyOf(observations.values()))));
    }

    static MetalPressOrderSavedData.StoredOrder latestMetalPress(ServerPlayer player) {
        return MetalPressOrderSavedData.forLevel(player.serverLevel()).orders().values().stream()
                .filter(value -> value.order().playerId().equals(player.getUUID()))
                .max(Comparator.comparingLong(value -> value.order().updatedAt())).orElse(null);
    }

    /**
     * Narrow server-authoritative FE diagnosis used by the separately approved maintenance gate.
     * Unrelated factory categories are deliberately NOT_APPLICABLE rather than guessed.
     */
    static Optional<FactoryHealthReport> diagnoseOwnedMetalPressPower(
            ServerPlayer player, ResourceId subjectId) {
        MetalPressOrderSavedData.StoredOrder stored = latestMetalPress(player);
        if (stored == null || !metalPressSubject(stored.order()).equals(subjectId)
                || !sameDimension(player.serverLevel(), stored.order())) {
            return Optional.empty();
        }
        EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations =
                new EnumMap<>(FactoryHealthCategory.class);
        for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
            observations.put(category, notApplicable(category,
                    "IE_POWER_MAINTENANCE_SCOPE_" + category.name()));
        }
        try {
            inspectMetalPressPower(player.serverLevel(), stored.order(), observations,
                    Set.of(MetalPressProductionService.OUTPUT));
        } catch (LinkageError | RuntimeException unavailable) {
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    LIVE.forgeEnergy(new ForgeEnergyProbe(false,
                            stored.order().expectedEnergyFe(), 0, 1, 0,
                            false, false, false),
                            Set.of(MetalPressProductionService.OUTPUT)));
        }
        return Optional.of(ANALYZER.analyze(new FactoryHealthSnapshot(subjectId,
                player.serverLevel().getGameTime(), List.copyOf(observations.values()))));
    }

    private static FactoryHealthReport diagnoseMetalPress(ServerPlayer player,
            MetalPressOrderSavedData.StoredOrder stored) {
        MetalPressProductionOrder order = stored.order();
        EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations = unknowns(
                "IE_ORDER_PROBE_UNAVAILABLE",
                "The IE order has no conclusive observation for this category");
        if (terminal(order.stage())) {
            for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
                observations.put(category, observation(category,
                        FactoryObservationState.NOT_APPLICABLE,
                        FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                        "IE_ORDER_" + order.stage().name(), Map.of(), Set.of(),
                        "The IE Metal Press order is " + order.stage().name()));
            }
        }
        Entry material = PlayerMaterialSavedData.forLevel(player.serverLevel())
                .entry(order.projectId()).orElse(null);
        boolean sameDimension = sameDimension(player.serverLevel(), order);
        if (material == null) {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    fault(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            FactoryEvidenceSource.MATERIAL_LEDGER, "MATERIAL_LEDGER_MISSING",
                            Map.of(), Set.of(), "The IE order has no project material ledger"));
        } else if (sameDimension) {
            inspectMaterials(player, material, observations);
            applyRuntimeStatus(material.statusCode(), observations);
        } else {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    unknown(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            "IE_ORDER_DIMENSION_NOT_LOADED_FOR_PLAYER"));
            observations.put(FactoryHealthCategory.MATERIAL_SUPPLY,
                    unknown(FactoryHealthCategory.MATERIAL_SUPPLY,
                            "IE_ORDER_DIMENSION_NOT_LOADED_FOR_PLAYER"));
        }
        if (!terminal(order.stage()) && sameDimension) {
            inspectMetalPressLive(player.serverLevel(), order, observations);
        } else if (!terminal(order.stage())) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    unknown(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                            "IE_ORDER_DIMENSION_NOT_LOADED_FOR_PLAYER"));
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    unknown(FactoryHealthCategory.ENERGY_SUPPLY,
                            "IE_ORDER_DIMENSION_NOT_LOADED_FOR_PLAYER"));
        }
        applyRuntimeStatus(order.statusCode(), observations);
        ResourceId subject = metalPressSubject(order);
        return ANALYZER.analyze(new FactoryHealthSnapshot(subject,
                player.serverLevel().getGameTime(), List.copyOf(observations.values())));
    }

    /** Diagnoses the persisted order at an exact registered warehouse coordinate. */
    public static Optional<FactoryHealthReport> diagnoseWarehouse(ServerLevel level, BlockPos centre) {
        ResourceId warehouseId = warehouseId(centre);
        ProductionOrder order = WarehouseOrderSavedData.forLevel(level).orders().values().stream()
                .filter(value -> value.warehouseId().equals(warehouseId))
                .findFirst().orElse(null);
        if (order == null) return Optional.empty();

        EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations = unknowns(
                "WAREHOUSE_PROBE_UNAVAILABLE",
                "The durable order has no conclusive observation for this category");
        if (order.status() == ProductionOrderStatus.TARGET_SATISFIED
                || order.status() == ProductionOrderStatus.CANCELLED) {
            for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
                observations.put(category, observation(category,
                        FactoryObservationState.NOT_APPLICABLE,
                        FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                        "WAREHOUSE_" + order.status().name(),
                        Map.of("targetStock", order.targetStock()), Set.of(order.target().resourceId()),
                        "The standing order is " + order.status().name()));
            }
        } else {
            WarehouseRuntimeSavedData.Registration registration = WarehouseRuntimeSavedData
                    .forLevel(level).registrations().get(warehouseId);
            if (registration == null) {
                observations.put(FactoryHealthCategory.LOGISTICS_ROUTE,
                        fault(FactoryHealthCategory.LOGISTICS_ROUTE,
                                FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                                "WAREHOUSE_RUNTIME_REGISTRATION_MISSING",
                                Map.of(), Set.of(order.target().resourceId()),
                                "The order exists but its restart-durable runtime registration is missing"));
            } else {
                observations.put(FactoryHealthCategory.LOGISTICS_ROUTE,
                        observation(FactoryHealthCategory.LOGISTICS_ROUTE,
                                FactoryObservationState.UNKNOWN,
                                FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                                "WAREHOUSE_RUNTIME_REGISTERED_ROUTE_UNPROBED",
                                Map.of("radius", (long) registration.radius(),
                                        "siteCount", (long) registration.siteOrigins().size()),
                                Set.of(),
                                "Registration exists, but no route failure has been observed"));
            }
        }
        WarehouseOrderService.diagnostic(warehouseId).ifPresent(runtime -> {
            Set<ResourceId> resources = Set.of(order.target().resourceId());
            runtime.outputDestination().ifPresent(position -> observations.put(
                    FactoryHealthCategory.OUTPUT_CAPACITY,
                    outputCapacity(level, position, order.target().resourceId(),
                            order.verifiedBatchOutput(), resources)));
            runtime.logistics().ifPresent(route -> observations.put(
                    FactoryHealthCategory.LOGISTICS_ROUTE,
                    LIVE.logistics(new LogisticsProgressProbe(true, route.explicitlyBlocked(),
                            route.expectedTransfers(), route.completedTransfers(),
                            route.lastProgressTick(), route.observedTick(),
                            FactoryLiveProbeEvaluator.DEFAULT_LOGISTICS_STALL_TICKS), resources)));
        });
        applyRuntimeStatus(order.lastStatusCode(), observations);
        return Optional.of(ANALYZER.analyze(new FactoryHealthSnapshot(order.orderId(),
                level.getGameTime(), List.copyOf(observations.values()))));
    }

    private static void inspectProjectLive(ServerLevel level,
            PlayerWorkflowSavedData.ProjectEntry project, Entry material,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations) {
        if (terminal(project.stage())) return;
        Set<ResourceId> resources = Set.of(project.target());
        if (material.logistics() != null) {
            observations.put(FactoryHealthCategory.OUTPUT_CAPACITY,
                    outputCapacity(level, material.logistics().delivery(), project.target(),
                            project.quantity(), resources));
        }

        PlayerConstructionService.DiagnosticContext context = null;
        if (ModList.get().isLoaded("create")) {
            try {
                context = PlayerConstructionService.diagnosticContext(project.projectId())
                        .orElse(null);
            } catch (LinkageError | RuntimeException unavailable) {
                // Optional-mod or lifecycle drift is UNKNOWN, never guessed healthy.
            }
        }
        if (context == null) return;

        if (context.logistics().isPresent()) {
            PlayerConstructionService.LogisticsDiagnosticSnapshot route =
                    context.logistics().orElseThrow();
            observations.put(FactoryHealthCategory.LOGISTICS_ROUTE,
                    LIVE.logistics(new LogisticsProgressProbe(true, route.explicitlyBlocked(),
                            route.expectedTransfers(), route.completedTransfers(),
                            route.lastProgressTick(), route.observedTick(),
                            FactoryLiveProbeEvaluator.DEFAULT_LOGISTICS_STALL_TICKS), resources));
        } else if (context.session().isPresent()) {
            CreateV606ThreeModeExecution.DiagnosticSnapshot session =
                    context.session().orElseThrow();
            long expected = Math.max(1, session.totalTasks() - 2L);
            long completed = Math.min(expected, session.completedTasks());
            observations.put(FactoryHealthCategory.LOGISTICS_ROUTE,
                    LIVE.logistics(new LogisticsProgressProbe(true, false, expected, completed,
                            session.lastProgressTick(), session.observedTick(),
                            FactoryLiveProbeEvaluator.DEFAULT_LOGISTICS_STALL_TICKS), resources));
        }
        inspectCreatePlan(level, context, observations, resources);
    }

    private static void inspectMetalPressLive(ServerLevel level, MetalPressProductionOrder order,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations) {
        Set<ResourceId> resources = Set.of(MetalPressProductionService.OUTPUT);
        observations.put(FactoryHealthCategory.OUTPUT_CAPACITY,
                LIVE.outputCapacity(new OutputCapacityProbe(false, false, 1, 0), resources));

        PlayerConstructionService.LogisticsDiagnosticSnapshot courier =
                MetalPressProductionService.logisticsDiagnostic(order.orderId()).orElse(null);
        if (courier != null) {
            observations.put(FactoryHealthCategory.LOGISTICS_ROUTE,
                    LIVE.logistics(new LogisticsProgressProbe(true, courier.explicitlyBlocked(),
                            courier.expectedTransfers(), courier.completedTransfers(),
                            courier.lastProgressTick(), courier.observedTick(),
                            FactoryLiveProbeEvaluator.DEFAULT_LOGISTICS_STALL_TICKS), resources));
        } else if (metalPressMaterialsDelivered(order.stage())) {
            observations.put(FactoryHealthCategory.LOGISTICS_ROUTE,
                    observation(FactoryHealthCategory.LOGISTICS_ROUTE,
                            FactoryObservationState.HEALTHY,
                            FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                            "IE_MATERIAL_LOGISTICS_COMPLETE", Map.of(), resources,
                            "The durable IE order has completed its exact material transport"));
        }

        if (!ModList.get().isLoaded("immersiveengineering")) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    LIVE.structure(new StructureProbe(false, 7, 0, 0, 0, 7), resources));
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    LIVE.forgeEnergy(new ForgeEnergyProbe(false, order.expectedEnergyFe(), 0,
                            1, 0, false, false, false), resources));
            return;
        }
        try {
            inspectMetalPressStructure(level, order, observations, resources);
            inspectMetalPressPower(level, order, observations, resources);
        } catch (LinkageError | RuntimeException unavailable) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    LIVE.structure(new StructureProbe(false, 7, 0, 0, 0, 7), resources));
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    LIVE.forgeEnergy(new ForgeEnergyProbe(false, order.expectedEnergyFe(), 0,
                            1, 0, false, false, false), resources));
        }
    }

    private static void inspectMetalPressStructure(ServerLevel level,
            MetalPressProductionOrder order,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations,
            Set<ResourceId> resources) {
        MetalPressOrderStage stage = order.stage();
        if (metalPressBeforeStructure(stage) || metalPressAfterTeardown(stage)) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    notApplicable(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                            metalPressBeforeStructure(stage)
                                    ? "IE_STRUCTURE_NOT_BUILT" : "IE_BASELINE_RESTORED"));
            return;
        }
        BlockPos origin = block(order.machineOrigin());
        boolean matches;
        long identityMismatch;
        if (stage == MetalPressOrderStage.STRUCTURE_BUILT) {
            matches = ImmersiveEngineeringV1020MetalPressProduction
                    .rawStructurePresent(level, origin);
            identityMismatch = matches ? 0 : 7;
        } else {
            var machine = ImmersiveEngineeringV1020MetalPressProduction.inspect(level, origin);
            boolean moldRequired = metalPressRequiresMold(stage);
            boolean mold = !moldRequired || machine.isPresent()
                    && ImmersiveEngineeringV1020MetalPressProduction.plateMoldInstalled(
                            level, origin, itemStack("immersiveengineering:mold_plate"));
            matches = machine.isPresent() && mold;
            identityMismatch = matches ? 0 : machine.isEmpty() ? 7 : 1;
        }
        observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                LIVE.structure(new StructureProbe(true, 7, matches ? 7 : 7 - identityMismatch,
                        identityMismatch, 0, 0), resources));
    }

    private static void inspectMetalPressPower(ServerLevel level,
            MetalPressProductionOrder order,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations,
            Set<ResourceId> resources) {
        MetalPressOrderStage stage = order.stage();
        if (!metalPressRequiresPower(stage)) {
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    notApplicable(FactoryHealthCategory.ENERGY_SUPPLY,
                            metalPressAfterTeardown(stage)
                                    ? "IE_POWER_TEARDOWN_COMPLETE" : "IE_POWER_NOT_CONNECTED"));
            return;
        }
        var power = ImmersiveEngineeringV1020MetalPressProduction.observePower(
                level, block(order.machineOrigin()));
        boolean charging = stage == MetalPressOrderStage.POWER_NETWORK_BUILT
                || ImmersiveEngineeringV1020MetalPressProduction
                        .thermalGenerationActive(level, block(order.machineOrigin()));
        boolean processing = stage == MetalPressOrderStage.INPUT_QUEUED
                || stage == MetalPressOrderStage.PROCESSING;
        boolean settled = order.durableEffects().contains(MetalPressDurableEffect.ENERGY_SETTLEMENT);
        observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                LIVE.forgeEnergy(new ForgeEnergyProbe(true, order.expectedEnergyFe(),
                        power.storedEnergyFe(), 1, power.externalConnections(), charging,
                        processing, settled), resources));
    }

    static ResourceId metalPressSubject(MetalPressProductionOrder order) {
        return ResourceId.parse("steve_industrial:ie_order/"
                + order.orderId().toString().replace("-", ""));
    }

    private static boolean sameDimension(ServerLevel level, MetalPressProductionOrder order) {
        return order.dimension().equals(ResourceId.parse(level.dimension().location().toString()));
    }

    private static boolean metalPressBeforeStructure(MetalPressOrderStage stage) {
        return switch (stage) {
            case MATERIAL_SOURCE_SELECTION, MATERIALS_RESERVED, MATERIALS_WITHDRAWN,
                    MATERIALS_DELIVERED -> true;
            default -> false;
        };
    }

    private static boolean metalPressMaterialsDelivered(MetalPressOrderStage stage) {
        return switch (stage) {
            case MATERIALS_DELIVERED, STRUCTURE_BUILT, MULTIBLOCK_FORMED, MOLD_INSTALLED,
                    POWER_NETWORK_BUILT, POWER_VERIFIED, INPUT_QUEUED, PROCESSING,
                    ENERGY_SETTLED, OUTPUT_OBSERVED, TEARDOWN, BASELINE_RESTORED,
                    MATERIALS_RETURNED, REPORT_GENERATED, COMPLETED -> true;
            default -> false;
        };
    }

    private static boolean metalPressRequiresMold(MetalPressOrderStage stage) {
        return switch (stage) {
            case MOLD_INSTALLED, POWER_NETWORK_BUILT, POWER_VERIFIED, INPUT_QUEUED,
                    PROCESSING, ENERGY_SETTLED, OUTPUT_OBSERVED -> true;
            default -> false;
        };
    }

    private static boolean metalPressRequiresPower(MetalPressOrderStage stage) {
        return switch (stage) {
            case POWER_NETWORK_BUILT, POWER_VERIFIED, INPUT_QUEUED, PROCESSING,
                    ENERGY_SETTLED, OUTPUT_OBSERVED -> true;
            default -> false;
        };
    }

    private static boolean metalPressAfterTeardown(MetalPressOrderStage stage) {
        return switch (stage) {
            case BASELINE_RESTORED, MATERIALS_RETURNED, REPORT_GENERATED, COMPLETED,
                    CANCELLED -> true;
            default -> false;
        };
    }

    private static ItemStack itemStack(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item == null) throw new IllegalStateException("diagnostic item is unavailable: " + id);
        return new ItemStack(item);
    }

    private static FactoryHealthObservation outputCapacity(ServerLevel level,
            dev.stevecreate.agent.core.model.BlockPos3i position, ResourceId output,
            long required, Set<ResourceId> resources) {
        BlockPos target = block(position);
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(output.toString()));
        if (item == null || !level.hasChunkAt(target)) {
            return LIVE.outputCapacity(new OutputCapacityProbe(false, false, required, 0), resources);
        }
        if (!(level.getBlockEntity(target) instanceof Container container)) {
            return LIVE.outputCapacity(new OutputCapacityProbe(true, false, required, 0), resources);
        }
        ItemStack expected = new ItemStack(item);
        long available = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack current = container.getItem(slot);
            if (!container.canPlaceItem(slot, expected)) continue;
            int limit = Math.min(container.getMaxStackSize(), current.isEmpty()
                    ? expected.getMaxStackSize() : current.getMaxStackSize());
            if (current.isEmpty()) available = Math.addExact(available, limit);
            else if (ItemStack.isSameItemSameTags(current, expected)) {
                available = Math.addExact(available, Math.max(0, limit - current.getCount()));
            }
        }
        return LIVE.outputCapacity(new OutputCapacityProbe(true, true, required, available), resources);
    }

    private static void inspectCreatePlan(ServerLevel level,
            PlayerConstructionService.DiagnosticContext context,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations,
            Set<ResourceId> resources) {
        if (context.session().isEmpty()) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    notApplicable(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                            "CONSTRUCTION_WAITING_FOR_MATERIAL_LOGISTICS"));
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    notApplicable(FactoryHealthCategory.ENERGY_SUPPLY,
                            "CONSTRUCTION_POWER_NOT_BUILT"));
            return;
        }
        CreateV606ThreeModeExecution.DiagnosticSnapshot session =
                context.session().orElseThrow();
        if (session.currentTaskClass() == ConstructionTaskClass.MATERIAL_TRANSPORT) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    notApplicable(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                            "CONSTRUCTION_MACHINE_NOT_STARTED"));
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    notApplicable(FactoryHealthCategory.ENERGY_SUPPLY,
                            "CONSTRUCTION_POWER_NOT_BUILT"));
            return;
        }
        Optional<CreateV606GoalDrivenExecution.Phase> phase = session.createPhase();
        long expectedCells = context.physicalPlan().placements().stream()
                .flatMap(value -> value.components().stream())
                .map(ResolvedGeometryComponent::position).distinct().count();
        if (phase.isEmpty() || phase.orElseThrow() == CreateV606GoalDrivenExecution.Phase.BUILD) {
            observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    LIVE.structure(new StructureProbe(false, Math.max(1, expectedCells), 0,
                            0, 0, Math.max(1, expectedCells)), resources));
            observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                    notApplicable(FactoryHealthCategory.ENERGY_SUPPLY,
                            "CONSTRUCTION_POWER_NOT_CONNECTED"));
            return;
        }
        observations.put(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                observeCreateStructure(level, context, resources));
        observations.put(FactoryHealthCategory.ENERGY_SUPPLY,
                observeCreatePower(level, context, resources));
    }

    private static FactoryHealthObservation observeCreateStructure(ServerLevel level,
            PlayerConstructionService.DiagnosticContext context, Set<ResourceId> resources) {
        Map<dev.stevecreate.agent.core.model.BlockPos3i, ResolvedGeometryComponent> expected =
                new LinkedHashMap<>();
        context.physicalPlan().placements().forEach(placement -> placement.components()
                .forEach(component -> expected.put(component.position(), component)));
        long observed = 0;
        long identity = 0;
        long orientation = 0;
        long unloaded = 0;
        for (ResolvedGeometryComponent component : expected.values()) {
            BlockPos position = block(component.position());
            if (!level.hasChunkAt(position)) {
                unloaded++;
                continue;
            }
            BlockState state = level.getBlockState(position);
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (blockId == null || !component.blockId().toString().equals(blockId.toString())) {
                identity++;
                continue;
            }
            boolean stateMatches = true;
            boolean orientationMismatch = false;
            for (Map.Entry<String, String> property : component.blockState().entrySet()) {
                String actual = propertyValue(state, property.getKey());
                if (!property.getValue().equals(actual)) {
                    stateMatches = false;
                    if (property.getKey().equals("facing") || property.getKey().equals("axis")
                            || property.getKey().equals("horizontal_facing")) {
                        orientationMismatch = true;
                    }
                }
            }
            if (stateMatches) observed++;
            else if (orientationMismatch) orientation++;
            else identity++;
        }
        boolean conclusive = unloaded == 0 || identity > 0 || orientation > 0;
        return LIVE.structure(new StructureProbe(conclusive, expected.size(), observed,
                identity, orientation, unloaded), resources);
    }

    private static FactoryHealthObservation observeCreatePower(ServerLevel level,
            PlayerConstructionService.DiagnosticContext context, Set<ResourceId> resources) {
        List<dev.stevecreate.agent.core.model.BlockPos3i> positions = context.physicalPlan()
                .placements().stream().flatMap(value -> value.rotationalPowerRoute().stream())
                .distinct().toList();
        if (positions.isEmpty()) {
            return LIVE.rotationalPower(new RotationalPowerProbe(false, 1, 0,
                    0, 0, 0, 0, 0), resources);
        }
        ForgeCreateKineticAdapter adapter;
        try {
            adapter = new ForgeCreateKineticAdapter(level);
        } catch (LinkageError | RuntimeException unavailable) {
            return LIVE.rotationalPower(new RotationalPowerProbe(false, positions.size(), 0,
                    0, 0, 0, 0, 0), resources);
        }
        List<KineticSnapshot> captures = new ArrayList<>();
        boolean unavailable = false;
        boolean componentMissing = false;
        for (dev.stevecreate.agent.core.model.BlockPos3i position : positions) {
            AdapterResult<KineticSnapshot> result = adapter.captureKinetics(
                    new KineticCaptureRequest(position));
            if (result instanceof AdapterResult.Success<KineticSnapshot> success) {
                captures.add(success.value());
            } else {
                AdapterFailureCode code = ((AdapterResult.Failure<KineticSnapshot>) result).code();
                if (code == AdapterFailureCode.KINETIC_COMPONENT_NOT_FOUND) componentMissing = true;
                else unavailable = true;
            }
        }
        long powered = captures.stream().filter(value -> Math.abs(value.speedRpm()) > 0
                && !value.overstressed()).count();
        long overstressed = captures.stream().filter(KineticSnapshot::overstressed).count();
        long minimumRpm = captures.isEmpty() ? 0 : captures.stream()
                .mapToLong(value -> scaled(Math.abs(value.speedRpm()))).min().orElse(0);
        Map<String, KineticSnapshot> networks = new LinkedHashMap<>();
        for (KineticSnapshot capture : captures) {
            String key = capture.networkId().isPresent()
                    ? capture.dimensionId() + "#" + capture.networkId().getAsLong()
                    : capture.dimensionId() + "@" + capture.position();
            networks.putIfAbsent(key, capture);
        }
        long capacity = networks.values().stream()
                .mapToLong(value -> scaled(value.stressCapacity())).sum();
        long load = networks.values().stream()
                .mapToLong(value -> scaled(value.stressLoad())).sum();
        boolean conclusiveFault = componentMissing || overstressed > 0 || load > capacity;
        boolean observerAvailable = !unavailable || conclusiveFault;
        return LIVE.rotationalPower(new RotationalPowerProbe(observerAvailable, positions.size(),
                captures.size(), powered, overstressed, minimumRpm, capacity, load), resources);
    }

    private static long scaled(double value) {
        return Math.max(0, Math.round(value * 1_000.0D));
    }

    private static String propertyValue(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? null : propertyValue(state, property);
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static BlockPos block(dev.stevecreate.agent.core.model.BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static void inspectMaterials(ServerPlayer player, Entry entry,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations) {
        long requiredTotal = sum(entry.requirements().values());
        long reservedTotal = entry.reservations().stream().mapToLong(Reservation::quantity).sum();
        Set<ResourceId> resources = new LinkedHashSet<>(entry.requirements().keySet());

        if (entry.report() != null) {
            var report = entry.report();
            Map<String, Long> metrics = Map.of(
                    "planned", report.planned(),
                    "withdrawn", report.withdrawn(),
                    "consumed", report.consumed(),
                    "returned", report.returned(),
                    "unaccountedItems", report.unaccountedItems());
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    observation(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            report.balanced() ? FactoryObservationState.HEALTHY
                                    : FactoryObservationState.FAULT,
                            FactoryEvidenceSource.MATERIAL_LEDGER,
                            report.balanced() ? "MATERIAL_LEDGER_BALANCED"
                                    : "MATERIAL_LEDGER_UNBALANCED",
                            metrics, resources,
                            report.balanced() ? "The completion ledger balances exactly"
                                    : "The completion ledger does not balance"));
            observations.put(FactoryHealthCategory.MATERIAL_SUPPLY,
                    observation(FactoryHealthCategory.MATERIAL_SUPPLY,
                            report.balanced() ? FactoryObservationState.HEALTHY
                                    : FactoryObservationState.UNKNOWN,
                            report.balanced() ? FactoryEvidenceSource.MATERIAL_LEDGER
                                    : FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                            report.balanced() ? "MATERIAL_SETTLED" : "MATERIAL_SETTLEMENT_UNKNOWN",
                            metrics, resources,
                            report.balanced() ? "All planned material has been accounted for"
                                    : "Supply cannot be inferred from an unbalanced report"));
            return;
        }

        LedgerCheck ledger = checkLedger(entry);
        if (!ledger.valid()) {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    fault(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            FactoryEvidenceSource.MATERIAL_LEDGER, "MATERIAL_LEDGER_DIVERGED",
                            Map.of("required", requiredTotal, "reserved", reservedTotal,
                                    "invalidRows", ledger.invalidRows()),
                            resources, "Reservation or transaction rows disagree with the material plan"));
            return;
        }

        if (entry.reservations().isEmpty()) {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    notApplicable(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            "MATERIAL_RESERVATION_NOT_CONFIRMED"));
            observations.put(FactoryHealthCategory.MATERIAL_SUPPLY,
                    observation(FactoryHealthCategory.MATERIAL_SUPPLY,
                            FactoryObservationState.UNKNOWN, FactoryEvidenceSource.NO_PROBE,
                            "UNCONFIRMED_MATERIAL_SELECTION", Map.of("required", requiredTotal,
                                    "selectedSources", (long) entry.sources().size()), resources,
                            "Selected sources have not yet produced an exact reservation"));
            return;
        }

        List<Reservation> physical = entry.reservations().stream()
                .filter(value -> stillExpectedInSource(entry, value)).toList();
        if (physical.size() != entry.reservations().size()) {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    observation(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            FactoryObservationState.UNKNOWN,
                            FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                            "MATERIAL_IN_TRANSIT_LEDGER_ONLY",
                            Map.of("required", requiredTotal, "reserved", reservedTotal,
                                    "inSource", (long) physical.size(),
                                    "inTransitOrSettled", (long) entry.reservations().size() - physical.size()),
                            resources,
                            "Some reservations have left their sources; durable status has no live cargo probe"));
            observations.put(FactoryHealthCategory.MATERIAL_SUPPLY,
                    observation(FactoryHealthCategory.MATERIAL_SUPPLY,
                            FactoryObservationState.UNKNOWN,
                            FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                            "MATERIAL_IN_TRANSIT_SUPPLY_UNPROBED",
                            Map.of("required", requiredTotal, "reserved", reservedTotal), resources,
                            "Source inventory alone cannot prove material carried or already consumed"));
            return;
        }

        Map<UUID, Source> current = new HashMap<>();
        long changedSources = 0;
        for (Source bound : entry.sources()) {
            Source rescanned = PlayerMaterialService.rescanBoundSourceForDiagnosis(player, bound);
            if (rescanned != null) current.put(bound.sourceId(), rescanned);
            if (rescanned == null || !PlayerMaterialService.sameSnapshot(bound, rescanned)) {
                changedSources++;
            }
        }
        long expired = physical.stream().filter(value ->
                value.expiresAt() <= Instant.now().toEpochMilli()).count();
        long missing = missingReservedQuantity(physical, current);
        Map<String, Long> metrics = Map.of(
                "required", requiredTotal, "reserved", reservedTotal,
                "sourceCount", (long) entry.sources().size(), "changedSources", changedSources,
                "expiredReservations", expired, "missingReserved", missing);
        if (changedSources > 0 || expired > 0) {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    fault(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            FactoryEvidenceSource.LIVE_WORLD,
                            expired > 0 ? "MATERIAL_RESERVATION_EXPIRED" : "MATERIAL_SOURCE_CHANGED",
                            metrics, resources,
                            "A bound source no longer matches the reservation evidence"));
        } else {
            observations.put(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    observation(FactoryHealthCategory.RESERVATION_INTEGRITY,
                            FactoryObservationState.HEALTHY, FactoryEvidenceSource.LIVE_WORLD,
                            "MATERIAL_RESERVATIONS_CURRENT", metrics, resources,
                            "Every exact reservation is current in its bound source"));
        }
        observations.put(FactoryHealthCategory.MATERIAL_SUPPLY,
                observation(FactoryHealthCategory.MATERIAL_SUPPLY,
                        missing > 0 ? FactoryObservationState.FAULT : FactoryObservationState.HEALTHY,
                        FactoryEvidenceSource.LIVE_WORLD,
                        missing > 0 ? "RESERVED_MATERIAL_GONE" : "RESERVED_MATERIAL_PRESENT",
                        metrics, resources,
                        missing > 0 ? "One or more exact reserved item identities are missing"
                                : "All exact reserved quantities are physically present"));
    }

    private static LedgerCheck checkLedger(Entry entry) {
        long invalid = 0;
        Map<ResourceId, Long> reservedByRequirement = new LinkedHashMap<>();
        Set<UUID> reservationIds = new HashSet<>();
        Set<UUID> sourceIds = new HashSet<>();
        entry.sources().forEach(value -> sourceIds.add(value.sourceId()));
        Map<UUID, Reservation> reservations = new HashMap<>();
        for (Reservation reservation : entry.reservations()) {
            if (!reservationIds.add(reservation.reservationId())
                    || !sourceIds.contains(reservation.sourceId())
                    || !reservation.identity().itemId().equals(reservation.requirement())
                    || !reservation.identity().payloadSha256()
                            .equals(MaterialIdentity.EMPTY_PAYLOAD_SHA256)) {
                invalid++;
            }
            reservations.put(reservation.reservationId(), reservation);
            reservedByRequirement.merge(reservation.requirement(), reservation.quantity(), Math::addExact);
        }
        if (!reservedByRequirement.equals(entry.requirements()) && !entry.reservations().isEmpty()) invalid++;
        Set<UUID> transactionIds = new HashSet<>();
        Set<UUID> transactionReservations = new HashSet<>();
        for (Transaction transaction : entry.transactions()) {
            Reservation reservation = reservations.get(transaction.reservationId());
            if (!transactionIds.add(transaction.transactionId())
                    || !transactionReservations.add(transaction.reservationId())
                    || reservation == null || transaction.quantity() != reservation.quantity()) {
                invalid++;
            }
        }
        return new LedgerCheck(invalid == 0, invalid);
    }

    private static long missingReservedQuantity(List<Reservation> reservations,
            Map<UUID, Source> current) {
        Map<String, Long> required = new LinkedHashMap<>();
        for (Reservation reservation : reservations) {
            required.merge(reservation.sourceId() + "|" + reservation.identity(),
                    reservation.quantity(), Math::addExact);
        }
        Map<String, Long> held = new LinkedHashMap<>();
        current.forEach((sourceId, source) -> source.slots().forEach(slot ->
                held.merge(sourceId + "|" + slot.identity(), slot.quantity(), Math::addExact)));
        long missing = 0;
        for (Map.Entry<String, Long> row : required.entrySet()) {
            missing = Math.addExact(missing,
                    Math.max(0, row.getValue() - held.getOrDefault(row.getKey(), 0L)));
        }
        return missing;
    }

    private static boolean stillExpectedInSource(Entry entry, Reservation reservation) {
        Transaction transaction = entry.transactions().stream()
                .filter(value -> value.reservationId().equals(reservation.reservationId()))
                .findFirst().orElse(null);
        return transaction == null || transaction.state() == MaterialTransactionState.PREPARED;
    }

    private static void applyRuntimeStatus(String status,
            EnumMap<FactoryHealthCategory, FactoryHealthObservation> observations) {
        for (FactoryHealthCategory category : FactoryRuntimeFaultClassifier.classify(status)) {
            observations.put(category, fault(category,
                    FactoryEvidenceSource.DURABLE_RUNTIME_STATUS,
                    "RUNTIME_" + category.name() + "_FAULT", Map.of(), Set.of(),
                    "Durable runtime status: " + bounded(status)));
        }
    }

    private static EnumMap<FactoryHealthCategory, FactoryHealthObservation> unknowns(
            String code, String detail) {
        EnumMap<FactoryHealthCategory, FactoryHealthObservation> result =
                new EnumMap<>(FactoryHealthCategory.class);
        for (FactoryHealthCategory category : FactoryHealthCategory.values()) {
            result.put(category, observation(category, FactoryObservationState.UNKNOWN,
                    FactoryEvidenceSource.NO_PROBE, code, Map.of(), Set.of(), detail));
        }
        return result;
    }

    private static FactoryHealthObservation fault(FactoryHealthCategory category,
            FactoryEvidenceSource source, String code, Map<String, Long> metrics,
            Set<ResourceId> resources, String detail) {
        return observation(category, FactoryObservationState.FAULT, source, code, metrics,
                resources, detail);
    }

    private static FactoryHealthObservation unknown(FactoryHealthCategory category,
            String code) {
        return observation(category, FactoryObservationState.UNKNOWN,
                FactoryEvidenceSource.NO_PROBE, code, Map.of(), Set.of(),
                "The owned order is in another dimension; no coordinate was probed here");
    }

    private static FactoryHealthObservation notApplicable(FactoryHealthCategory category,
            String code) {
        return observation(category, FactoryObservationState.NOT_APPLICABLE,
                FactoryEvidenceSource.DURABLE_RUNTIME_STATUS, code, Map.of(), Set.of(),
                "This category does not apply at the current project stage");
    }

    private static FactoryHealthObservation observation(FactoryHealthCategory category,
            FactoryObservationState state, FactoryEvidenceSource source, String code,
            Map<String, Long> metrics, Set<ResourceId> resources, String detail) {
        return new FactoryHealthObservation(category, state, source, code, metrics, resources,
                bounded(detail));
    }

    private static boolean beforeMaterialSelection(WorkflowStage stage) {
        return switch (stage) {
            case GOAL_SELECTION, PLACEMENT_PREVIEW, SITE_SURVEY, AWAITING_APPROVAL,
                    CLEARING, POST_CLEAR_RESCAN -> true;
            default -> false;
        };
    }

    private static boolean expectsMaterialLedger(WorkflowStage stage) {
        return switch (stage) {
            case MATERIAL_SOURCE_SELECTION, MATERIAL_RESERVED, CONSTRUCTION, PAUSED, COMPLETED -> true;
            default -> false;
        };
    }

    private static boolean terminal(WorkflowStage stage) {
        return stage == WorkflowStage.COMPLETED || stage == WorkflowStage.CANCELLED
                || stage == WorkflowStage.REFUSED;
    }

    private static boolean terminal(MetalPressOrderStage stage) {
        return stage == MetalPressOrderStage.COMPLETED || stage == MetalPressOrderStage.CANCELLED;
    }

    private static long sum(java.util.Collection<Long> values) {
        long result = 0;
        for (long value : values) result = Math.addExact(result, value);
        return result;
    }

    private static ResourceId warehouseId(BlockPos warehouse) {
        return ResourceId.parse("steve_industrial:warehouse/" + warehouse.getX() + "_"
                + warehouse.getY() + "_" + warehouse.getZ());
    }

    private static String bounded(String value) {
        String normalized = value == null ? "UNKNOWN" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.isEmpty()) normalized = "UNKNOWN";
        return normalized.length() <= FactoryHealthObservation.MAX_DETAIL_LENGTH
                ? normalized : normalized.substring(0, FactoryHealthObservation.MAX_DETAIL_LENGTH);
    }

    private record LedgerCheck(boolean valid, long invalidRows) {}
}
