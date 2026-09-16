package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The decision that separates "produced a factory" from "reported success".
 *
 * <p>Each condition below can fail on its own while the run looks finished, which is
 * exactly why they are asserted separately rather than through one happy path.</p>
 */
class CompositeSettlementV1Test {
    private static final CompositePlayerOrderSpecV1 SPEC =
            CompositePlayerOrderCatalogV1.COMPOSITE_01;
    private static final Map<ResourceId, Long> EXACT_OUTPUT =
            Map.of(ResourceId.parse("create:cogwheel"), 1L);
    private static final Map<ResourceId, Long> EXACT_SALVAGE =
            Map.of(ResourceId.parse("minecraft:oak_planks"), 5L);

    @Test
    void acceptsARunThatProvedEveryCondition() {
        CompositeSettlementV1 settlement = settlement(25, 25, EXACT_OUTPUT, EXACT_SALVAGE, true, true);

        assertThat(settlement.complete()).isTrue();
        assertThatThrownBy(settlement::pauseReason)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnUnbalancedLedgerEvenWhenTheOutputIsRight() {
        CompositeSettlementV1 settlement = settlement(25, 24, EXACT_OUTPUT, EXACT_SALVAGE, true, true);

        assertThat(settlement.complete()).isFalse();
        assertThat(settlement.pauseReason())
                .isEqualTo("COMPOSITE_MATERIAL_LEDGER_RECONCILIATION_REQUIRED");
    }

    @Test
    void refusesTooLittleOutputButNotTooMuch() {
        // Nothing delivered is the case this rule exists for: a broken route loses
        // material downstream and shows up here as a shortfall.
        assertThat(settlement(25, 25, Map.of(), EXACT_SALVAGE, true, true).pauseReason())
                .isEqualTo("COMPOSITE_OUTPUT_SHORT");

        // Two where one was promised settles. An unattended site that ran another batch
        // before settlement is the normal case for this project, not a fault, and the
        // ledger — checked ahead of this — is what would catch a stage running twice.
        CompositeSettlementV1 surplus = settlement(25, 25,
                Map.of(ResourceId.parse("create:cogwheel"), 2L), EXACT_SALVAGE, true, true);
        assertThat(surplus.complete()).isTrue();
        // And the surplus is carried, not swallowed: an over-running site has to stay
        // distinguishable from an exact one.
        assertThat(surplus.outputSurplus()).isEqualTo(1L);
        assertThat(settlement(25, 25, Map.of(ResourceId.parse("create:cogwheel"), 1L),
                EXACT_SALVAGE, true, true).outputSurplus()).isEqualTo(0L);
    }

    /** Unsettled salvage is produced material the player never receives. */
    @Test
    void refusesSalvageThatDoesNotMatchWhatTheGraphOverproduces() {
        assertThat(settlement(25, 25, EXACT_OUTPUT, Map.of(), true, true).pauseReason())
                .isEqualTo("COMPOSITE_SALVAGE_UNSETTLED");
        assertThat(settlement(25, 25, EXACT_OUTPUT,
                Map.of(ResourceId.parse("minecraft:oak_planks"), 4L), true, true).pauseReason())
                .isEqualTo("COMPOSITE_SALVAGE_UNSETTLED");
    }

    @Test
    void refusesUnprovenRouteEvidenceAndUnclearedInfrastructure() {
        assertThat(settlement(25, 25, EXACT_OUTPUT, EXACT_SALVAGE, false, true).pauseReason())
                .isEqualTo("COMPOSITE_ROUTE_EVIDENCE_INCOMPLETE");
        assertThat(settlement(25, 25, EXACT_OUTPUT, EXACT_SALVAGE, true, false).pauseReason())
                .isEqualTo("COMPOSITE_CLEANUP_REQUIRED");
    }

    /**
     * An unbalanced ledger makes every later observation untrustworthy, so it must be
     * reported ahead of them however many other conditions also failed.
     */
    @Test
    void reportsTheMostFundamentalFailureWhenSeveralFailAtOnce() {
        assertThat(settlement(25, 20, Map.of(), Map.of(), false, false).pauseReason())
                .isEqualTo("COMPOSITE_MATERIAL_LEDGER_RECONCILIATION_REQUIRED");
        // Cleanup is last: a standing site can still be cleared, unlike spent material.
        assertThat(settlement(25, 25, EXACT_OUTPUT, EXACT_SALVAGE, false, false).pauseReason())
                .isEqualTo("COMPOSITE_ROUTE_EVIDENCE_INCOMPLETE");
    }

    @Test
    void derivesTheSameVerdictForTheBranchMergeGraph() {
        CompositePlayerOrderSpecV1 branchMerge = CompositePlayerOrderCatalogV1.COMPOSITE_03;

        CompositeSettlementV1 good = CompositeSettlementV1.of(branchMerge, 30, 30,
                Map.of(ResourceId.parse("create:large_cogwheel"), 1L), EXACT_SALVAGE, true, true);
        CompositeSettlementV1 wrongTarget = CompositeSettlementV1.of(branchMerge, 30, 30,
                EXACT_OUTPUT, EXACT_SALVAGE, true, true);

        assertThat(good.complete()).isTrue();
        assertThat(wrongTarget.pauseReason()).isEqualTo("COMPOSITE_OUTPUT_SHORT");
    }

    /**
     * A line left standing on purpose settles; one left standing by accident does not.
     *
     * <p>Retained is not forgotten. The infrastructure came out of the player's chest and
     * is still theirs, embodied in the structure rather than returned — but a site that
     * is neither cleared nor deliberately kept is material that has gone missing, and
     * that still has to fail.</p>
     */
    @Test
    void acceptsASiteKeptOnPurposeAndRefusesOneMerelyLeftStanding() {
        CompositeSettlementV1 abandoned = CompositeSettlementV1.of(SPEC, 25, 25, EXACT_OUTPUT,
                EXACT_SALVAGE, true, false);
        assertThat(abandoned.complete()).isFalse();
        assertThat(abandoned.pauseReason()).isEqualTo("COMPOSITE_CLEANUP_REQUIRED");

        CompositeSettlementV1 resident = CompositeSettlementV1.of(SPEC, 25, 25, EXACT_OUTPUT,
                EXACT_SALVAGE, true, false, true);
        assertThat(resident.complete()).isTrue();
        assertThat(resident.infrastructureRetained()).isTrue();
        assertThat(resident.infrastructureCleared()).isFalse();

        // Everything else still has to hold: keeping the site excuses nothing else.
        CompositeSettlementV1 shortOutput = CompositeSettlementV1.of(SPEC, 25, 25, Map.of(),
                EXACT_SALVAGE, true, false, true);
        assertThat(shortOutput.complete()).isFalse();
        assertThat(shortOutput.pauseReason()).isEqualTo("COMPOSITE_OUTPUT_SHORT");
    }

    private static CompositeSettlementV1 settlement(
            long planned,
            long withdrawn,
            Map<ResourceId, Long> output,
            Map<ResourceId, Long> salvage,
            boolean routesProved,
            boolean cleared) {
        return CompositeSettlementV1.of(SPEC, planned, withdrawn, output, salvage,
                routesProved, cleared);
    }
}
