package dev.stevecreate.agent.core.binding;

import static dev.stevecreate.agent.core.binding.BindingTestFixtures.FINGERPRINT;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.catalog;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.constraints;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.descriptor;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.id;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.multiPlan;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.singlePlan;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessContext;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessRefusal;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessResult;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessSuccess;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessVerificationCheck;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessVerifier;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.layout.ClearanceVolume;
import dev.stevecreate.agent.core.layout.GeometryComponent;
import dev.stevecreate.agent.core.layout.ImmutableMachineGeometryCatalog;
import dev.stevecreate.agent.core.layout.LayoutCellState;
import dev.stevecreate.agent.core.layout.LayoutConstraints;
import dev.stevecreate.agent.core.layout.LayoutFailureCode;
import dev.stevecreate.agent.core.layout.MachineFootprint;
import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.layout.PhysicalPortRule;
import dev.stevecreate.agent.core.layout.PhysicalizationFailure;
import dev.stevecreate.agent.core.layout.PhysicalizationResult;
import dev.stevecreate.agent.core.layout.PhysicalizationService;
import dev.stevecreate.agent.core.layout.PhysicalizationSuccess;
import dev.stevecreate.agent.core.layout.PhysicalizationVerificationCheck;
import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PhysicalizationServiceTest {
    private static final BlockPos3i ANCHOR = new BlockPos3i(100, 64, 100);
    private final PhysicalizationService service = new PhysicalizationService();

    @Test
    void definesTheExactStableLayoutFailureContract() {
        assertThat(Arrays.stream(LayoutFailureCode.values()).map(Enum::name).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "LAYOUT_CONSTRAINTS_MISSING", "FOOTPRINT_UNAVAILABLE",
                        "CLEARANCE_BLOCKED", "ORIENTATION_UNAVAILABLE",
                        "PHYSICAL_PORT_RESOLUTION_FAILED", "ITEM_ROUTE_NOT_FOUND",
                        "ROTATIONAL_POWER_ROUTE_NOT_FOUND", "ROUTE_CAPACITY_INSUFFICIENT",
                        "STRESS_CAPACITY_INSUFFICIENT", "PLACEMENT_COLLISION",
                        "SEARCH_BUDGET_EXHAUSTED", "PHYSICALIZATION_FAILED",
                        "UNIFIED_GRAPH_INVALID", "WORLD_SNAPSHOT_STALE",
                        "UNSUPPORTED_IMPLEMENTATION_GEOMETRY");
    }

    @Test
    void physicalizesSingleMachineInAllThreeAcceptedOrientationsDeterministically() {
        VerifiedImplementationBoundPlan bound = bindSingle();
        ImmutableMachineGeometryCatalog geometries = geometries("fixture:mill");
        for (QuarterTurn orientation : List.of(
                QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90, QuarterTurn.CLOCKWISE_270)) {
            LayoutConstraints constraints = constraints(snapshot(Map.of()), List.of(orientation), 64, 100_000, 64, 64);
            VerifiedPhysicalPlan first = success(service.physicalize(bound, geometries, constraints));
            VerifiedPhysicalPlan second = success(service.physicalize(bound, geometries, constraints));

            assertThat(first.placements()).singleElement().satisfies(placement -> {
                assertThat(placement.orientation()).isEqualTo(orientation);
                assertThat(placement.topologyContract()).isEqualTo("fixture:power->machine");
            });
            assertThat(first.routes()).hasSize(bound.graph().logicalPlan().logicalGraph().edges().size());
            assertThat(first.evidence()).containsOnlyKeys(PhysicalizationVerificationCheck.values());
            assertThat(first.candidate().trace()).isEqualTo(second.candidate().trace());
            assertThat(first.placements()).isEqualTo(second.placements());
            assertThat(first.routes()).isEqualTo(second.routes());
            assertThat(first.unifiedGraph().nodes()).isEqualTo(second.unifiedGraph().nodes());
        }
    }

    @Test
    void physicalizesAMultiNodeGraphAndRoutesItsIntermediateItemEdge() {
        VerifiedImplementationBoundPlan bound = successBinding(new ImplementationBindingService().bind(
                multiPlan(), catalog(
                        descriptor("fixture:crusher", "industrial:crushing", "fixture:crushing", 0),
                        descriptor("fixture:smelter", "industrial:smelting", "fixture:smelting", 0)),
                BindingTestFixtures.constraints()));
        ImmutableMachineGeometryCatalog geometries = new ImmutableMachineGeometryCatalog(
                List.of(geometry("fixture:crusher"), geometry("fixture:smelter")), FINGERPRINT);
        VerifiedPhysicalPlan plan = success(service.physicalize(
                bound, geometries,
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 128, 200_000, 64, 128)));

        assertThat(plan.placements()).hasSize(2);
        assertThat(plan.routes()).hasSize(bound.graph().logicalPlan().logicalGraph().edges().size());
        assertThat(plan.unifiedGraph().edges().values())
                .anyMatch(edge -> edge.resourceType() == GenericResourceType.ROTATIONAL_POWER);
        assertThat(plan.unifiedGraph().edges().values())
                .anyMatch(edge -> edge.resourceType() == GenericResourceType.ITEM);
    }

    @Test
    void reportsOccupiedClearanceCapacityStressStaleBudgetAndUnsupportedFailuresPrecisely() {
        VerifiedImplementationBoundPlan bound = bindSingle();
        ImmutableMachineGeometryCatalog geometries = geometries("fixture:mill");

        assertCode(service.physicalize(bound, geometries,
                constraints(snapshot(Map.of(ANCHOR, LayoutCellState.OCCUPIED)),
                        List.of(QuarterTurn.ZERO), 1, 10_000, 64, 64)),
                LayoutFailureCode.PLACEMENT_COLLISION);
        assertCode(service.physicalize(bound, geometries,
                constraints(snapshot(Map.of(ANCHOR.translate(0, 1, 0), LayoutCellState.PROTECTED)),
                        List.of(QuarterTurn.ZERO), 1, 10_000, 64, 64)),
                LayoutFailureCode.CLEARANCE_BLOCKED);
        assertCode(service.physicalize(bound, geometries,
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 64, 10_000, 1, 64)),
                LayoutFailureCode.ROUTE_CAPACITY_INSUFFICIENT);
        assertCode(service.physicalize(bound, geometries,
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 1, 10_000, 64, 1)),
                LayoutFailureCode.STRESS_CAPACITY_INSUFFICIENT);
        assertCode(service.physicalize(bound, geometries,
                constraints(new PlacementSnapshot("stale=1", 0, snapshotCells(Map.of())),
                        List.of(QuarterTurn.ZERO), 1, 10_000, 64, 64)),
                LayoutFailureCode.WORLD_SNAPSHOT_STALE);
        assertCode(service.physicalize(bound, geometries,
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 1, 1, 64, 64)),
                LayoutFailureCode.SEARCH_BUDGET_EXHAUSTED);
        assertCode(service.physicalize(bound,
                new ImmutableMachineGeometryCatalog(List.of(), FINGERPRINT),
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 1, 10_000, 64, 64)),
                LayoutFailureCode.UNSUPPORTED_IMPLEMENTATION_GEOMETRY);
    }

    @Test
    void verifiedPhysicalPlanIsNotPubliclyConstructibleAndCarriesNoSessionOrMutationAuthority() {
        assertThat(VerifiedPhysicalPlan.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(new Class<?>[] {
                    VerifiedPhysicalPlan.class,
                    dev.stevecreate.agent.core.layout.PhysicalLayoutCandidate.class,
                    dev.stevecreate.agent.core.layout.PhysicalMachinePlacement.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.contains("GenericExecutionSession")
                        || name.contains("BoundedStepRunner")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
    }

    @Test
    void executionReadinessRequiresEveryGateBeforeExposingANonPublicSuccess() {
        VerifiedPhysicalPlan physical = success(service.physicalize(
                bindSingle(), geometries("fixture:mill"),
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 64, 100_000, 64, 64)));
        ExecutionReadinessResult result = new ExecutionReadinessVerifier().verify(
                physical, new ReadyFacts(physical).context());

        assertThat(result).isInstanceOf(ExecutionReadinessSuccess.class);
        ExecutionReadyPlan ready = ((ExecutionReadinessSuccess) result).plan();
        assertThat(ready.physicalPlan()).isSameAs(physical);
        assertThat(ready.evidence()).containsOnlyKeys(ExecutionReadinessVerificationCheck.values());
        assertThat(ready.trace()).anyMatch(value -> value.contains("execution:goal="));
        assertThat(ExecutionReadyPlan.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(ExecutionReadyPlan.class.getDeclaredFields())
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.contains("GenericExecutionSession")
                        || name.contains("BoundedStepRunner")
                        || name.startsWith("net.minecraft"));
    }

    @Test
    void readinessRefusesStaleAreaResourcesAuthoritySafetyAndDuplicateSessionsPrecisely() {
        VerifiedPhysicalPlan physical = success(service.physicalize(
                bindSingle(), geometries("fixture:mill"),
                constraints(snapshot(Map.of()), List.of(QuarterTurn.ZERO), 64, 100_000, 64, 64)));
        ResourceId input = physical.candidate().boundPlan().graph().logicalPlan()
                .candidate().rawMaterials().get(0).resourceId();
        BlockPos3i target = physical.placements().get(0).components().get(0).position();

        assertReadinessCode(physical, facts -> facts.runtime = "stale=1",
                ExecutionReadinessFailureCode.PHYSICAL_PLAN_STALE);
        assertReadinessCode(physical, facts -> facts.snapshot = snapshot(Map.of(
                target, LayoutCellState.OCCUPIED)),
                ExecutionReadinessFailureCode.TARGET_AREA_CHANGED);
        assertReadinessCode(physical, facts -> facts.loaded.remove(target),
                ExecutionReadinessFailureCode.REQUIRED_CHUNK_UNLOADED);
        assertReadinessCode(physical, facts -> facts.inputs.put(input, 0L),
                ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING);
        assertReadinessCode(physical, facts -> facts.power = 0,
                ExecutionReadinessFailureCode.POWER_SOURCE_MISSING);
        assertReadinessCode(physical, facts -> facts.stress = 0,
                ExecutionReadinessFailureCode.STRESS_CAPACITY_INSUFFICIENT);
        assertReadinessCode(physical, facts -> facts.adapters.clear(),
                ExecutionReadinessFailureCode.EXECUTION_NOT_READY);
        assertReadinessCode(physical, facts -> facts.authoritative = false,
                ExecutionReadinessFailureCode.EXECUTION_NOT_READY);
        assertReadinessCode(physical, facts -> facts.budget = 1,
                ExecutionReadinessFailureCode.EXECUTION_NOT_READY);
        assertReadinessCode(physical, facts -> facts.journal = false,
                ExecutionReadinessFailureCode.JOURNAL_UNAVAILABLE);
        assertReadinessCode(physical, facts -> facts.rollback = false,
                ExecutionReadinessFailureCode.ROLLBACK_UNSAFE);
        assertReadinessCode(physical, facts -> facts.recovery = false,
                ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE);
        assertReadinessCode(physical, facts -> facts.active.add(facts.session),
                ExecutionReadinessFailureCode.SESSION_ALREADY_EXISTS);
        assertReadinessCode(physical, facts -> facts.world =
                        ExecutionWorldClassification.FORMAL_EXTERNAL_INSTANCE,
                ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN);
    }

    @Test
    void definesTheExactStableExecutionFailureContract() {
        assertThat(Arrays.stream(ExecutionReadinessFailureCode.values())
                .map(Enum::name).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "EXECUTION_NOT_READY", "PHYSICAL_PLAN_STALE", "TARGET_AREA_CHANGED",
                        "REQUIRED_CHUNK_UNLOADED", "INPUT_RESOURCE_MISSING", "POWER_SOURCE_MISSING",
                        "STRESS_CAPACITY_INSUFFICIENT", "BLOCK_PLACEMENT_BLOCKED",
                        "ROUTE_CONSTRUCTION_FAILED", "SESSION_ALREADY_EXISTS", "JOURNAL_UNAVAILABLE",
                        "RELOAD_RECOVERY_UNSAFE", "PROCESS_TIMEOUT", "INPUT_NOT_CONSUMED",
                        "OUTPUT_NOT_PRODUCED", "OUTPUT_QUANTITY_MISMATCH",
                        "VERIFICATION_EVIDENCE_MISSING", "ROLLBACK_UNSAFE",
                        "EXECUTION_CANCELLED", "FORMAL_WORLD_FORBIDDEN");
    }

    private static VerifiedImplementationBoundPlan bindSingle() {
        return successBinding(new ImplementationBindingService().bind(
                singlePlan(), catalog(descriptor(
                        "fixture:mill", "industrial:milling", "fixture:milling", 0)),
                BindingTestFixtures.constraints()));
    }

    private static ImmutableMachineGeometryCatalog geometries(String implementationId) {
        return new ImmutableMachineGeometryCatalog(List.of(geometry(implementationId)), FINGERPRINT);
    }

    private static MachineGeometryDescriptor geometry(String implementationId) {
        ResourceId implementation = id(implementationId);
        return new MachineGeometryDescriptor(
                implementation,
                new MachineFootprint(Set.of(new BlockPos3i(0, 0, 0))),
                new ClearanceVolume(Set.of(
                        new BlockPos3i(-1, 0, 0), new BlockPos3i(0, 0, 0),
                        new BlockPos3i(1, 0, 0), new BlockPos3i(0, 1, 0))),
                Set.of(QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90, QuarterTurn.CLOCKWISE_270),
                List.of(new GeometryComponent(
                        id("fixture:machine_role"), id("fixture:machine_block"),
                        new BlockPos3i(0, 0, 0), Map.of())),
                List.of(
                        new PhysicalPortRule(
                                id(implementationId + "_input"), new BlockPos3i(-1, 1, 0),
                                Optional.of(Direction6.WEST), GenericResourceType.ITEM, PortMode.INPUT, 64),
                        new PhysicalPortRule(
                                id(implementationId + "_output"), new BlockPos3i(1, 1, 0),
                                Optional.of(Direction6.EAST), GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        new PhysicalPortRule(
                                id(implementationId + "_power"), new BlockPos3i(0, 0, 0),
                                Optional.of(Direction6.SOUTH), GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32)),
                List.of(new BlockPos3i(0, 0, 1), new BlockPos3i(0, 0, 0)),
                32, 8, "fixture:power->machine");
    }

    private static LayoutConstraints constraints(
            PlacementSnapshot snapshot,
            List<QuarterTurn> orientations,
            int routeLength,
            int budget,
            long itemCapacity,
            long stressCapacity) {
        return new LayoutConstraints(
                ANCHOR, orientations, 1, 64, routeLength, budget,
                itemCapacity, 64, stressCapacity, snapshot);
    }

    private static PlacementSnapshot snapshot(Map<BlockPos3i, LayoutCellState> overrides) {
        return new PlacementSnapshot(FINGERPRINT, 0, snapshotCells(overrides));
    }

    private static Map<BlockPos3i, LayoutCellState> snapshotCells(
            Map<BlockPos3i, LayoutCellState> overrides) {
        Map<BlockPos3i, LayoutCellState> cells = new LinkedHashMap<>();
        for (int x = 80; x <= 130; x++) {
            for (int y = 60; y <= 70; y++) {
                for (int z = 80; z <= 130; z++) {
                    cells.put(new BlockPos3i(x, y, z), LayoutCellState.REPLACEABLE);
                }
            }
        }
        cells.putAll(overrides);
        return cells;
    }

    private static VerifiedImplementationBoundPlan successBinding(BindingResult result) {
        assertThat(result).isInstanceOf(BindingResult.Success.class);
        return ((BindingResult.Success) result).plan();
    }

    private static VerifiedPhysicalPlan success(PhysicalizationResult result) {
        assertThat(result).isInstanceOf(PhysicalizationSuccess.class);
        return ((PhysicalizationSuccess) result).plan();
    }

    private static void assertCode(PhysicalizationResult result, LayoutFailureCode code) {
        assertThat(result).isInstanceOf(PhysicalizationFailure.class);
        assertThat(((PhysicalizationFailure) result).failure().code()).isEqualTo(code);
    }

    private static void assertReadinessCode(
            VerifiedPhysicalPlan physical,
            Consumer<ReadyFacts> mutation,
            ExecutionReadinessFailureCode code) {
        ReadyFacts facts = new ReadyFacts(physical);
        mutation.accept(facts);
        ExecutionReadinessResult result = new ExecutionReadinessVerifier().verify(
                physical, facts.context());
        assertThat(result).isInstanceOf(ExecutionReadinessRefusal.class);
        assertThat(((ExecutionReadinessRefusal) result).failure().code()).isEqualTo(code);
    }

    static final class ReadyFacts {
        private final VerifiedPhysicalPlan physical;
        private final ResourceId session = id("execution:test_session");
        private String runtime = FINGERPRINT;
        private Set<ResourceId> adapters;
        private Set<ResourceId> implementations;
        private boolean authoritative = true;
        private PlacementSnapshot snapshot = snapshot(Map.of());
        private Set<BlockPos3i> loaded = new java.util.LinkedHashSet<>(snapshot.cells().keySet());
        private Map<ResourceId, Long> inputs = new LinkedHashMap<>();
        private long power = 64;
        private long stress = 64;
        private int budget = 4_096;
        private boolean journal = true;
        private boolean rollback = true;
        private boolean recovery = true;
        private Set<ResourceId> active = new java.util.LinkedHashSet<>();
        private ExecutionWorldClassification world =
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST;

        ReadyFacts(VerifiedPhysicalPlan physical) {
            this.physical = physical;
            var nodes = physical.candidate().boundPlan().graph().boundProcessNodes().values();
            adapters = nodes.stream().map(BoundMachineNode::adapterId).collect(Collectors.toSet());
            implementations = nodes.stream().map(BoundMachineNode::implementationId)
                    .collect(Collectors.toSet());
            var candidate = physical.candidate().boundPlan().graph().logicalPlan().candidate();
            java.util.stream.Stream.concat(candidate.rawMaterials().stream(),
                            candidate.ownedResourcesUsed().stream())
                    .forEach(value -> inputs.merge(value.resourceId(), value.amount(), Math::addExact));
        }

        ExecutionReadinessContext context() {
            return new ExecutionReadinessContext(
                    session, runtime, adapters, implementations, id("minecraft:overworld"),
                    world, authoritative, loaded, snapshot, inputs, power, stress, budget,
                    journal, rollback, recovery, active);
        }
    }
}
