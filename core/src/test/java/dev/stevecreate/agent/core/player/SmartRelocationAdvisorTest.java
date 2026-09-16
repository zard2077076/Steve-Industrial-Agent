package dev.stevecreate.agent.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmartRelocationAdvisorTest {
    @Test
    void zeroHardConflictAlwaysBeatsCloserProtectedOrUnknownCandidate() {
        var current = facts("current", 0, 1, 0, 0, 0, true);
        var protectedSite = facts("protected", 1, 0, 0, 0, 0, false);
        var safeMoved = facts("safe", 0, 0, 3, 2, 1, false);

        var ranked = new SmartRelocationAdvisor().rank(
                List.of(current, protectedSite, safeMoved));

        assertThat(ranked).extracting(value -> value.candidate().candidateId())
                .containsExactly("safe", "current", "protected");
        assertThat(ranked.get(0).reasons()).contains("hard_conflicts=0", "bot_access=clear");
    }

    @Test
    void equalCandidatesUseStableIdentityInsteadOfInputOrder() {
        var a = facts("a", 0, 0, 1, 0, 1, false);
        var b = facts("b", 0, 0, 1, 0, 1, false);
        assertThat(new SmartRelocationAdvisor().rank(List.of(b, a)))
                .extracting(value -> value.candidate().candidateId())
                .containsExactly("a", "b");
    }

    private static PlacementCandidateFacts facts(
            String id, int protectedCount, int unknown, int demolition,
            int distance, int expansionPenalty, boolean current) {
        return new PlacementCandidateFacts(id, new BlockPos3i(10 + distance, 70, 10),
                QuarterTurn.ZERO, LayoutVariant.STANDARD, protectedCount, unknown,
                0, 0, demolition, demolition, 8, 0, 0, 0, 0,
                expansionPenalty, 0, protectedCount > 0 ? 100 : 5,
                distance, 0, current);
    }
}
