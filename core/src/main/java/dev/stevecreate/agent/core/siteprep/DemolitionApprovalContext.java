package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import java.util.Objects;

/** Explicit player-project scope that cannot be inferred from a preview hash alone. */
public record DemolitionApprovalContext(
        String projectIdentity,
        ResourceId target,
        long quantity,
        BlockPos3i anchor,
        QuarterTurn orientation,
        LayoutVariant layoutVariant,
        String regionAuthorizationHash,
        String safetyPolicy,
        PlayerExecutionMode executionMode,
        boolean playerWorkflowBound,
        String contextHash) {
    public DemolitionApprovalContext {
        projectIdentity = SitePreparationHashes.text(projectIdentity, "projectIdentity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(layoutVariant, "layoutVariant");
        regionAuthorizationHash = SitePreparationHashes.hash(
                regionAuthorizationHash, "regionAuthorizationHash");
        safetyPolicy = SitePreparationHashes.text(safetyPolicy, "safetyPolicy");
        Objects.requireNonNull(executionMode, "executionMode");
        contextHash = SitePreparationHashes.hash(contextHash, "contextHash");
        if (quantity < 1 || quantity > 64) {
            throw new IllegalArgumentException("approval quantity must be between 1 and 64");
        }
        String expected = hash(projectIdentity, target, quantity, anchor, orientation,
                layoutVariant, regionAuthorizationHash, safetyPolicy, executionMode,
                playerWorkflowBound);
        if (!contextHash.equals(expected)) {
            throw new IllegalArgumentException("approval context hash is invalid");
        }
    }

    public static DemolitionApprovalContext create(
            String projectIdentity,
            ResourceId target,
            long quantity,
            BlockPos3i anchor,
            QuarterTurn orientation,
            LayoutVariant layoutVariant,
            String regionAuthorizationHash,
            String safetyPolicy,
            PlayerExecutionMode executionMode) {
        return createInternal(projectIdentity, target, quantity, anchor, orientation,
                layoutVariant, regionAuthorizationHash, safetyPolicy, executionMode, true);
    }

    public static DemolitionApprovalContext legacyAdvancedCommand() {
        return createInternal("advanced-site-preparation-command",
                ResourceId.parse("steve_industrial:advanced_site_preparation"), 1,
                new BlockPos3i(0, 0, 0), QuarterTurn.ZERO, LayoutVariant.STANDARD,
                SitePreparationHashes.sha256("legacy-advanced-region"),
                "advanced-command-explicit-obstacle-selection",
                PlayerExecutionMode.BOTS, false);
    }

    private static DemolitionApprovalContext createInternal(
            String projectIdentity,
            ResourceId target,
            long quantity,
            BlockPos3i anchor,
            QuarterTurn orientation,
            LayoutVariant layoutVariant,
            String regionAuthorizationHash,
            String safetyPolicy,
            PlayerExecutionMode executionMode,
            boolean playerWorkflowBound) {
        return new DemolitionApprovalContext(projectIdentity, target, quantity, anchor,
                orientation, layoutVariant, regionAuthorizationHash, safetyPolicy,
                executionMode, playerWorkflowBound,
                hash(projectIdentity, target, quantity, anchor, orientation, layoutVariant,
                        regionAuthorizationHash, safetyPolicy, executionMode,
                        playerWorkflowBound));
    }

    private static String hash(
            String projectIdentity,
            ResourceId target,
            long quantity,
            BlockPos3i anchor,
            QuarterTurn orientation,
            LayoutVariant layoutVariant,
            String regionAuthorizationHash,
            String safetyPolicy,
            PlayerExecutionMode executionMode,
            boolean playerWorkflowBound) {
        return SitePreparationHashes.sha256(projectIdentity + "\n" + target + "\n"
                + quantity + "\n" + anchor + "\n" + orientation + "\n" + layoutVariant
                + "\n" + regionAuthorizationHash + "\n" + safetyPolicy + "\n"
                + executionMode + "\n" + playerWorkflowBound);
    }
}
