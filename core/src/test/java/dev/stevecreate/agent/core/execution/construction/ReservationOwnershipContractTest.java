package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.PLAN_ID;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.TOKEN_HASH;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.ownership;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.simpleGraph;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReservationOwnershipContractTest {
    @Test
    void botOwnershipAndPlacementReservationRequireExactWorkerAndCurrentLease() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));

        assertThatThrownBy(() -> new TaskOwnership(
                id("session:test"), graph.graphId(), task.taskId(), id("executor:bots"),
                ExecutionMode.BOTS, Optional.empty(), 0, 10, 20, TOKEN_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker identity");
        assertThatThrownBy(() -> new TaskOwnership(
                id("session:test"), graph.graphId(), task.taskId(), id("executor:direct"),
                ExecutionMode.DIRECT, Optional.of(id("worker:one")), 0, 10, 20, TOKEN_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot claim a Bot worker");

        assertThatThrownBy(() -> TaskAssignment.assign(
                graph,
                task.taskId(),
                id("assignment:expired"),
                ownership(graph, task, ExecutionMode.DIRECT, 10, 20),
                1,
                20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid");

        PlacementReservation reservation = new PlacementReservation(
                id("reservation:position"),
                PLAN_ID,
                id("session:test"),
                task.taskId(),
                new BlockPos3i(1, 2, 3),
                ExecutionMode.BOTS,
                Optional.of(id("worker:one")),
                ReservationStatus.ACTIVE,
                1,
                10,
                20);
        assertThat(reservation.activeAt(19)).isTrue();
        assertThat(reservation.activeAt(20)).isFalse();
    }

    @Test
    void materialLedgerCannotDoubleWithdrawDeliverOrReturn() {
        MaterialReservation reservation = new MaterialReservation(
                id("reservation:material"),
                id("session:test"),
                id("task:fetch"),
                id("source:dedicated_chest"),
                MaterialSourceScope.AUTHORIZED_DEDICATED_CONTAINER,
                id("minecraft:andesite"),
                8,
                8,
                6,
                4,
                1,
                ReservationStatus.ACTIVE,
                1,
                20);

        assertThat(reservation.outstandingQuantity()).isEqualTo(1);
        assertThat(MaterialSourceScope.values())
                .containsExactly(
                        MaterialSourceScope.TEST_ONLY_BOUNDED_SOURCE,
                        MaterialSourceScope.AUTHORIZED_DEDICATED_CONTAINER);
        assertThatThrownBy(() -> new MaterialReservation(
                reservation.reservationId(), reservation.sessionId(), reservation.taskId(),
                reservation.sourceId(), reservation.sourceScope(), reservation.resourceId(),
                8, 8, 6, 6, 1, ReservationStatus.ACTIVE, 1, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantities");
    }

    @Test
    void sharedInfrastructureUsesReferenceCountedOwnersAndReleasedStateIsEmpty() {
        SharedInfrastructureReservation active = new SharedInfrastructureReservation(
                id("reservation:shared_power"),
                PLAN_ID,
                id("infrastructure:power_line"),
                Set.of(id("session:b"), id("session:a")),
                List.of(new BlockPos3i(2, 0, 0), new BlockPos3i(1, 0, 0)),
                ReservationStatus.ACTIVE,
                1,
                10);

        assertThat(active.referenceCount()).isEqualTo(2);
        assertThat(active.owningSessionIds())
                .containsExactly(id("session:a"), id("session:b"));
        assertThat(active.positions())
                .containsExactly(new BlockPos3i(1, 0, 0), new BlockPos3i(2, 0, 0));
        assertThatThrownBy(() -> new SharedInfrastructureReservation(
                active.reservationId(), active.verifiedPhysicalPlanId(), active.infrastructureId(),
                Set.of(id("session:a")), active.positions(), ReservationStatus.RELEASED, 2, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot retain owners");
    }

    @Test
    void recoveryRequiresExactEvidenceAndAlwaysForbidsResourceReplay() {
        assertThatThrownBy(() -> new RecoveryPolicy(
                RecoveryStrategy.EXACT_RESCAN_RESUME,
                1,
                true,
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryPolicy(
                RecoveryStrategy.PRE_MUTATION_RESTART,
                1,
                false,
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blindly replayed");

        RecoveryPolicy policy = new RecoveryPolicy(
                RecoveryStrategy.EXACT_RESCAN_REASSIGN,
                2,
                true,
                Set.of(id("evidence:world_state"), id("evidence:journal")));
        assertThat(policy.strategy().requiresExactRescan()).isTrue();
        assertThat(policy.strategy().allowsReassignment()).isTrue();
    }
}
