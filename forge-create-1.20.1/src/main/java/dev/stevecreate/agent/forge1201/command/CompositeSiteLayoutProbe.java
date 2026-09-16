package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;

/**
 * Asks whether a spec could be laid out, without a world and without building anything.
 *
 * <p>{@link CompositeSiteLayout} and its contract are package-private on purpose: only
 * the order service should construct sites. The survey needs the same judgement without
 * that authority, so this exposes the question and not the layout.</p>
 */
public final class CompositeSiteLayoutProbe {
    /** An origin far from anything; the contract is origin-independent by construction. */
    private static final BlockPos3i PROBE_ORIGIN = new BlockPos3i(0, 64, 0);

    private CompositeSiteLayoutProbe() {}

    public static boolean isBuildable(CompositePlayerOrderSpecV1 spec) {
        try {
            CompositeSiteLayout layout = CompositeSiteLayout.forSpec(spec, PROBE_ORIGIN);
            return CompositeLayoutContract.violations(spec, layout).isEmpty();
        } catch (RuntimeException refused) {
            return false;
        }
    }
}
