package dev.stevecreate.agent.core.siteprep;

import java.util.List;

public record SitePreparationEvidence(
        String selectionHash,
        String initialSnapshotHash,
        String approvalTokenIdentity,
        String cleanSnapshotHash,
        int unknownBlocksRemoved,
        int protectedBlocksRemoved,
        int containersOpened,
        int playerInventoryAccesses,
        int regionOutsideMutations,
        int formalWorldAccesses,
        boolean botOverlap,
        boolean teleportFallback,
        List<String> trace) {
    public SitePreparationEvidence {
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
        initialSnapshotHash = SitePreparationHashes.hash(
                initialSnapshotHash, "initialSnapshotHash");
        approvalTokenIdentity = SitePreparationHashes.text(
                approvalTokenIdentity, "approvalTokenIdentity");
        cleanSnapshotHash = SitePreparationHashes.hash(cleanSnapshotHash, "cleanSnapshotHash");
        if (unknownBlocksRemoved < 0 || protectedBlocksRemoved < 0 || containersOpened < 0
                || playerInventoryAccesses < 0 || regionOutsideMutations < 0
                || formalWorldAccesses < 0) {
            throw new IllegalArgumentException("safety counters cannot be negative");
        }
        trace = List.copyOf(trace);
        if (trace.size() > 4_096) throw new IllegalArgumentException("trace is unbounded");
    }

    public boolean safetyBoundaryPassed() {
        return unknownBlocksRemoved == 0 && protectedBlocksRemoved == 0
                && containersOpened == 0 && playerInventoryAccesses == 0
                && regionOutsideMutations == 0 && formalWorldAccesses == 0
                && !botOverlap && !teleportFallback;
    }
}
