package dev.stevecreate.agent.core.electrical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import org.junit.jupiter.api.Test;

class ProjectEnergyLedgerTest {
    @Test
    void settleIsIdempotentAcrossSnapshotRestoreAndIdentityReuseIsRejected() {
        ProjectEnergyLedger ledger = new ProjectEnergyLedger();
        ResourceId transaction = id("energy_tx:one");
        ResourceId project = id("project:one");
        ledger.prepare(transaction, project, id("task:press"), id("endpoint:battery"),
                id("forge:energy"), 2_400, 1);
        ledger.settle(transaction, 2);

        ProjectEnergyLedger restored = ProjectEnergyLedger.restore(ledger.snapshot());
        restored.settle(transaction, 3);
        assertThat(restored.balance(project)).satisfies(report -> {
            assertThat(report.settledFe()).isEqualTo(2_400);
            assertThat(report.duplicateSettlements()).isZero();
            assertThat(report.balanced()).isTrue();
        });
        assertThatThrownBy(() -> restored.prepare(transaction, project, id("task:other"),
                id("endpoint:battery"), id("forge:energy"), 2_400, 4))
                .hasMessageContaining("identity was reused");
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
