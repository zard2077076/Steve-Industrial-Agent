package dev.stevecreate.agent.core.siteprep;

import java.time.Instant;
import java.util.Objects;

public record FacingSelection(
        SiteFacing facing,
        String playerIdentity,
        Instant selectedAt,
        String selectionHash) {
    public FacingSelection {
        Objects.requireNonNull(facing, "facing");
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        Objects.requireNonNull(selectedAt, "selectedAt");
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
    }

    public FacingSelection rotateClockwise(Instant at) {
        String hash = SitePreparationHashes.sha256(selectionHash + "\n"
                + facing.rotateClockwise() + "\n" + at);
        return new FacingSelection(facing.rotateClockwise(), playerIdentity, at, hash);
    }
}
