package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DeploymentRiskAssessmentTest {
    @Test
    void completeRiskMatrixIsDeterministicAndCriticalBlocksApproval() {
        DeploymentPreview preview = preview(true);
        Map<ResourceId, Long> forward = new LinkedHashMap<>();
        forward.put(id("minecraft:cobblestone"), 1L);
        forward.put(id("minecraft:iron_ingot"), 0L);
        Map<ResourceId, Long> reverse = new LinkedHashMap<>();
        reverse.put(id("minecraft:iron_ingot"), 0L);
        reverse.put(id("minecraft:cobblestone"), 1L);

        DeploymentRiskAssessment one = new DeploymentRiskAssessor().assess(
                preview, riskyContext(forward));
        DeploymentRiskAssessment two = new DeploymentRiskAssessor().assess(
                preview, riskyContext(reverse));

        assertThat(one).isEqualTo(two);
        assertThat(one.findings()).extracting(DeploymentRiskFinding::category)
                .containsExactly(DeploymentRiskCategory.values());
        assertThat(one.findings()).extracting(DeploymentRiskFinding::severity)
                .contains(RiskSeverity.INFO, RiskSeverity.LOW, RiskSeverity.MEDIUM,
                        RiskSeverity.HIGH, RiskSeverity.CRITICAL);
        assertThat(one.highestSeverity()).isEqualTo(RiskSeverity.CRITICAL);
        assertThat(one.approvalBlocked()).isTrue();
        assertThat(one.findings().stream()
                .filter(finding -> finding.severity() == RiskSeverity.CRITICAL))
                .allMatch(DeploymentRiskFinding::blocksApproval);
    }

    @Test
    void safeFactsProduceOnlyInformationalPlacementEvidenceAndDoNotBlockApproval() {
        DeploymentPreview preview = preview(false);
        DeploymentRiskContext context = new DeploymentRiskContext(
                id("minecraft:overworld"), true, false, false, List.of(), List.of(),
                false, 0, 64, false,
                Map.of(id("minecraft:cobblestone"), 4L, id("minecraft:iron_ingot"), 2L),
                true, false, DeploymentPermissionRisk.ALLOWED, true, true,
                preview.runtimeFingerprint());

        DeploymentRiskAssessment assessment = new DeploymentRiskAssessor().assess(preview, context);

        assertThat(assessment.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.category()).isEqualTo(DeploymentRiskCategory.BLOCK_PLACEMENT_COUNT);
            assertThat(finding.severity()).isEqualTo(RiskSeverity.INFO);
            assertThat(finding.blocksApproval()).isFalse();
        });
        assertThat(assessment.highestSeverity()).isEqualTo(RiskSeverity.INFO);
        assertThat(assessment.approvalBlocked()).isFalse();
    }

    @Test
    void riskContractIsExactTypedAndContainsNoLlmOrGameAuthority() {
        assertThat(Arrays.stream(DeploymentRiskCategory.values()).map(Enum::name)
                .collect(Collectors.toSet())).containsExactlyInAnyOrder(
                        "BLOCK_PLACEMENT_COUNT", "BLOCK_REMOVAL_COUNT",
                        "UNKNOWN_BLOCK_REPLACEMENT", "PLAYER_BUILDING_OVERWRITE",
                        "BLOCK_ENTITY", "CONTAINER_CONTENTS", "FLUID",
                        "FIRE_OR_LAVA", "EXPLOSION", "DROPPED_ENTITY",
                        "CHUNK_BOUNDARY", "DIMENSION", "POWER_OVERLOAD",
                        "LOGISTICS_CONGESTION", "RESOURCE_SHORTAGE",
                        "INCOMPLETE_ROLLBACK", "RELOAD_RECOVERY",
                        "MULTI_NODE_RESOURCE_HISTORY", "CLAIM_PERMISSION_UNKNOWN",
                        "BACKUP_UNAVAILABLE", "SNAPSHOT_STALE",
                        "RUNTIME_FINGERPRINT_CHANGED");
        assertThat(Arrays.stream(RiskSeverity.values()).map(Enum::name))
                .containsExactly("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(Arrays.stream(new Class<?>[] {
                    DeploymentRiskAssessment.class, DeploymentRiskFinding.class,
                    DeploymentRiskContext.class, DeploymentRiskAssessor.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.toLowerCase().contains("llm")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
        assertThatThrownBy(() -> new DeploymentRiskFinding(
                DeploymentRiskCategory.BACKUP_UNAVAILABLE, RiskSeverity.CRITICAL,
                "backup", "missing", "create backup", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeploymentRiskFinding(
                DeploymentRiskCategory.FLUID, RiskSeverity.HIGH,
                "fluid", "present", "contain", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeploymentRiskContext riskyContext(Map<ResourceId, Long> available) {
        return new DeploymentRiskContext(
                id("minecraft:the_nether"), false, true, true,
                List.of(new BlockPos3i(16, 64, 16)), List.of(new BlockPos3i(17, 64, 16)),
                true, 3, 1, true, available, false, true,
                DeploymentPermissionRisk.UNKNOWN, false, false, "runtime:changed");
    }

    private static DeploymentPreview preview(boolean risky) {
        BlockPos3i first = new BlockPos3i(15, 64, 15);
        BlockPos3i second = risky ? new BlockPos3i(16, 64, 16) : first;
        DeploymentBlockObservation container = new DeploymentBlockObservation(
                second, id("minecraft:chest"), true, true, true);
        List<DeploymentBlockObservation> riskyObservations = risky ? List.of(container) : List.of();
        List<DeploymentBlockReplacement> replacements = risky
                ? List.of(new DeploymentBlockReplacement(
                        second, id("minecraft:stone"), id("fixture:machine"))) : List.of();
        return new DeploymentPreview(
                id("fixture:target"), 2, List.of(id("fixture:recipe")),
                List.of(id("fixture:implementation")), first, List.of(QuarterTurn.ZERO),
                new DeploymentBoundingBox(first, second),
                List.of(new DeploymentBlockPlacement(first, id("fixture:machine"), Map.of())),
                risky ? List.of(new DeploymentBlockObservation(
                        second, id("minecraft:stone"), false, false, false)) : List.of(),
                replacements, riskyObservations, riskyObservations,
                List.of(new DeploymentRoutePreview(
                        id("fixture:item_route"), GenericResourceType.ITEM, 2, 2,
                        List.of(first, second))),
                List.of(new DeploymentRoutePreview(
                        id("fixture:power_route"), GenericResourceType.ROTATIONAL_POWER, 8, 16,
                        List.of(first, second))),
                Map.of(id("fixture:machine"), 1L),
                Map.of(id("minecraft:cobblestone"), 4L, id("minecraft:iron_ingot"), 2L),
                Map.of(id("fixture:machine"), 1L), Map.of(id("fixture:target"), 2L),
                OptionalLong.of(200), 8, List.of("bounded source"), 2,
                risky ? RollbackClassification.PARTIAL : RollbackClassification.FULLY_REVERSIBLE,
                WorldEnvironmentType.ISOLATED_TEST_WORLD, "runtime:current", "snapshot:0",
                List.of(), List.of("HUMAN_APPROVAL"),
                risky ? List.of("UNKNOWN_BLOCK_REPLACEMENT_FORBIDDEN") : List.of(),
                "0".repeat(64));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
