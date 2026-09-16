package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import org.junit.jupiter.api.Test;

class DeployerGenericExecutionPlanTest {
    @Test
    void exposesOneSharedBoundedRunnerPlanWithTwoExactInputs() {
        DeployerPlan physical =
                DeployerPlan.cogwheel(new BlockPos3i(1, 2, 3));
        var generic = DeployerGenericExecutionPlan.from(physical);

        assertThat(generic.planId())
                .isEqualTo(DeployerGenericExecutionPlan.PLAN_ID);
        assertThat(generic.machineGraph().nodes()).hasSize(24);
        assertThat(generic.machineGraph().edges()).hasSize(9);
        assertThat(generic.steps())
                .extracting(value -> value.phase())
                .containsExactly(
                        GenericExecutionPhase.BUILD,
                        GenericExecutionPhase.POWER,
                        GenericExecutionPhase.FEED_INPUT,
                        GenericExecutionPhase.PROCESS);
        assertThat(generic.processSpec().inputs()).hasSize(1);
        assertThat(generic.machineGraph().nodes().values())
                .filteredOn(value -> "true".equals(
                        value.configuration().get("boundary")))
                .hasSize(2);
    }

    @Test
    void requiresHeldItemAndActualDepotOutputEvidence() {
        var rules = DeployerGenericExecutionPlan.verificationRules(
                DeployerPlan.cogwheel(new BlockPos3i(0, 0, 0)));
        var process = rules.get(
                DeployerGenericExecutionPlan.PROCESS_STEP_ID);

        assertThat(process.requiredEvidence())
                .extracting(value -> value.requirementId())
                .contains(
                        DeployerGenericExecutionPlan
                                .HELD_ITEM_BEFORE_AFTER,
                        DeployerGenericExecutionPlan
                                .DEPOT_ITEM_OBSERVED,
                        DeployerGenericExecutionPlan
                                .DEPLOYER_CYCLE_OBSERVED,
                        DeployerGenericExecutionPlan
                                .CHEST_OUTPUT_OBSERVED);
        assertThat(process.requiredEvidence())
                .extracting(value -> value.kind())
                .contains(
                        VerificationEvidenceKind
                                .CUSTOM_ADAPTER_EVIDENCE,
                        VerificationEvidenceKind.OUTPUT_STORED);
    }

    @Test
    void carriesEveryImmediateSafetyRefusalIntoTheMachineNode() {
        var node = DeployerGenericExecutionPlan.from(
                        DeployerPlan.cogwheel(
                                new BlockPos3i(0, 0, 0)))
                .machineGraph()
                .nodes()
                .values()
                .stream()
                .filter(value -> value.id().equals(
                        DeployerGenericExecutionPlan.DEPLOYER_NODE_ID))
                .findFirst()
                .orElseThrow();

        for (String key : new String[] {
                "arbitrary_block_use",
                "entity_interaction",
                "player_inventory",
                "private_storage",
                "unknown_nbt_mutation"}) {
            assertThat(node.configuration()).containsEntry(key, "forbidden");
        }
        assertThat(node.configuration())
                .containsEntry("interaction_target", "owned_depot_item")
                .containsEntry("interaction_face", "down");
        assertThat(node.implementationId())
                .isEqualTo(ResourceId.parse("create:deployer"));
    }
}
