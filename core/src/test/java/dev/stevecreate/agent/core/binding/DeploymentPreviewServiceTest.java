package dev.stevecreate.agent.core.binding;

import static dev.stevecreate.agent.core.binding.BindingTestFixtures.FINGERPRINT;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.catalog;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.descriptor;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.id;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.singlePlan;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.DeploymentBlockObservation;
import dev.stevecreate.agent.core.deployment.DeploymentPolicy;
import dev.stevecreate.agent.core.deployment.DeploymentPreview;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewContext;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewService;
import dev.stevecreate.agent.core.deployment.RollbackClassification;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentDescriptor;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.layout.ClearanceVolume;
import dev.stevecreate.agent.core.layout.GeometryComponent;
import dev.stevecreate.agent.core.layout.ImmutableMachineGeometryCatalog;
import dev.stevecreate.agent.core.layout.LayoutCellState;
import dev.stevecreate.agent.core.layout.LayoutConstraints;
import dev.stevecreate.agent.core.layout.MachineFootprint;
import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.layout.PhysicalPortRule;
import dev.stevecreate.agent.core.layout.PhysicalizationResult;
import dev.stevecreate.agent.core.layout.PhysicalizationService;
import dev.stevecreate.agent.core.layout.PhysicalizationSuccess;
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
import org.junit.jupiter.api.Test;

class DeploymentPreviewServiceTest {
    private static final BlockPos3i ANCHOR = new BlockPos3i(100, 64, 100);

