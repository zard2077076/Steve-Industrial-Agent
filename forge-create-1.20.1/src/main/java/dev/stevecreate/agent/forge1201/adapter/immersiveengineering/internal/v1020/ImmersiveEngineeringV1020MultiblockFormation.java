package dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020;

import blusunrize.immersiveengineering.common.blocks.multiblocks.IETemplateMultiblock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;

/**
 * Building and forming any Immersive Engineering template multiblock.
 *
 * <p>This was the Metal Press's, and every line of it that touched the world took
 * {@code IEMultiblocks.METAL_PRESS} as its only machine-specific input — the structure
 * template, the trigger offset and the formation call are all methods on
 * {@link IETemplateMultiblock}, and IE registers more than twenty of them under that one
 * type. Copying four hundred lines per machine would have been copying this.
 *
 * <p>What is <em>not</em> generic, and deliberately stays out: reading a formed machine's
 * state and mapping its recipes. Each machine has its own logic class with its own state
 * type and its own recipe type, so those remain per-machine. Formation is the part that
 * is shared, and it is the part that touches the world.
 */
public final class ImmersiveEngineeringV1020MultiblockFormation {
    /** Bound on how much of the world one formation may touch. */
    public static final int MAX_COMPONENTS = 64;

    private ImmersiveEngineeringV1020MultiblockFormation() {}

    public record StructureResult(boolean success, String code, int placedBlocks) {}

    /** Every cell this multiblock's template occupies, relative to an origin. */
    public static java.util.List<BlockPos> componentPositions(
            ServerLevel level, IETemplateMultiblock multiblock, BlockPos origin) {
        java.util.LinkedHashSet<BlockPos> cells = new java.util.LinkedHashSet<>();
        for (var component : multiblock.getStructure(level)) {
            cells.add(origin.offset(component.pos()).immutable());
        }
        return java.util.List.copyOf(cells);
    }

    /**
     * Places the template's blocks, refusing before it has changed anything.
     *
     * <p>Every cell is checked for replaceability first and only then is anything placed,
     * so a blocked site leaves the world untouched rather than half-built.</p>
     */
    public static StructureResult place(
            ServerLevel level, IETemplateMultiblock multiblock, BlockPos origin) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(multiblock, "multiblock");
        int placed = 0;
        for (var component : multiblock.getStructure(level)) {
            BlockPos position = origin.offset(component.pos());
            if (!level.hasChunkAt(position)) {
                return new StructureResult(false, "CHUNK_NOT_LOADED", placed);
            }
            BlockState current = level.getBlockState(position);
            if (!current.isAir() && !current.canBeReplaced()) {
                return new StructureResult(false, "BLOCK_PLACEMENT_BLOCKED", placed);
            }
        }
        for (var component : multiblock.getStructure(level)) {
            if (!level.setBlockAndUpdate(origin.offset(component.pos()), component.state())) {
                return new StructureResult(false, "BLOCK_PLACEMENT_FAILED", placed);
            }
            placed++;
        }
        return new StructureResult(true, "STRUCTURE_BUILT", placed);
    }

    /** Whether the raw template blocks are all present, before any formation. */
    public static boolean rawStructurePresent(
            ServerLevel level, IETemplateMultiblock multiblock, BlockPos origin) {
        for (var component : multiblock.getStructure(level)) {
            if (!level.getBlockState(origin.offset(component.pos())).equals(component.state())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Triggers formation with a hammer, restoring whatever the actor was holding.
     *
     * <p>IE forms a multiblock through a player interaction, so this borrows the actor's
     * hand and gives it back in a finally block — a fixture that left a hammer in a
     * FakePlayer's hand would change the next thing that actor did.</p>
     */
    public static boolean form(
            ServerLevel level,
            IETemplateMultiblock multiblock,
            BlockPos origin,
            Direction facing,
            FakePlayer actor,
            ItemStack hammer) {
        Objects.requireNonNull(hammer, "hammer");
        if (hammer.isEmpty()) throw new IllegalArgumentException("formation needs a hammer");
        ItemStack previous = actor.getItemInHand(InteractionHand.MAIN_HAND).copy();
        actor.setItemInHand(InteractionHand.MAIN_HAND, hammer.copy());
        try {
            BlockPos trigger = origin.offset(multiblock.getTriggerOffset());
            return multiblock.createStructure(level, trigger, facing, actor);
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    /**
     * The formed machine's master block entity, if the structure has come together.
     *
     * <p>Formation is observable without knowing which machine this is: IE puts a
     * {@link blusunrize.immersiveengineering.api.multiblocks.blocks.registry.MultiblockBlockEntityMaster}
     * somewhere in every formed template. Reading that machine's <em>state</em> does need
     * its own logic type, which is why that stays per-machine — but "did it form" does
     * not.</p>
     */
    public static java.util.Optional<
            blusunrize.immersiveengineering.api.multiblocks.blocks.registry
                    .MultiblockBlockEntityMaster<?>> formedMaster(
            ServerLevel level, IETemplateMultiblock multiblock, BlockPos origin) {
        for (var component : multiblock.getStructure(level)) {
            if (level.getBlockEntity(origin.offset(component.pos()))
                    instanceof blusunrize.immersiveengineering.api.multiblocks.blocks.registry
                            .MultiblockBlockEntityMaster<?> master) {
                return java.util.Optional.of(master);
            }
        }
        return java.util.Optional.empty();
    }

    /** Clears every cell a template occupies, for a fixture that must leave no trace. */
    public static void clear(ServerLevel level, IETemplateMultiblock multiblock, BlockPos origin) {
        for (BlockPos cell : componentPositions(level, multiblock, origin)) {
            level.setBlockAndUpdate(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * How many blocks each named multiblock's template needs.
     *
     * <p>Read-only, and it exists because picking a second machine to support is a
     * question about size and nothing else can answer it offline: the templates live in
     * IE's data files, so the only honest way to know is to ask the running game.</p>
     */
    public static Map<String, Integer> templateSizes(
            ServerLevel level, Map<String, IETemplateMultiblock> candidates) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        candidates.forEach((name, multiblock) -> {
            try {
                sizes.put(name, multiblock.getStructure(level).size());
            } catch (RuntimeException unavailable) {
                // A template that cannot be read is a fact worth reporting, not a crash:
                // it tells the caller this machine is not a candidate.
                sizes.put(name, -1);
            }
        });
        return Map.copyOf(sizes);
    }
}
