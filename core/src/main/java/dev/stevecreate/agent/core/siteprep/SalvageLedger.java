package dev.stevecreate.agent.core.siteprep;

import java.util.List;

public record SalvageLedger(
        String ledgerIdentity,
        String destinationIdentity,
        List<SalvageEntry> entries) {
    public SalvageLedger {
        ledgerIdentity = SitePreparationHashes.text(ledgerIdentity, "ledgerIdentity");
        destinationIdentity = SitePreparationHashes.text(destinationIdentity, "destinationIdentity");
        entries = List.copyOf(entries);
        if (entries.size() > 4_096) throw new IllegalArgumentException("salvage ledger is unbounded");
    }

    public int collectedCount() {
        return entries.stream().mapToInt(SalvageEntry::collectedCount).sum();
    }

    public int deliveredCount() {
        return entries.stream().mapToInt(SalvageEntry::deliveredCount).sum();
    }
}