    @Test
    void isolatedDryRunContainsTheCompleteAuditablePhysicalChainWithoutCreatingAuthority() {
        VerifiedPhysicalPlan physical = physicalPlan();
        BlockPos3i target = physical.placements().get(0).components().get(0).position();
        DeploymentPreview preview = new DeploymentPreviewService().preview(
                physical, context(Map.of(target, observation(target, "minecraft:tall_grass"))));

        assertThat(preview.target()).isEqualTo(id("fixture:dust"));
        assertThat(preview.quantity()).isEqualTo(2);
        assertThat(preview.recipeIds()).isNotEmpty();
        assertThat(preview.implementationIds()).containsExactly(id("fixture:mill"));
        assertThat(preview.anchor()).isEqualTo(ANCHOR);
        assertThat(preview.orientations()).containsExactly(QuarterTurn.ZERO);
        assertThat(preview.affectedBounds().contains(target)).isTrue();
        assertThat(preview.plannedPlacements()).hasSize(1);
        assertThat(preview.plannedRemovals()).isEmpty();
        assertThat(preview.plannedReplacements()).hasSize(1);
        assertThat(preview.protectedBlocksEncountered()).isEmpty();
        assertThat(preview.blockEntitiesEncountered()).isEmpty();
        assertThat(preview.itemRoutes()).isNotEmpty();
        assertThat(preview.rotationalPowerRoutes()).singleElement().satisfies(route ->
                assertThat(route.positions()).hasSize(2));
        assertThat(preview.materialBillOfMaterials()).containsEntry(id("fixture:machine_block"), 1L);
        assertThat(preview.materialBillOfMaterials()).containsEntry(id("fixture:route_component"), 3L);
        assertThat(preview.requiredInputResources()).isNotEmpty();
        assertThat(preview.machineConstructionMaterials()).containsOnlyKeys(id("fixture:machine_block"));
        assertThat(preview.expectedOutput()).containsEntry(id("fixture:dust"), 2L);
        assertThat(preview.estimatedTicks()).isPresent();
        assertThat(preview.stressDemand()).isEqualTo(8);
        assertThat(preview.powerSourceAssumptions()).isNotEmpty();
        assertThat(preview.journalEstimate()).isPositive();
        assertThat(preview.rollbackClassification()).isEqualTo(RollbackClassification.FULLY_REVERSIBLE);
        assertThat(preview.environmentClassification()).isEqualTo(WorldEnvironmentType.ISOLATED_TEST_WORLD);
        assertThat(preview.runtimeFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(preview.worldSnapshotFingerprint()).isEqualTo("snapshot:fixture:0");
        assertThat(preview.requiredApprovals()).contains("HUMAN_APPROVAL", "VERIFIED_BACKUP");
        assertThat(preview.previewHash()).matches("[0-9a-f]{64}");
        assertThat(preview.hasValidHash()).isTrue();
        assertThat(preview.canonicalJson()).contains("\"previewHash\":\"" + preview.previewHash() + "\"");
    }

    @Test
    void previewAndSerializationAreDeterministicAcrossObservationMapOrder() {
        VerifiedPhysicalPlan physical = physicalPlan();
        BlockPos3i first = physical.placements().get(0).components().get(0).position();
        BlockPos3i second = physical.routes().get(0).positions().get(0);
        Map<BlockPos3i, DeploymentBlockObservation> forward = new LinkedHashMap<>();
        forward.put(first, observation(first, "minecraft:air"));
        forward.put(second, observation(second, "minecraft:air"));
        Map<BlockPos3i, DeploymentBlockObservation> reverse = new LinkedHashMap<>();
        reverse.put(second, observation(second, "minecraft:air"));
        reverse.put(first, observation(first, "minecraft:air"));

        DeploymentPreview one = new DeploymentPreviewService().preview(physical, context(forward));
        DeploymentPreview two = new DeploymentPreviewService().preview(physical, context(reverse));

        assertThat(one).isEqualTo(two);
        assertThat(one.previewHash()).isEqualTo(two.previewHash());
        assertThat(one.canonicalJson()).isEqualTo(two.canonicalJson());
    }

    @Test
    void contentAndSnapshotChangesProduceDifferentSelfValidatingHashes() {
        VerifiedPhysicalPlan physical = physicalPlan();
        BlockPos3i target = physical.placements().get(0).components().get(0).position();
        DeploymentPreview baseline = new DeploymentPreviewService().preview(
                physical, context(Map.of(target, observation(target, "minecraft:air"))));
        DeploymentPreview contentChanged = new DeploymentPreviewService().preview(
                physical, context(Map.of(target, observation(target, "minecraft:tall_grass"))));
        DeploymentPreviewContext snapshotChangedContext = new DeploymentPreviewContext(
                context(Map.of()).environment(), context(Map.of()).policy(), "snapshot:fixture:1",
                Map.of(target, observation(target, "minecraft:air")),
                Map.of(id("fixture:route_component"), 3L),
                RollbackClassification.FULLY_REVERSIBLE,
                List.of("fixture bounded rotational source"));
        DeploymentPreview snapshotChanged = new DeploymentPreviewService().preview(
                physical, snapshotChangedContext);

        assertThat(contentChanged.previewHash()).isNotEqualTo(baseline.previewHash());
        assertThat(snapshotChanged.previewHash()).isNotEqualTo(baseline.previewHash());
        assertThat(contentChanged.hasValidHash()).isTrue();
        assertThat(snapshotChanged.hasValidHash()).isTrue();
    }

    @Test
    void protectedContainerObservationIsPreservedAsAuditableRiskEvidence() {
        VerifiedPhysicalPlan physical = physicalPlan();
        BlockPos3i target = physical.placements().get(0).components().get(0).position();
        DeploymentBlockObservation container = new DeploymentBlockObservation(
                target, id("minecraft:chest"), true, true, true);

        DeploymentPreview preview = new DeploymentPreviewService().preview(
                physical, context(Map.of(target, container)));

        assertThat(preview.protectedBlocksEncountered()).containsExactly(container);
        assertThat(preview.blockEntitiesEncountered()).containsExactly(container);
        assertThat(preview.riskFindings()).contains(
                "PROTECTED_BLOCKS_ENCOUNTERED", "BLOCK_ENTITIES_ENCOUNTERED",
                "CONTAINER_CONTENTS_ENCOUNTERED");
    }

    @Test
    void formalWorldCanOnlyProduceAZeroAuthorityPreviewWithExplicitViolations() {
        VerifiedPhysicalPlan physical = physicalPlan();
        WorldEnvironmentDescriptor formal = new WorldEnvironmentDescriptor(
                "formal", WorldEnvironmentType.FORMAL_PLAYER_WORLD, "world",
                "formal-root/world", "formal-root", "1.20.1", "forge",
                FINGERPRINT, "save", "server", false, false, true, true, false,
                "test", List.of("formal root match"), List.of());
        DeploymentPreviewContext context = new DeploymentPreviewContext(
                formal, DeploymentPolicy.formalWorldDryRunOnly("formal-root"),
                "snapshot:formal:read-only", Map.of(),
                Map.of(id("fixture:route_component"), 3L),
                RollbackClassification.UNSUPPORTED, List.of("unverified read-only assumption"));

        DeploymentPreview preview = new DeploymentPreviewService().preview(physical, context);

        assertThat(preview.environmentClassification()).isEqualTo(WorldEnvironmentType.FORMAL_PLAYER_WORLD);
        assertThat(preview.riskFindings()).contains("FORMAL_WORLD_PREVIEW_ONLY");
        assertThat(preview.policyViolations()).contains(
                "ENVIRONMENT_NOT_ALLOWED", "MUTATION_BUDGET_EXCEEDED", "ROLLBACK_UNSUPPORTED");
        assertThat(preview.hasValidHash()).isTrue();
    }

    @Test
    void previewTypesContainNoWorldSessionRunnerResourceOrMutationAuthority() {
        assertThat(Arrays.stream(new Class<?>[] {
                    DeploymentPreview.class, DeploymentPreviewContext.class,
                    DeploymentPreviewService.class, DeploymentBlockObservation.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.contains("GenericExecutionSession")
                        || name.contains("BoundedStepRunner")
                        || name.contains("WorldResourceBuffer")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
    }

    static DeploymentPreviewContext context(
            Map<BlockPos3i, DeploymentBlockObservation> observations) {
        WorldEnvironmentDescriptor environment = new WorldEnvironmentDescriptor(
                "isolated", WorldEnvironmentType.ISOLATED_TEST_WORLD, "world",
                "isolated-root/world", "isolated-root", "1.20.1", "forge",
                FINGERPRINT, "save", "server", true, true, false, false, true,
                "test", List.of("repository marker"), List.of());
        return new DeploymentPreviewContext(
                environment, DeploymentPolicy.isolatedTestDefault("isolated-root", "formal-root"),
                "snapshot:fixture:0", observations, Map.of(id("fixture:route_component"), 3L),
                RollbackClassification.FULLY_REVERSIBLE,
                List.of("fixture bounded rotational source"));
    }

    private static DeploymentBlockObservation observation(BlockPos3i position, String blockId) {
        return new DeploymentBlockObservation(
                position, id(blockId), false, false, false);
    }

    static VerifiedPhysicalPlan physicalPlan() {
        VerifiedImplementationBoundPlan bound = ((BindingResult.Success)
                new ImplementationBindingService().bind(
                        singlePlan(), catalog(descriptor(
                                "fixture:mill", "industrial:milling", "fixture:milling", 0)),
                        BindingTestFixtures.constraints())).plan();
        ResourceId implementation = id("fixture:mill");
        MachineGeometryDescriptor geometry = new MachineGeometryDescriptor(
                implementation,
                new MachineFootprint(Set.of(new BlockPos3i(0, 0, 0))),
                new ClearanceVolume(Set.of(
                        new BlockPos3i(-1, 0, 0), new BlockPos3i(0, 0, 0),
                        new BlockPos3i(1, 0, 0), new BlockPos3i(0, 1, 0))),
                Set.of(QuarterTurn.ZERO),
                List.of(new GeometryComponent(
                        id("fixture:machine_role"), id("fixture:machine_block"),
                        new BlockPos3i(0, 0, 0), Map.of("facing", "north"))),
                List.of(
                        port("fixture:mill_input", -1, Direction6.WEST, PortMode.INPUT, 64),
                        port("fixture:mill_output", 1, Direction6.EAST, PortMode.OUTPUT, 64),
                        new PhysicalPortRule(id("fixture:mill_power"), new BlockPos3i(0, 0, 0),
                                Optional.of(Direction6.SOUTH), GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32)),
                List.of(new BlockPos3i(0, 0, 1), new BlockPos3i(0, 0, 0)),
                32, 8, "fixture:power->machine");
        PlacementSnapshot snapshot = new PlacementSnapshot(FINGERPRINT, 0, snapshotCells());
        LayoutConstraints constraints = new LayoutConstraints(
                ANCHOR, List.of(QuarterTurn.ZERO), 1, 64, 64, 100_000,
                64, 64, 64, snapshot);
        PhysicalizationResult result = new PhysicalizationService().physicalize(
                bound, new ImmutableMachineGeometryCatalog(List.of(geometry), FINGERPRINT), constraints);
        assertThat(result).isInstanceOf(PhysicalizationSuccess.class);
        return ((PhysicalizationSuccess) result).plan();
    }

    private static PhysicalPortRule port(
            String id, int x, Direction6 side, PortMode mode, long capacity) {
        return new PhysicalPortRule(
                BindingTestFixtures.id(id), new BlockPos3i(x, 1, 0), Optional.of(side),
                GenericResourceType.ITEM, mode, capacity);
    }

    private static Map<BlockPos3i, LayoutCellState> snapshotCells() {
        Map<BlockPos3i, LayoutCellState> cells = new LinkedHashMap<>();
        for (int x = 80; x <= 130; x++) {
            for (int y = 60; y <= 70; y++) {
                for (int z = 80; z <= 130; z++) {
                    cells.put(new BlockPos3i(x, y, z), LayoutCellState.REPLACEABLE);
                }
            }
        }
        return cells;
    }
}
