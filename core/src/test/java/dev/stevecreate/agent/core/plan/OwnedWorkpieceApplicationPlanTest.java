package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import org.junit.jupiter.api.Test;

class OwnedWorkpieceApplicationPlanTest {
    private static final BlockPos3i WORKPIECE = new BlockPos3i(5, 65, 5);
    private static final BlockPos3i RESOURCE_BUFFER = new BlockPos3i(2, 64, 2);
    private static final BlockPos3i MINIMUM = new BlockPos3i(0, 60, 0);
    private static final BlockPos3i MAXIMUM = new BlockPos3i(10, 70, 10);

    @Test
    void factoryBindsFixedDownwardMachineToOwnedWorkpiece() {
        OwnedWorkpieceApplicationPlan plan = plan();

        assertThat(plan.deployerPosition()).isEqualTo(new BlockPos3i(5, 67, 5));
        assertThat(plan.drivePosition()).isEqualTo(new BlockPos3i(5, 67, 6));
        assertThat(plan.resourceBufferPosition()).isEqualTo(RESOURCE_BUFFER);
        assertThat(plan.ownedMutationPositions())
                .containsExactly(
                        new BlockPos3i(5, 67, 6),
                        new BlockPos3i(5, 67, 5),
                        WORKPIECE);
    }

    @Test
    void shiftedMachineOrRegionEscapeIsRejected() {
        OwnedWorkpieceApplicationPlan plan = plan();
        assertThatThrownBy(() -> new OwnedWorkpieceApplicationPlan(
                plan.policy(),
                        plan.resourceBufferPosition(),
                        plan.drivePosition().translate(1, 0, 0),
                        plan.deployerPosition()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed downward");

        assertThatThrownBy(() -> OwnedWorkpieceApplicationPlan.andesiteCasing(
                        id("plan:c10_owned_workpiece"),
                        id("session:c10_owned_workpiece"),
                        WORKPIECE,
                        RESOURCE_BUFFER,
                        WORKPIECE,
                        WORKPIECE.translate(0, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");

        assertThatThrownBy(() -> new OwnedWorkpieceApplicationPlan(
                        plan.policy(), WORKPIECE,
                        plan.drivePosition(), plan.deployerPosition()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource buffer overlaps");
    }

    private static OwnedWorkpieceApplicationPlan plan() {
        return OwnedWorkpieceApplicationPlan.andesiteCasing(
                id("plan:c10_owned_workpiece"),
                id("session:c10_owned_workpiece"),
                WORKPIECE,
                RESOURCE_BUFFER,
                MINIMUM,
                MAXIMUM);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
