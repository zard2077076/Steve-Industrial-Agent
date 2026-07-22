package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Conservative cleanup for blocks proven to be owned by one IWP execution journal. */
public final class CreateV606PilotCleanupService {
    private static final ResourceId AIR = ResourceId.parse("minecraft:air");

    private CreateV606PilotCleanupService() {}

    public static CleanupPlan preview(
            ServerLevel level,
            List<WorldChangeJournal> journals,
            Set<BlockPos3i> expectedOwnedPositions,
            BlockPos3i resourceBufferPosition) {
        Objects.requireNonNull(level, "level");
        journals = List.copyOf(Objects.requireNonNull(journals, "journals"));
        expectedOwnedPositions = Set.copyOf(Objects.requireNonNull(
                expectedOwnedPositions, "expectedOwnedPositions"));
        Objects.requireNonNull(resourceBufferPosition, "resourceBufferPosition");
        if (!level.getServer().isSameThread()) {
            return refused("Cleanup preview left the authoritative server thread");
        }
        if (CreateExecutionWorldGuard.mutationFailure(level).isPresent()) {
            return refused("FORMAL_WORLD_EXECUTION_FORBIDDEN: "
                    + CreateExecutionWorldGuard.mutationFailure(level).orElseThrow());
        }
        Map<BlockPos3i, BlockChange> first = new LinkedHashMap<>();
        Map<BlockPos3i, BlockChange> last = new LinkedHashMap<>();
        for (WorldChangeJournal journal : journals) {
            for (WorldChangeJournal.Entry entry : journal.entries()) {
                if (entry instanceof BlockChange block) {
                    first.putIfAbsent(block.position(), block);
                    last.put(block.position(), block);
                }
            }
        }
        if (!expectedOwnedPositions.containsAll(first.keySet())) {
            Set<BlockPos3i> outside = new LinkedHashSet<>(first.keySet());
            outside.removeAll(expectedOwnedPositions);
            return refused("Session journal contains positions outside the preview: " + outside);
        }
        for (BlockChange change : first.values()) {
            if (!change.before().blockId().equals(AIR)) {
                return refused("Cleanup refuses a non-air original block at " + change.position());
            }
        }
        List<BlockPos3i> remove = new ArrayList<>();
        List<BlockPos3i> alreadyClean = new ArrayList<>();
        Create606WorldChangeJournal reader = new Create606WorldChangeJournal(
                level, ResourceId.parse("steve_industrial:pilot_cleanup/readback"));
        for (BlockPos3i position : expectedOwnedPositions) {
            BlockPos block = block(position);
            if (!level.hasChunkAt(block)) return refused("Cleanup chunk is unloaded at " + position);
            WorldBlockSnapshot current = reader.capture(block);
            if (current.blockId().equals(AIR)) {
                alreadyClean.add(position);
                continue;
            }
            BlockChange ownedChange = last.get(position);
            if (ownedChange == null) {
                return refused("A non-air preview position lacks session journal ownership at "
                        + position);
            }
            WorldBlockSnapshot expected = ownedChange.after();
            if (!matchesOwnedAfter(current, expected)) {
                return refused("Session-owned position changed after execution at " + position);
            }
            remove.add(position);
        }
        BlockPos buffer = block(resourceBufferPosition);
        if (!level.hasChunkAt(buffer)) return refused("Resource buffer chunk is unloaded");
        ResourceId bufferBlock = reader.capture(buffer).blockId();
        if (bufferBlock.equals(ResourceId.parse("minecraft:chest"))) {
            remove.add(resourceBufferPosition);
        } else if (bufferBlock.equals(AIR)) {
            alreadyClean.add(resourceBufferPosition);
        } else {
            return refused("Resource buffer is no longer the session-owned chest");
        }
        remove.sort(POSITION_ORDER.reversed());
        alreadyClean.sort(POSITION_ORDER);
        return new CleanupPlan(true, List.copyOf(remove), List.copyOf(alreadyClean), "safe");
    }

    public static CleanupResult execute(
            ServerLevel level,
            List<WorldChangeJournal> journals,
            Set<BlockPos3i> expectedOwnedPositions,
            BlockPos3i resourceBufferPosition) {
        CleanupPlan plan = preview(level, journals, expectedOwnedPositions, resourceBufferPosition);
        if (!plan.safe()) return new CleanupResult(false, 0, plan.detail());
        int removed = 0;
        for (BlockPos3i position : plan.toRemove()) {
            BlockPos block = block(position);
            if (level.getBlockState(block).isAir()) {
                continue;
            }
            if (!level.setBlockAndUpdate(block, Blocks.AIR.defaultBlockState())) {
                if (level.getBlockState(block).isAir()) {
                    continue;
                }
                return new CleanupResult(false, removed, "World rejected cleanup at " + position);
            }
            if (!level.getBlockState(block).isAir()) {
                return new CleanupResult(false, removed, "Cleanup readback is not air at " + position);
            }
            removed++;
        }
        return new CleanupResult(true, removed, "journal-owned blocks removed");
    }

    static boolean matchesOwnedAfter(
            WorldBlockSnapshot current,
            WorldBlockSnapshot expected) {
        if (!current.blockId().equals(expected.blockId())) return false;
        if (current.properties().equals(expected.properties())) return true;
        if (!current.blockId().equals(ResourceId.parse("create:belt"))) return false;
        Map<String, String> currentStable = new LinkedHashMap<>(current.properties());
        Map<String, String> expectedStable = new LinkedHashMap<>(expected.properties());
        currentStable.remove("part");
        expectedStable.remove("part");
        return currentStable.equals(expectedStable);
    }

    private static CleanupPlan refused(String detail) {
        return new CleanupPlan(false, List.of(), List.of(), detail);
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static final Comparator<BlockPos3i> POSITION_ORDER = Comparator
            .comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y)
            .thenComparingInt(BlockPos3i::z);

    public record CleanupPlan(
            boolean safe,
            List<BlockPos3i> toRemove,
            List<BlockPos3i> alreadyClean,
            String detail) {
        public CleanupPlan {
            toRemove = List.copyOf(toRemove);
            alreadyClean = List.copyOf(alreadyClean);
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record CleanupResult(boolean success, int removed, String detail) {
        public CleanupResult {
            if (removed < 0) throw new IllegalArgumentException("removed cannot be negative");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
