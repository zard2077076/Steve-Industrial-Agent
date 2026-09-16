package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606BranchMergeExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606CompositeExecution;
import java.util.Objects;

/**
 * One running Composite, whichever wrapper owns it.
 *
 * <p>The linear and branch/merge wrappers are separate reviewed implementations with
 * their own session, completion and cleanup types.  The player order needs the same
 * four things from either — advance, snapshot, cancel, clean up — so this adapts them
 * without pretending they are one class or weakening either one's evidence.</p>
 *
 * <p>Route evidence is deliberately narrowed to a boolean here.  The linear wrapper
 * reports per-route observed counts and the branch/merge wrapper reports only how many
 * physical routes completed, so this asks each one the strongest question it can
 * actually answer and lets the wrapper's own constructor enforce the rest.</p>
 */
sealed interface CompositeRun permits CompositeRun.Linear, CompositeRun.BranchMerge {
    Tick tick();

    CompositeProductionSnapshot snapshot();

    void cancel(ResourceId reason);

    Cleanup cleanup();

    sealed interface Tick permits Progress, Completed, Failed {}

    record Progress() implements Tick {}

    /** Completion plus whether every physical intermediate movement was proven. */
    record Completed(boolean routesProved) implements Tick {}

    record Failed(String detail) implements Tick {
        public Failed {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Normalised cleanup verdict: nothing owned may remain standing. */
    record Cleanup(boolean complete) {}

    record Linear(CreateV606CompositeExecution.Session session) implements CompositeRun {
        public Linear {
            Objects.requireNonNull(session, "session");
        }

        @Override
        public Tick tick() {
            CreateV606CompositeExecution.TickResult result = session.tick();
            if (result instanceof CreateV606CompositeExecution.Progress) return new Progress();
            if (result instanceof CreateV606CompositeExecution.Failed failed) {
                return new Failed("COMPOSITE_STAGE_FAILED:" + failed.nodeId()
                        + ":" + failed.phase() + ":" + failed.detail());
            }
            CreateV606CompositeExecution.Completed completed =
                    (CreateV606CompositeExecution.Completed) result;
            boolean proved = completed.routes().stream().allMatch(route ->
                    route.observedDestinationAfter() == route.expectedQuantity()
                            && route.observedSourceBefore() - route.observedSourceAfter()
                                    == route.expectedQuantity() + route.observedOverflowAfter());
            return new Completed(proved);
        }

        @Override
        public CompositeProductionSnapshot snapshot() {
            return session.snapshot();
        }

        @Override
        public void cancel(ResourceId reason) {
            session.cancel(reason);
        }

        @Override
        public Cleanup cleanup() {
            CreateV606CompositeExecution.CleanupReport report = session.cleanup();
            return new Cleanup(report.remainingMachinePositions() == 0
                    && report.remainingRouteBlocks() == 0
                    && report.clearedMachinePositions() == report.plannedMachinePositions());
        }
    }

    record BranchMerge(CreateV606BranchMergeExecution.Session session) implements CompositeRun {
        public BranchMerge {
            Objects.requireNonNull(session, "session");
        }

        @Override
        public Tick tick() {
            CreateV606BranchMergeExecution.TickResult result = session.tick();
            if (result instanceof CreateV606BranchMergeExecution.Progress) return new Progress();
            if (result instanceof CreateV606BranchMergeExecution.Failed failed) {
                // phase() alone is just the FAILED enum value; the node and the
                // wrapper's own detail are what name the physical cause.
                return new Failed("COMPOSITE_BRANCH_FAILED:" + failed.phase()
                        + ":" + failed.nodeId() + ":" + failed.detail());
            }
            CreateV606BranchMergeExecution.Completed completed =
                    (CreateV606BranchMergeExecution.Completed) result;
            // The wrapper's own constructor refuses any completion that did not finish
            // all four physical routes, so reaching here already proves the movement.
            return new Completed(completed.completedPhysicalRoutes() == 4);
        }

        @Override
        public CompositeProductionSnapshot snapshot() {
            return session.snapshot();
        }

        @Override
        public void cancel(ResourceId reason) {
            session.cleanupFailed(reason);
        }

        @Override
        public Cleanup cleanup() {
            CreateV606BranchMergeExecution.CleanupReport report = session.cleanup();
            return new Cleanup(report.remainingMachinePositions() == 0
                    && report.remainingRouteBlocks() == 0);
        }
    }
}
