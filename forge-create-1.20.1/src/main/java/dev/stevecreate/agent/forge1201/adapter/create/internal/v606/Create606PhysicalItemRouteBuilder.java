package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.layout.PhysicalRoute;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/** One-cell-per-tick physical ITEM-route construction owned by the verified root session. */
public final class Create606PhysicalItemRouteBuilder {
    private static final ResourceId STEP_ID =
            ResourceId.parse("steve_industrial:execution/connect_item_routes");
    private static final ResourceLocation ROUTE_BLOCK =
            ResourceLocation.fromNamespaceAndPath("create", "andesite_casing");

    /**
     * What an item route is built from, for whoever has to pay for it.
     *
     * <p>The executor knew this and the material plan did not, so the player path refused
     * every plan containing a route rather than price one.</p>
     */
    public static ResourceId routeBlock() {
        return ResourceId.parse(ROUTE_BLOCK.toString());
    }

    /** How many cells a set of routes will lay, counted the way the executor lays them. */
    public static int interiorCellCount(List<PhysicalRoute> routes) {
        LinkedHashSet<BlockPos3i> interiors = new LinkedHashSet<>();
        Objects.requireNonNull(routes, "routes")
                .forEach(route -> interiors.addAll(route.interiorPositions()));
        return interiors.size();
    }

    private final ServerLevel level;
    private final List<BlockPos3i> cells;
    private final Create606WorldChangeJournal journal;
    private int cursor;

    Create606PhysicalItemRouteBuilder(
            ServerLevel level,
            ResourceId rootSessionId,
            List<PhysicalRoute> routes) {
        this.level = Objects.requireNonNull(level, "level");
        this.journal = new Create606WorldChangeJournal(level,
                new ResourceId(rootSessionId.namespace(), rootSessionId.path() + "/item_routes"));
        LinkedHashSet<BlockPos3i> interiors = new LinkedHashSet<>();
        // The same definition the material plan prices, so what is charged for and what
        // is built cannot drift apart.
        Objects.requireNonNull(routes, "routes")
                .forEach(route -> interiors.addAll(route.interiorPositions()));
        this.cells = List.copyOf(interiors);
    }

    BuildResult tick() {
        if (complete()) return new RouteComplete(cells.size());
        var guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return failure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        BlockPos3i cell = cells.get(cursor);
        BlockPos position = position(cell);
        if (!level.hasChunkAt(position)) {
            return failure(ExecutionReadinessFailureCode.REQUIRED_CHUNK_UNLOADED,
                    "ITEM route chunk unloaded at " + cell);
        }
        if (!level.getBlockState(position).canBeReplaced()) {
            return failure(ExecutionReadinessFailureCode.ROUTE_CONSTRUCTION_FAILED,
                    "ITEM route target changed at " + cell);
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ROUTE_BLOCK);
        if (block == null) {
            return failure(ExecutionReadinessFailureCode.ROUTE_CONSTRUCTION_FAILED,
                    "Create route material is unavailable: " + ROUTE_BLOCK);
        }
        var before = journal.capture(position);
        if (!level.setBlockAndUpdate(position, block.defaultBlockState())) {
            return failure(ExecutionReadinessFailureCode.ROUTE_CONSTRUCTION_FAILED,
                    "World rejected ITEM route cell " + cell);
        }
        journal.recordBlockChange(STEP_ID, level.getGameTime(), position, before);
        cursor++;
        return complete() ? new RouteComplete(cells.size()) : new RouteProgress(cursor, cells.size());
    }

    boolean complete() { return cursor >= cells.size(); }
    int cellCount() { return cells.size(); }
    BlockPos3i nextCell() {
        if (complete()) throw new IllegalStateException("ITEM route is already complete");
        return cells.get(cursor);
    }
    WorldChangeJournal journal() { return journal.snapshot(); }
    RollbackReport rollback() { return journal.rollback(); }
    List<BlockPos3i> cells() { return cells; }

    sealed interface BuildResult permits RouteProgress, RouteComplete, RouteFailure {}
    record RouteProgress(int completed, int total) implements BuildResult {}
    record RouteComplete(int total) implements BuildResult {}
    record RouteFailure(ExecutionReadinessFailureCode code, String detail) implements BuildResult {}

    private static RouteFailure failure(ExecutionReadinessFailureCode code, String detail) {
        return new RouteFailure(code, detail);
    }

    private static BlockPos position(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }
}
