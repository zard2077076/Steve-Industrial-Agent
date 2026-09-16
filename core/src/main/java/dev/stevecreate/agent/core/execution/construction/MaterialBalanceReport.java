package dev.stevecreate.agent.core.execution.construction;

/** Terminal material conservation evidence. */
public record MaterialBalanceReport(
        long planned,
        long reserved,
        long withdrawn,
        long delivered,
        long consumed,
        long returned,
        long outstanding,
        long duplicateWithdrawals,
        long duplicateReturns,
        long unaccountedItems,
        boolean materialLedgerBalanced) {
    public MaterialBalanceReport {
        if (planned < 0 || reserved < 0 || withdrawn < 0 || delivered < 0 || consumed < 0
                || returned < 0 || outstanding < 0 || duplicateWithdrawals < 0
                || duplicateReturns < 0 || unaccountedItems < 0) {
            throw new IllegalArgumentException("material balance cannot be negative");
        }
        boolean exact = planned == reserved
                && withdrawn == Math.addExact(Math.addExact(consumed, returned), outstanding)
                && duplicateWithdrawals == 0 && duplicateReturns == 0 && unaccountedItems == 0;
        if (materialLedgerBalanced != exact) {
            throw new IllegalArgumentException("material balance flag disagrees with arithmetic");
        }
    }
}
