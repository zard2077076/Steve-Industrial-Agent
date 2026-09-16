package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.plan.DeployerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.DeployerPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CreateV606DeployerSafetyTest {
    @Test
    void handlerHasNoGenericPlayerEntityBlockUseOrTeleportPath()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/stevecreate/agent/forge1201/"
                        + "adapter/create/internal/v606/"
                        + "Create606DeployerActionHandler.java"));

        assertThat(source)
                .doesNotContain(
                        ".getPlayer(",
                        "InteractionHand",
                        "BlockHitResult",
                        ".openMenu(",
                        ".interactAt(",
                        ".interactOn(",
                        ".hurt(",
                        ".teleportTo(",
                        "getHandOffset(",
                        "Thread.sleep(",
                        "D:\\PCL2")
                .contains(
                        "deployer.getRecipe(staged)",
                        "depot.setHeldItem(processed.copy())",
                        "heldItemBeforeCount",
                        "heldAfterCount",
                        "current.hasTag()",
                        "owned Deployer, Depot and output chest must be empty");
    }

    @Test
    void handlerGraphCarriesAllImmediateRefusals() {
        var graph = DeployerGenericExecutionPlan.from(
                DeployerPlan.cogwheel(
                        new BlockPos3i(0, 64, 0)))
                .machineGraph();
        var deployer = graph.node(
                DeployerGenericExecutionPlan.DEPLOYER_NODE_ID);

        assertThat(deployer.configuration())
                .containsEntry("interaction_target", "owned_depot_item")
                .containsEntry("interaction_face", "down")
                .containsEntry("entity_interaction", "forbidden")
                .containsEntry("arbitrary_block_use", "forbidden")
                .containsEntry("player_inventory", "forbidden")
                .containsEntry("private_storage", "forbidden")
                .containsEntry("unknown_nbt_mutation", "forbidden");
    }
}
