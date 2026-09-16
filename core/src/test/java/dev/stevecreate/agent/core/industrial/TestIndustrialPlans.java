package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.execution.construction.ProjectMaterialDisposition;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLine;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialPurpose;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;

final class TestIndustrialPlans {
    private TestIndustrialPlans() {}

    static VerifiedProjectMaterialPlan material(ResourceId project, ResourceId target) {
        ProjectMaterialLine line = new ProjectMaterialLine(id("material:line"),
                id("minecraft:iron_ingot"), List.of(id("minecraft:iron_ingot")), 1,
                ProjectMaterialPurpose.PROCESS_INPUT, ProjectMaterialDisposition.CONSUME,
                List.of(), "test");
        return new VerifiedProjectMaterialPlan(id("material:plan"), project, target, 1,
                id("physical:plan"), "runtime", "a".repeat(64), List.of(line));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
