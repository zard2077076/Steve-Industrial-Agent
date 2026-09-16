package dev.stevecreate.agent.core.execution.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectMaterialLedgerTest {
    private static final String HASH = "a".repeat(64);
    private static final MaterialIdentity SHAFT = new MaterialIdentity(
            id("create:shaft"), MaterialIdentity.EMPTY_PAYLOAD_SHA256);

    @Test
    void twoProjectsCannotReserveTheSamePhysicalSlotAndItem() {
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ResourceId one = id("project:one");
        ResourceId two = id("project:two");
        ledger.registerPlan(plan(one, 12));
        ledger.registerPlan(plan(two, 12));
        ledger.bindSource(source(id("source:one"), one, 20), 1);
        ledger.bindSource(source(id("source:two"), two, 20), 1);

        ledger.reserve(allocation(id("allocation:one"), one, id("source:one"), 12), 2);
        assertThatThrownBy(() -> ledger.reserve(
                allocation(id("allocation:two"), two, id("source:two"), 12), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already reserved");
    }

    @Test
    void preparedWithdrawnDeliveredConsumedIsIdempotentAndBalancedAfterReload() {
        ResourceId project = id("project:main");
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan(project, 4));
        ledger.bindSource(source(id("source:main"), project, 8), 1);
        MaterialAllocation allocation = ledger.reserve(
                allocation(id("allocation:main"), project, id("source:main"), 4), 2);
        ResourceId transaction = id("transaction:main");
        ledger.prepare(transaction, project, id("task:17"), allocation.allocationId(),
                MaterialExecutorKind.BOT, 4, 3);
        assertThat(ledger.prepare(transaction, project, id("task:17"), allocation.allocationId(),
                MaterialExecutorKind.BOT, 4, 3).state()).isEqualTo(MaterialTransactionState.PREPARED);
        ledger.advance(transaction, MaterialTransactionState.WITHDRAWN, 4);
        ledger.advance(transaction, MaterialTransactionState.DELIVERED, 5);
        ledger.advance(transaction, MaterialTransactionState.CONSUMED, 6);
        assertThat(ledger.advance(transaction, MaterialTransactionState.CONSUMED, 7).generation())
                .isEqualTo(ledger.transactions().get(transaction).generation());

        ProjectMaterialLedger restored = ProjectMaterialLedger.restore(ledger.snapshot(20));
        MaterialBalanceReport balance = restored.balance(project);
        assertThat(balance.planned()).isEqualTo(4);
        assertThat(balance.withdrawn()).isEqualTo(4);
        assertThat(balance.consumed()).isEqualTo(4);
        assertThat(balance.duplicateWithdrawals()).isZero();
        assertThat(balance.duplicateReturns()).isZero();
        assertThat(balance.unaccountedItems()).isZero();
        assertThat(balance.materialLedgerBalanced()).isTrue();
    }

    @Test
    void cancellationRequiresExactReturnBeforeReservationRelease() {
        ResourceId project = id("project:return");
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan(project, 3));
        ledger.bindSource(source(id("source:return"), project, 3), 1);
        MaterialAllocation allocation = ledger.reserve(
                allocation(id("allocation:return"), project, id("source:return"), 3), 2);
        ResourceId transaction = id("transaction:return");
        ledger.prepare(transaction, project, id("task:return"), allocation.allocationId(),
                MaterialExecutorKind.DIRECT, 3, 3);
        ledger.advance(transaction, MaterialTransactionState.WITHDRAWN, 4);

        assertThatThrownBy(() -> ledger.releaseAllocation(allocation.allocationId(), 5))
                .hasMessageContaining("must be returned");
        ledger.advance(transaction, MaterialTransactionState.RETURN_PENDING, 5);
        ledger.advance(transaction, MaterialTransactionState.RETURNED, 6);
        ledger.releaseAllocation(allocation.allocationId(), 7);
        assertThat(ledger.balance(project).returned()).isEqualTo(3);
    }

    @Test
    void customPayloadCannotSatisfyTagFreeSlotIdentity() {
        ResourceId project = id("project:nbt");
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan(project, 1));
        ledger.bindSource(source(id("source:nbt"), project, 1), 1);
        MaterialAllocation changed = new MaterialAllocation(id("allocation:nbt"), project,
                id("requirement:shaft"), id("source:nbt"), 0,
                new MaterialIdentity(id("create:shaft"), "b".repeat(64)), 1);
        assertThatThrownBy(() -> ledger.reserve(changed, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differs");
    }

    @Test
    void anotherAccessFaceCannotDoubleReserveTheSamePhysicalContainer() {
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ResourceId one = id("project:face_one");
        ResourceId two = id("project:face_two");
        ledger.registerPlan(plan(one, 8));
        ledger.registerPlan(plan(two, 8));
        ledger.bindSource(source(id("source:face_one"), one, 12, "up", 60_001), 1);
        ledger.bindSource(source(id("source:face_two"), two, 12, "north", 60_001), 1);
        ledger.reserve(allocation(id("allocation:face_one"), one, id("source:face_one"), 8), 2);

        assertThatThrownBy(() -> ledger.reserve(
                allocation(id("allocation:face_two"), two, id("source:face_two"), 8), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already reserved");
    }

    @Test
    void expiredSourceAuthorityCannotCreateANewReservation() {
        ResourceId project = id("project:expired");
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.registerPlan(plan(project, 1));
        ledger.bindSource(source(id("source:expired"), project, 1, "up", 5), 1);

        assertThatThrownBy(() -> ledger.reserve(
                allocation(id("allocation:expired"), project, id("source:expired"), 1), 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    private static MaterialRequirementPlan plan(ResourceId project, long quantity) {
        return new MaterialRequirementPlan(id("plan:" + project.path()), project,
                id("create:cogwheel"), 1, "runtime:test", HASH,
                List.of(new MaterialRequirement(id("requirement:shaft"),
                        id("create:deploying/cogwheel"), 0, RecipeIngredientKind.EXACT_RESOURCE,
                        "exact:create:shaft@" + quantity, List.of(id("create:shaft")), quantity)));
    }

    private static MaterialSourceBinding source(ResourceId source, ResourceId project, long quantity) {
        return source(source, project, quantity, "up", 60_001);
    }

    private static MaterialSourceBinding source(ResourceId source, ResourceId project, long quantity,
            String face, long expiresAt) {
        return new MaterialSourceBinding(source, project, "player", id("minecraft:overworld"),
                new BlockPos3i(4, 64, 4), face, "minecraft:chest", HASH, HASH,
                0, 64, 1, expiresAt, List.of(new MaterialSlotSnapshot(0, SHAFT, quantity)));
    }

    private static MaterialAllocation allocation(
            ResourceId allocation, ResourceId project, ResourceId source, long quantity) {
        return new MaterialAllocation(allocation, project, id("requirement:shaft"),
                source, 0, SHAFT, quantity);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
