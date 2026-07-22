package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.deployment.DeploymentBudgetContext;
import dev.stevecreate.agent.core.deployment.DeploymentBudgetService;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunCommandFormatter;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunReport;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunSection;
import dev.stevecreate.agent.core.deployment.DeploymentPermissionRisk;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewService;
import dev.stevecreate.agent.core.deployment.DeploymentRiskAssessor;
import dev.stevecreate.agent.core.deployment.DeploymentRiskContext;
import dev.stevecreate.agent.core.deployment.ResourceSourcePolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeploymentDryRunCommandFormatterTest {
    @Test
    void everyViewRetainsHashEnvironmentBoundsBudgetsRisksAndHardSafetyFlags() {
        var physical = DeploymentPreviewServiceTest.physicalPlan();
        var context = DeploymentPreviewServiceTest.context(Map.of());
        var preview = new DeploymentPreviewService().preview(physical, context);
        var risk = new DeploymentRiskAssessor().assess(preview, new DeploymentRiskContext(
                ResourceId.parse("minecraft:overworld"), true, false, false, List.of(),
                List.of(), false, 0, preview.stressDemand(), false, Map.of(), true,
                false, DeploymentPermissionRisk.UNKNOWN, false, true,
                preview.runtimeFingerprint()));
        var budget = new DeploymentBudgetService().calculate(preview, context.policy(),
                new DeploymentBudgetContext(Map.of(), Map.of(), Map.of(), preview.stressDemand(),
                        200, 4096, 128, ResourceSourcePolicy.TEST_FIXTURE_PROVIDED, true));
        var report = new DeploymentDryRunReport(
                preview, risk, budget, preview.orientations().get(0),
                List.of("REGION_AUTHORIZATION", "CLAIM_PERMISSION", "HUMAN_APPROVAL"),
                List.of("BACKUP_PLAN", "BACKUP_MANIFEST", "RESTORE_VERIFICATION"),
                List.of("CRITICAL_RISK", "AUTHORIZATION_MISSING", "BACKUP_MISSING"),
                true, false, false, false, false, false, false, false);

        var formatter = new DeploymentDryRunCommandFormatter();
        for (DeploymentDryRunSection section : DeploymentDryRunSection.values()) {
            var formatted = formatter.format(section, report);
            assertThat(formatted.userLines().get(0))
                    .contains("previewHash=" + preview.previewHash(),
                            "environment=ISOLATED_TEST_WORLD", "formalWorldExecutable=false");
            assertThat(formatted.userLines().get(formatted.userLines().size() - 1))
                    .contains("worldMutation=false", "sessionCreated=false",
                            "playerItemsConsumed=false", "machineStarted=false",
                            "llmCalled=false", "freeTextCoordinatesAccepted=false");
            assertThat(formatted.structuredLog()).contains(
                    "bounds=", "materials=", "powerDemand=", "risk=",
                    "missingAuthorization=", "missingBackup=", "readiness=BLOCKED");
        }
    }

    @Test
    void reportTypesContainNoGameSessionExecutorResourceOrLlmAuthority() {
        assertThat(Arrays.stream(new Class<?>[] {
                    DeploymentDryRunReport.class, DeploymentDryRunCommandFormatter.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create")
                        || name.contains("ExecutionReadyPlan")
                        || name.contains("GenericExecutionSession")
                        || name.contains("WorldResourceBuffer")
                        || name.toLowerCase().contains("llm"));
    }

    @Test
    void reportConstructionRejectsFormalExecutionAuthority() {
        var physical = DeploymentPreviewServiceTest.physicalPlan();
        var context = DeploymentPreviewServiceTest.context(Map.of());
        var preview = new DeploymentPreviewService().preview(physical, context);
        var risk = new DeploymentRiskAssessor().assess(preview, new DeploymentRiskContext(
                ResourceId.parse("minecraft:overworld"), true, false, false, List.of(),
                List.of(), false, 0, preview.stressDemand(), false, Map.of(), true,
                false, DeploymentPermissionRisk.UNKNOWN, false, true,
                preview.runtimeFingerprint()));
        var budget = new DeploymentBudgetService().calculate(preview, context.policy(),
                new DeploymentBudgetContext(Map.of(), Map.of(), Map.of(), preview.stressDemand(),
                        200, 4096, 128, ResourceSourcePolicy.TEST_FIXTURE_PROVIDED, true));

        assertThatThrownBy(() -> new DeploymentDryRunReport(
                preview, risk, budget, preview.orientations().get(0),
                List.of("REGION_AUTHORIZATION"), List.of("BACKUP_PLAN"),
                List.of("FORMAL_WORLD_FORBIDDEN"), true, true,
                false, false, false, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PW-12 report cannot carry execution authority");
    }
}
