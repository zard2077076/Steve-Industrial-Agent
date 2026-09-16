package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/**
 * Whether a finished Composite run may be reported complete, and if not, why.
 *
 * <p>This was an if-chain inside the order service, which meant the single decision
 * that separates "produced a factory" from "reported success" had no test of its own.
 * It has five independent conditions and a deliberate reason ordering, and getting the
 * ordering wrong is invisible: the run still pauses, just with a reason that sends the
 * reader after the wrong thing.</p>
 *
 * <p>Every condition is required. A run that produced the exact output but left its
 * salvage unsettled has still lost the player material, and a run whose ledger does not
 * balance has not accounted for it wherever it went.</p>
 */
public record CompositeSettlementV1(
        boolean ledgerBalanced,
        boolean outputProved,
        boolean salvageProved,
        boolean routesProved,
        boolean infrastructureCleared,
        boolean infrastructureRetained,
        long promisedOutput,
        long observedOutput) {

    /**
     * What the run delivered beyond its promise, never negative.
     *
     * <p>Carried rather than discarded because a surplus is a fact about the run: the
     * site kept producing past the point the order settled. That is expected for an
     * unattended factory and is not a failure, but a settlement that silently swallowed
     * it would make an over-running site indistinguishable from an exact one.</p>
     */
    public long outputSurplus() {
        return Math.max(0, observedOutput - promisedOutput);
    }

    /**
     * Derives settlement from what the run actually left behind.
     *
     * @param plannedTotal every item the order reserved
     * @param withdrawnTotal every item the ledger accounted for at the end
     * @param observedOutput what is physically in the final delivery chest
     * @param observedSalvage what is physically in the salvage chests
     */
    public static CompositeSettlementV1 of(
            CompositePlayerOrderSpecV1 spec,
            long plannedTotal,
            long withdrawnTotal,
            Map<ResourceId, Long> observedOutput,
            Map<ResourceId, Long> observedSalvage,
            boolean routesProved,
            boolean infrastructureCleared) {
        return of(spec, plannedTotal, withdrawnTotal, observedOutput, observedSalvage,
                routesProved, infrastructureCleared, false);
    }

    /**
     * Settles a run whose site may be left standing on purpose.
     *
     * <p>A production line that stays up is the point of a resident factory, and until
     * now the only accounted end state was a site taken back down. Retained is not the
     * same as forgotten: the infrastructure was withdrawn from the player and is still
     * theirs, embodied in the structure rather than returned to a chest. What must never
     * happen is a site that is neither cleared nor deliberately kept, which is material
     * that has simply gone missing.</p>
     */
    public static CompositeSettlementV1 of(
            CompositePlayerOrderSpecV1 spec,
            long plannedTotal,
            long withdrawnTotal,
            Map<ResourceId, Long> observedOutput,
            Map<ResourceId, Long> observedSalvage,
            boolean routesProved,
            boolean infrastructureCleared,
            boolean infrastructureRetained) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(observedOutput, "observedOutput");
        Objects.requireNonNull(observedSalvage, "observedSalvage");
        long delivered = observedOutput.getOrDefault(spec.target(), 0L);
        // At least the promise, not exactly it. Short is still a failure and always must
        // be — that is what catches a broken route, which loses material downstream and
        // shows up as too few. Over-delivery is the opposite case: an unattended site
        // that ran another batch before settlement. Refusing that was a false alarm, and
        // this project's whole direction is factories left running without a player.
        //
        // Nothing is lost by relaxing it. A stage that ran twice consumed twice, and an
        // unbalanced ledger is reported ahead of this one.
        return new CompositeSettlementV1(
                plannedTotal == withdrawnTotal,
                delivered >= spec.targetQuantity(),
                observedSalvage.equals(spec.intermediateSalvage()),
                routesProved,
                infrastructureCleared,
                infrastructureRetained,
                spec.targetQuantity(),
                delivered);
    }

    public boolean complete() {
        return ledgerBalanced && outputProved && salvageProved
                && routesProved && (infrastructureCleared || infrastructureRetained);
    }

    /**
     * The typed reason to pause on, most fundamental first.
     *
     * <p>Ordering is not cosmetic. An unbalanced ledger means the accounting itself is
     * untrustworthy, so every downstream observation is suspect and must be reported
     * first. Cleanup is last because it is the only condition that leaves the player's
     * material intact — a site that still stands can be cleared, whereas an unsettled
     * ledger cannot be un-spent.</p>
     *
     * @throws IllegalStateException if the run is in fact complete; callers must check
     *     {@link #complete()} first rather than treating a reason as always available.
     */
    public String pauseReason() {
        if (!ledgerBalanced) return "COMPOSITE_MATERIAL_LEDGER_RECONCILIATION_REQUIRED";
        if (!outputProved) return "COMPOSITE_OUTPUT_SHORT";
        if (!salvageProved) return "COMPOSITE_SALVAGE_UNSETTLED";
        if (!routesProved) return "COMPOSITE_ROUTE_EVIDENCE_INCOMPLETE";
        if (!infrastructureCleared && !infrastructureRetained) {
            return "COMPOSITE_CLEANUP_REQUIRED";
        }
        throw new IllegalStateException("a complete settlement has no pause reason");
    }
}
