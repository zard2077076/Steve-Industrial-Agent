package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DeploymentBudgetTest {
    @Test
    void calculatesEveryRequiredBudgetSectionDeterministically() {
        DeploymentPreview preview = preview(WorldEnvironmentType.ISOLATED_TEST_WORLD, 2, 8, 3);
        Map<ResourceId, Long> intermediates = new LinkedHashMap<>();
        intermediates.put(id("fixture:intermediate_b"), 2L);
        intermediates.put(id("fixture:intermediate_a"), 1L);
        DeploymentBudgetContext context = new DeploymentBudgetContext(
                intermediates,
                Map.of(id("fixture:power_component"), 1L),
                Map.of(id("fixture:logistics_component"), 2L),
                32, 80, 4_096, 128, ResourceSourcePolicy.TEST_FIXTURE_PROVIDED, true);

        DeploymentBudget budget = new DeploymentBudgetService().calculate(
                preview, DeploymentPolicy.isolatedTestDefault("isolated-root", "formal-root"), context);

        assertThat(budget.rawMaterialRequirements()).containsEntry(id("minecraft:cobblestone"), 4L);
        assertThat(budget.intermediateProductRequirements()).containsExactlyEntriesOf(Map.of(
                id("fixture:intermediate_a"), 1L, id("fixture:intermediate_b"), 2L));
        assertThat(budget.constructionBlockRequirements()).containsEntry(id("fixture:machine"), 2L);
        assertThat(budget.powerComponentRequirements()).containsEntry(id("fixture:power_component"), 1L);
        assertThat(budget.logisticsComponentRequirements()).containsEntry(id("fixture:logistics_component"), 2L);
        assertThat(budget.inputInventoryRequirements()).containsEntry(id("minecraft:cobblestone"), 4L)
                .containsEntry(id("fixture:intermediate_a"), 1L);
        assertThat(budget.expectedOutput()).containsEntry(id("fixture:target"), 2L);
        assertThat(budget.rotationalStressDemand()).isEqualTo(8);
        assertThat(budget.powerCapacityMargin()).isEqualTo(24);
        assertThat(budget.estimatedRuntimeTicks()).hasValue(200);
        assertThat(budget.maximumConstructionTicks()).isEqualTo(80);
        assertThat(budget.maximumAffectedBlocks()).isEqualTo(256);
        assertThat(budget.rollbackExtraSpaceBytes()).isEqualTo(4_096);
        assertThat(budget.journalSizeEstimateBytes()).isEqualTo(384);
        assertThat(budget.resourceSourcePolicy()).isEqualTo(ResourceSourcePolicy.TEST_FIXTURE_PROVIDED);
        assertThat(budget.policyViolations()).isEmpty();
        assertThat(budget.withinPolicy()).isTrue();
    }

    @Test
    void reportsEveryBudgetAndResourceSourceViolationWithoutWithdrawingAnything() {
        DeploymentPreview preview = preview(WorldEnvironmentType.ISOLATED_TEST_WORLD, 5_000, 5_000, 300);
        DeploymentBudgetContext context = new DeploymentBudgetContext(
                Map.of(), Map.of(id("fixture:not_in_bom"), 1L),
                Map.of(id("fixture:also_not_in_bom"), 1L), 1, 80, 0, 128,
                ResourceSourcePolicy.UNSUPPORTED_SOURCE, false);

        DeploymentBudget budget = new DeploymentBudgetService().calculate(
                preview, DeploymentPolicy.isolatedTestDefault("isolated-root", "formal-root"), context);

        assertThat(budget.policyViolations()).containsExactly(
                DeploymentBudgetViolation.AFFECTED_BLOCK_LIMIT_EXCEEDED,
                DeploymentBudgetViolation.MUTATION_BUDGET_EXCEEDED,
                DeploymentBudgetViolation.MATERIAL_BUDGET_EXCEEDED,
                DeploymentBudgetViolation.POWER_CAPACITY_INSUFFICIENT,
                DeploymentBudgetViolation.STRESS_BUDGET_EXCEEDED,
                DeploymentBudgetViolation.POWER_COMPONENT_NOT_IN_BOM,
                DeploymentBudgetViolation.LOGISTICS_COMPONENT_NOT_IN_BOM,
                DeploymentBudgetViolation.RESOURCE_SOURCE_POLICY_MISMATCH,
                DeploymentBudgetViolation.RESOURCE_SOURCE_UNSUPPORTED,
                DeploymentBudgetViolation.RESOURCE_SOURCE_NOT_READ_ONLY);
        assertThat(budget.withinPolicy()).isFalse();
    }

    @Test
    void formalWorldAcceptsOnlyNoWithdrawalOrReadOnlySourceSemantics() {
        DeploymentPreview formal = preview(WorldEnvironmentType.FORMAL_PLAYER_WORLD, 1, 8, 1);
        DeploymentBudgetContext forbidden = new DeploymentBudgetContext(
                Map.of(), Map.of(), Map.of(), 32, 10, 0, 64,
                ResourceSourcePolicy.DESIGNATED_CONTAINER, false);
        DeploymentBudgetContext safe = new DeploymentBudgetContext(
                Map.of(), Map.of(), Map.of(), 32, 10, 0, 64,
                ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN, true);
        DeploymentPolicy policy = DeploymentPolicy.formalWorldDryRunOnly("formal-root");

        assertThat(new DeploymentBudgetService().calculate(formal, policy, forbidden).policyViolations())
                .contains(DeploymentBudgetViolation.FORMAL_WORLD_AUTO_WITHDRAW_FORBIDDEN);
        assertThat(new DeploymentBudgetService().calculate(formal, policy, safe).policyViolations())
                .doesNotContain(DeploymentBudgetViolation.FORMAL_WORLD_AUTO_WITHDRAW_FORBIDDEN,
                        DeploymentBudgetViolation.RESOURCE_SOURCE_POLICY_MISMATCH,
                        DeploymentBudgetViolation.RESOURCE_SOURCE_NOT_READ_ONLY);
    }

    @Test
    void budgetContractIsExactLoaderNeutralAndResourceSourceSetIsStable() {
        assertThat(Arrays.stream(ResourceSourcePolicy.values()).map(Enum::name))
                .containsExactly("TEST_FIXTURE_PROVIDED", "PLAYER_PROVIDED_READ_ONLY_SNAPSHOT",
                        "DESIGNATED_CONTAINER", "EXISTING_NETWORK_READ_ONLY",
                        "AUTO_WITHDRAW_FORBIDDEN", "UNSUPPORTED_SOURCE");
        assertThat(Arrays.stream(DeploymentBudgetViolation.values()).map(Enum::name)
                .collect(Collectors.toSet())).containsExactlyInAnyOrder(
                        "AFFECTED_BLOCK_LIMIT_EXCEEDED", "MUTATION_BUDGET_EXCEEDED",
                        "MATERIAL_BUDGET_EXCEEDED", "POWER_CAPACITY_INSUFFICIENT",
                        "STRESS_BUDGET_EXCEEDED", "POWER_COMPONENT_NOT_IN_BOM",
                        "LOGISTICS_COMPONENT_NOT_IN_BOM", "RESOURCE_SOURCE_POLICY_MISMATCH",
                        "RESOURCE_SOURCE_UNSUPPORTED", "RESOURCE_SOURCE_NOT_READ_ONLY",
                        "FORMAL_WORLD_AUTO_WITHDRAW_FORBIDDEN");
        assertThat(Arrays.stream(new Class<?>[] {
                    DeploymentBudget.class, DeploymentBudgetContext.class,
                    DeploymentBudgetService.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.contains("WorldResourceBuffer")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
    }

    private static DeploymentPreview preview(
            WorldEnvironmentType environment, int materialCount, long stress, long affected) {
        BlockPos3i position = new BlockPos3i(100, 64, 100);
        Map<ResourceId, Long> materials = Map.of(
                id("fixture:machine"), (long) materialCount,
                id("fixture:power_component"), 1L,
                id("fixture:logistics_component"), 2L);
        return new DeploymentPreview(
                id("fixture:target"), 2, List.of(id("fixture:recipe")),
                List.of(id("fixture:implementation")), position, List.of(QuarterTurn.ZERO),
                new DeploymentBoundingBox(position, position),
                List.of(new DeploymentBlockPlacement(position, id("fixture:machine"), Map.of())),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new DeploymentRoutePreview(
                        id("fixture:item_route"), GenericResourceType.ITEM, 2, 2,
                        List.of(position, position))),
                List.of(new DeploymentRoutePreview(
                        id("fixture:power_route"), GenericResourceType.ROTATIONAL_POWER, 8, 32,
                        List.of(position, position))),
                materials, Map.of(id("minecraft:cobblestone"), 4L),
                Map.of(id("fixture:machine"), (long) materialCount),
                Map.of(id("fixture:target"), 2L), OptionalLong.of(200), stress,
                List.of("bounded source"), affected, RollbackClassification.FULLY_REVERSIBLE,
                environment, "runtime:current", "snapshot:0", List.of(), List.of(), List.of(),
                "0".repeat(64));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
