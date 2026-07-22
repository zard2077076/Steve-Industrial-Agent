package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutionCancellationResultTest {
    @Test
    void exposesJournalPositionsAndConservativeRollbackWithoutHidingWarnings() {
        ResourceId sessionId = id("test:session");
        BlockPos3i position = new BlockPos3i(1, 2, 3);
        WorldChangeJournal journal = WorldChangeJournal.empty(sessionId).append(new BlockChange(
                id("test:change/place"),
                sessionId,
                id("test:step/build"),
                10,
                position,
                block("minecraft:air"),
                block("minecraft:stone")));
        RollbackReport rollback = new RollbackReport(
                sessionId, 1, 1, List.of(position), List.of());

        ExecutionCancellationResult result = new ExecutionCancellationResult(
                sessionId, id("test:user_cancelled"), journal, rollback);

        assertThat(result.modifiedPositions()).containsExactly(position);
        assertThat(result.rollbackReport().fullyRestored()).isTrue();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExecutionCancellationResult(
                        id("test:other"),
                        id("test:user_cancelled"),
                        journal,
                        rollback))
                .withMessage("Journal session does not match cancellation session");
    }

    private static WorldBlockSnapshot block(String value) {
        return new WorldBlockSnapshot(id(value), Map.of(), Optional.empty());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
