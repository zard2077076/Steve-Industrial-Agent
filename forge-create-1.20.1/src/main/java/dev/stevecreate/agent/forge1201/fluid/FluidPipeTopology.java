package dev.stevecreate.agent.forge1201.fluid;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which fluid endpoints a given one can actually reach through Create pipework.
 *
 * <p>{@code FluidEndpointDiscovery} answers "what is nearby". This answers the question
 * that decides whether a transfer is possible at all: "is it connected". Two tanks a
 * metre apart with no pipe between them look identical in a list of positions and
 * capacities, and pairing them would plan a transfer that can never happen.
 *
 * <p>Create already knows its own pipe topology and exposes it: {@link FluidPropagator}
 * says whether a block is a pipe, which faces that pipe connects on, and whether a
 * neighbour offers a fluid capability. Walking that is reading Create's answer, not
 * reimplementing it — a private reimplementation would drift from the mod the moment
 * pipe behaviour changed.
 *
 * <p>Read-only, like the rest of the discovery family. Finding that two tanks are
 * plumbed together is not permission to move anything between them.
 */
public final class FluidPipeTopology {
    /** Bound on how far a walk may wander before it is surveying the map. */
    public static final int MAX_PIPES = 512;

    private FluidPipeTopology() {}

    /**
     * An endpoint reachable from the origin, and how much pipe it took to get there.
     *
     * @param pipeCount pipes traversed on the shortest walk found, which is what makes
     *     one route preferable to another when several exist
     */
    public record Route(BlockPos3i destination, Direction entryFace, int pipeCount) {
        public Route {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(entryFace, "entryFace");
        }
    }

    /**
     * Every fluid-capable block reachable from {@code origin} through connected pipes.
     *
     * <p>The origin itself is excluded — a tank is trivially connected to itself and
     * offering that as a route would be offering a transfer the executor refuses outright
     * as ENDPOINTS_IDENTICAL.
     *
     * <p>Breadth-first, so the first time an endpoint is reached is by the shortest pipe
     * run, and ordered by that length then by position, so an unchanged world answers the
     * same way twice.</p>
     */
    public static List<Route> reachableFrom(ServerLevel level, BlockPos origin, int maxPipes) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        if (maxPipes < 1 || maxPipes > MAX_PIPES) {
            throw new IllegalArgumentException("pipe budget must be between 1 and " + MAX_PIPES);
        }
        Set<BlockPos> visitedPipes = new LinkedHashSet<>();
        Set<BlockPos> foundEndpoints = new LinkedHashSet<>();
        List<Route> routes = new ArrayList<>();
        Deque<Step> queue = new ArrayDeque<>();

        // Seed with the pipes touching the origin. A pipe only counts if it actually
        // connects back towards the origin; one merely sitting adjacent carries nothing.
        for (Direction face : Direction.values()) {
            BlockPos neighbour = origin.relative(face);
            if (connectsTowards(level, neighbour, face.getOpposite())) {
                queue.add(new Step(neighbour, 1));
            }
        }

        while (!queue.isEmpty()) {
            Step step = queue.poll();
            if (!visitedPipes.add(step.position())) continue;
            if (visitedPipes.size() > maxPipes) break;
            BlockState state = level.getBlockState(step.position());
            FluidTransportBehaviour behaviour = FluidPropagator.getPipe(level, step.position());
            if (behaviour == null) continue;
            for (Direction face : FluidPropagator.getPipeConnections(state, behaviour)) {
                BlockPos next = step.position().relative(face);
                if (next.equals(origin) || visitedPipes.contains(next)) continue;
                if (FluidPropagator.getPipe(level, next) != null) {
                    queue.add(new Step(next, step.pipeCount() + 1));
                    continue;
                }
                // Not a pipe: an endpoint if it offers a fluid capability on the face the
                // pipe meets it from.
                if (FluidPropagator.hasFluidCapability(level, next, face.getOpposite())
                        && foundEndpoints.add(next.immutable())) {
                    routes.add(new Route(
                            new BlockPos3i(next.getX(), next.getY(), next.getZ()),
                            face.getOpposite(),
                            step.pipeCount()));
                }
            }
        }
        routes.sort(Comparator.comparingInt(Route::pipeCount)
                .thenComparing(route -> route.destination().toString()));
        return List.copyOf(routes);
    }

    /** Whether two endpoints are plumbed together, which is what a transfer needs. */
    public static boolean connected(ServerLevel level, BlockPos from, BlockPos to, int maxPipes) {
        BlockPos3i target = new BlockPos3i(to.getX(), to.getY(), to.getZ());
        return reachableFrom(level, from, maxPipes).stream()
                .anyMatch(route -> route.destination().equals(target));
    }

    /** Whether the block at {@code position} is a pipe that connects on {@code face}. */
    private static boolean connectsTowards(ServerLevel level, BlockPos position, Direction face) {
        FluidTransportBehaviour behaviour = FluidPropagator.getPipe(level, position);
        if (behaviour == null) return false;
        return FluidPropagator.getPipeConnections(level.getBlockState(position), behaviour)
                .contains(face);
    }

    private record Step(BlockPos position, int pipeCount) {}
}
