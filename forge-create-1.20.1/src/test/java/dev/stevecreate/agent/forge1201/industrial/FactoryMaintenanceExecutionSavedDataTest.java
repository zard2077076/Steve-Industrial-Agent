package dev.stevecreate.agent.forge1201.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class FactoryMaintenanceExecutionSavedDataTest {
    private static final ResourceId ID = ResourceId.parse(
            "steve_industrial:maintenance/11111111111111111111111111111111");
    private static final UUID PLAYER =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Test
    void roundTripsPreparedAppliedAndVerifiedWithoutReopeningIdentity() {
        FactoryMaintenanceExecutionSavedData data = new FactoryMaintenanceExecutionSavedData();
        data.prepare(ID, PLAYER, "2".repeat(64), 10);
        data.transition(ID, FactoryMaintenanceExecutionSavedData.State.APPLIED, 11, 2);
        data.transition(ID, FactoryMaintenanceExecutionSavedData.State.VERIFIED, 12, 2);

        var restored = FactoryMaintenanceExecutionSavedData
                .load(data.save(new CompoundTag())).entry(ID).orElseThrow();
        assertThat(restored.state()).isEqualTo(
                FactoryMaintenanceExecutionSavedData.State.VERIFIED);
        assertThat(restored.worldMutations()).isEqualTo(2);
    }

    @Test
    void tamperedHashMutationCountOrDuplicateDropsAllTransactions() {
        FactoryMaintenanceExecutionSavedData data = new FactoryMaintenanceExecutionSavedData();
        data.prepare(ID, PLAYER, "2".repeat(64), 10);
        CompoundTag original = data.save(new CompoundTag());

        CompoundTag hash = original.copy();
        hash.getList("Executions", Tag.TAG_COMPOUND).getCompound(0)
                .putString("DiagnosticHash", "bad");
        assertThat(FactoryMaintenanceExecutionSavedData.load(hash).entry(ID)).isEmpty();

        CompoundTag count = original.copy();
        count.getList("Executions", Tag.TAG_COMPOUND).getCompound(0)
                .putInt("WorldMutations", 1);
        assertThat(FactoryMaintenanceExecutionSavedData.load(count).entry(ID)).isEmpty();

        CompoundTag duplicate = original.copy();
        duplicate.getList("Executions", Tag.TAG_COMPOUND).add(
                duplicate.getList("Executions", Tag.TAG_COMPOUND).getCompound(0).copy());
        assertThat(FactoryMaintenanceExecutionSavedData.load(duplicate).entry(ID)).isEmpty();

        CompoundTag missingEvent = original.copy();
        missingEvent.getList("Journal", Tag.TAG_STRING).clear();
        assertThat(FactoryMaintenanceExecutionSavedData.load(missingEvent).entry(ID)).isEmpty();

        CompoundTag orphanEvent = original.copy();
        orphanEvent.getList("Journal", Tag.TAG_STRING).add(
                net.minecraft.nbt.StringTag.valueOf("2|11|APPLIED|" + ID + "|" + PLAYER
                        + "|" + "2".repeat(64) + "|2"));
        orphanEvent.putLong("Generation", 2);
        assertThat(FactoryMaintenanceExecutionSavedData.load(orphanEvent).entry(ID)).isEmpty();
    }

    @Test
    void boundedEntryCapacityRefusesBeforeOpeningAnotherTransaction() {
        FactoryMaintenanceExecutionSavedData data = new FactoryMaintenanceExecutionSavedData();
        for (int index = 0; index < FactoryMaintenanceExecutionSavedData.MAX_ENTRIES; index++) {
            ResourceId id = ResourceId.parse("steve_industrial:maintenance/capacity_" + index);
            assertThat(data.canPrepare(id)).isTrue();
            data.prepare(id, PLAYER, "2".repeat(64), 10);
        }
        ResourceId overflow = ResourceId.parse("steve_industrial:maintenance/capacity_overflow");
        assertThat(data.canPrepare(overflow)).isFalse();
        assertThatThrownBy(() -> data.prepare(overflow, PLAYER, "2".repeat(64), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("maintenance execution entries are full");
    }
}
