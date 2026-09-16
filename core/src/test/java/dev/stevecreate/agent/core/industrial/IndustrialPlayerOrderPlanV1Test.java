package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialDisposition;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLine;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialPurpose;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndustrialPlayerOrderPlanV1Test {
    @Test
    void oneEnvelopeCarriesMaterialPlanAndExecutionModeForAnyAdapter() {
        ResourceId project = id("project:one");
        ResourceId target = id("create:iron_sheet");
        VerifiedProjectMaterialPlan material = material(project, target);
        IndustrialPlayerOrderPlanV1 plan = new IndustrialPlayerOrderPlanV1(
                id("steve_industrial:player_create_pressing"), project, target, 1, material,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(IndustrialCapability.ITEM_PROCESSING), ExecutionMode.DIRECT, 0,
                "create-runtime", "b".repeat(64));

        assertThat(plan.fingerprint()).hasSize(64);
        assertThat(plan.capabilities()).containsExactly(IndustrialCapability.ITEM_PROCESSING);
        assertThat(plan.executionMode()).isEqualTo(ExecutionMode.DIRECT);
    }

    @Test
    void materialIdentityCannotDriftAwayFromThePlayerOrder() {
        VerifiedProjectMaterialPlan material = material(id("project:one"), id("create:iron_sheet"));
        assertThatThrownBy(() -> new IndustrialPlayerOrderPlanV1(
                id("steve_industrial:player_create_pressing"), id("project:other"),
                id("create:iron_sheet"), 1, material, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(IndustrialCapability.ITEM_PROCESSING),
                ExecutionMode.DIRECT, 0, "runtime", "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material plan identity");
    }

    @Test
    void rejectsTerminalRegressionAndAllowsSafetyPause() {
        IndustrialPlayerOrderV1 order = sampleOrder();
        IndustrialPlayerOrderV1 ready = order.checkpoint(
                IndustrialLifecyclePhase.READY, "MATERIALS_RESERVED", java.util.Set.of(), 2);
        IndustrialPlayerOrderV1 paused = ready.checkpoint(
                IndustrialLifecyclePhase.PAUSED, "RELOAD_RECONCILIATION_REQUIRED",
                java.util.Set.of(), 3);
        assertThat(paused.phase()).isEqualTo(IndustrialLifecyclePhase.PAUSED);
        assertThatThrownBy(() -> paused.checkpoint(
                IndustrialLifecyclePhase.DISCOVERED, "BAD", java.util.Set.of(), 4))
                .isInstanceOf(IllegalArgumentException.class);
        IndustrialPlayerOrderV1 cancelled = paused.checkpoint(
                IndustrialLifecyclePhase.CANCELLED, "CANCELLED_RETURN_PENDING",
                java.util.Set.of(), 5);
        assertThatThrownBy(() -> cancelled.checkpoint(
                IndustrialLifecyclePhase.RUNNING, "BAD", java.util.Set.of(), 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IndustrialPlayerOrderV1 sampleOrder() {
        return new IndustrialPlayerOrderV1(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "isolated-test", id("minecraft:overworld"),
                id("steve_industrial:test"), id("minecraft:iron_ingot"),
                id("minecraft:test"), new BlockPos3i(0, 0, 0), "a".repeat(64),
                "runtime", "b".repeat(64), IndustrialLifecyclePhase.PLANNED,
                "PLAN_BOUND", java.util.Set.of(), 1, 1, 0, Optional.empty());
    }

    private static VerifiedProjectMaterialPlan material(ResourceId project, ResourceId target) {
        ProjectMaterialLine line = new ProjectMaterialLine(id("material:line"),
                id("minecraft:iron_ingot"), List.of(id("minecraft:iron_ingot")), 1,
                ProjectMaterialPurpose.PROCESS_INPUT, ProjectMaterialDisposition.CONSUME,
                List.of(), "test");
        return new VerifiedProjectMaterialPlan(id("material:plan"), project, target, 1,
                id("physical:plan"), "runtime", "a".repeat(64), List.of(line));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
