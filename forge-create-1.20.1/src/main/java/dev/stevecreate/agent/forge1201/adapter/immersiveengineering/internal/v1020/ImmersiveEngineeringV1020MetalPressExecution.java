package dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020;

import blusunrize.immersiveengineering.api.multiblocks.blocks.logic.IMultiblockBE;
import blusunrize.immersiveengineering.api.multiblocks.blocks.registry.MultiblockBlockEntityMaster;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import blusunrize.immersiveengineering.common.blocks.multiblocks.logic.MetalPressLogic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Exact IE 10.2.0 Metal Press lifecycle used by the isolated physical gate.
 *
 * <p>This class deliberately has no player-command entry point. The acceptance energy source is
 * marked test-only and may never be selected by production planning. Production execution must
 * supply an already verified live FE network and a material-ledger authority before this lifecycle
 * can be exposed through {@code ImmersiveEngineeringVersionAdapter.execute()}.</p>
 */
public final class ImmersiveEngineeringV1020MetalPressExecution {
    private static final BlockPos INPUT_POS = new BlockPos(0, 1, 0);
    private static final BlockPos OUTPUT_POS = new BlockPos(3, 1, 0);
    private static final int TIMEOUT_TICKS = 600;

    private ImmersiveEngineeringV1020MetalPressExecution() {}

    public static Session beginTestOnly(
            ServerLevel level,
            BlockPos origin,
            ItemStack hammer,
            ItemStack mold,
            ItemStack input,
            ItemStack expectedOutput,
            int expectedEnergyFe) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("IE Metal Press execution must start on the server thread");
        }
        if (hammer.isEmpty() || mold.isEmpty() || input.isEmpty() || expectedOutput.isEmpty()
                || expectedEnergyFe < 1) {
            throw new IllegalArgumentException("IE Metal Press test execution inputs are incomplete");
        }
        return new Session(level, origin.immutable(), hammer.copy(), mold.copy(), input.copy(),
                expectedOutput.copy(), expectedEnergyFe);
    }

    public static final class Session {
        private final UUID sessionId = UUID.randomUUID();
        private final ServerLevel level;
        private final BlockPos origin;
        private final ItemStack hammer;
        private final ItemStack mold;
        private final ItemStack input;
        private final ItemStack expectedOutput;
        private final int expectedEnergyFe;
        private final Map<BlockPos, BlockState> priorStates = new LinkedHashMap<>();
        private final List<String> journal = new ArrayList<>();
        private final List<UUID> ownedEntities = new ArrayList<>();
        private MetalPressLogic.State state;
        private BlockPos masterPosition;
        private BlockPos outputPosition;
        private int ticks;
        private int observedEnergyConsumed;
        private boolean inputAccepted;
        private boolean completed;
        private boolean cleaned;
        private String failureCode;

        private Session(
                ServerLevel level,
                BlockPos origin,
                ItemStack hammer,
                ItemStack mold,
                ItemStack input,
                ItemStack expectedOutput,
                int expectedEnergyFe) {
            this.level = level;
            this.origin = origin;
            this.hammer = hammer;
            this.mold = mold;
            this.input = input;
            this.expectedOutput = expectedOutput;
            this.expectedEnergyFe = expectedEnergyFe;
            prepareAndStart();
        }

        private void prepareAndStart() {
            try {
                journal.add("PREPARED");
                var structure = IEMultiblocks.METAL_PRESS.getStructure(level);
                for (var component : structure) {
                    BlockPos position = origin.offset(component.pos()).immutable();
                    if (!level.hasChunkAt(position)) {
                        fail("CHUNK_NOT_LOADED");
                        return;
                    }
                    BlockState prior = level.getBlockState(position);
                    if (!prior.isAir() && !prior.canBeReplaced()) {
                        fail("BLOCK_PLACEMENT_BLOCKED");
                        return;
                    }
                    priorStates.put(position, prior);
                }
                for (var component : structure) {
                    BlockPos position = origin.offset(component.pos());
                    if (!level.setBlockAndUpdate(position, component.state())) {
                        fail("BLOCK_PLACEMENT_FAILED");
                        return;
                    }
                }
                journal.add("PLACED:" + structure.size());
                FakePlayer actor = FakePlayerFactory.getMinecraft(level);
                ItemStack previousHand = actor.getItemInHand(InteractionHand.MAIN_HAND).copy();
                actor.setItemInHand(InteractionHand.MAIN_HAND, hammer.copy());
                BlockPos trigger = origin.offset(IEMultiblocks.METAL_PRESS.getTriggerOffset());
                boolean formed;
                try {
                    // SOUTH means the unmirrored template orientation: facing.opposite() is NORTH.
                    formed = IEMultiblocks.METAL_PRESS.createStructure(
                            level, trigger, Direction.SOUTH, actor);
                } finally {
                    actor.setItemInHand(InteractionHand.MAIN_HAND, previousHand);
                }
                if (!formed) {
                    fail("MULTIBLOCK_FORMATION_FAILED");
                    return;
                }
                journal.add("FORMED");
                locateMaster();
                if (state == null || masterPosition == null || outputPosition == null) {
                    fail("MULTIBLOCK_MASTER_NOT_FOUND");
                    return;
                }
                insertMold(actor);
                if (failureCode != null) return;
                // Test-only deterministic FE source. The physical machine must still consume the
                // recipe's real runtime FE. Production code is forbidden from invoking this class.
                state.getEnergy().setStoredEnergy(expectedEnergyFe);
                journal.add("ENERGY_PRELOADED_TEST_ONLY:" + expectedEnergyFe);
                feedInput();
            } catch (RuntimeException failure) {
                fail("IE_API_FAILURE:" + failure.getClass().getSimpleName());
            }
        }

        private void locateMaster() {
            for (BlockPos position : priorStates.keySet()) {
                BlockEntity entity = level.getBlockEntity(position);
                if (entity instanceof MultiblockBlockEntityMaster<?> master
                        && master.getHelper().getState() instanceof MetalPressLogic.State metalState) {
                    state = metalState;
                    masterPosition = position.immutable();
                    outputPosition = master.getHelper().getContext().getLevel()
                            .toAbsolute(OUTPUT_POS).immutable();
                    return;
                }
            }
        }

        private void insertMold(FakePlayer actor) {
            ItemStack previousHand = actor.getItemInHand(InteractionHand.MAIN_HAND).copy();
            actor.setItemInHand(InteractionHand.MAIN_HAND, mold.copy());
            try {
                BlockEntity entity = level.getBlockEntity(masterPosition);
                if (!(entity instanceof MultiblockBlockEntityMaster<?> master)) {
                    fail("MULTIBLOCK_MASTER_LOST");
                    return;
                }
                InteractionResult result = master.getHelper().click(actor, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(masterPosition), Direction.UP,
                                masterPosition, false));
                if (!result.consumesAction() || state.mold.isEmpty()
                        || !ItemStack.isSameItemSameTags(state.mold, mold)) {
                    fail("MOLD_INSERTION_FAILED");
                    return;
                }
                journal.add("MOLD_INSERTED");
            } finally {
                actor.setItemInHand(InteractionHand.MAIN_HAND, previousHand);
            }
        }

        private void feedInput() {
            BlockEntity inputEntity = null;
            for (BlockPos position : priorStates.keySet()) {
                BlockEntity candidate = level.getBlockEntity(position);
                if (candidate instanceof IMultiblockBE<?> multiblock
                        && INPUT_POS.equals(multiblock.getHelper().getPositionInMB())) {
                    inputEntity = candidate;
                    break;
                }
            }
            if (!(inputEntity instanceof IMultiblockBE<?> multiblock)) {
                fail("ITEM_INPUT_PORT_NOT_FOUND");
                return;
            }
            BlockPos inputPosition = inputEntity.getBlockPos();
            ItemEntity itemEntity = new ItemEntity(level,
                    inputPosition.getX() + 0.5D, inputPosition.getY() + 0.5D,
                    inputPosition.getZ() + 0.5D, input.copy());
            if (!level.addFreshEntity(itemEntity)) {
                fail("ITEM_INPUT_SPAWN_FAILED");
                return;
            }
            ownedEntities.add(itemEntity.getUUID());
            multiblock.getHelper().onEntityCollided(itemEntity);
            inputAccepted = !itemEntity.isAlive() || itemEntity.getItem().getCount() < input.getCount();
            if (!inputAccepted) {
                fail("ITEM_INPUT_REJECTED");
                return;
            }
            journal.add("INPUT_ACCEPTED:" + input.getCount());
        }

        public Optional<Result> tick() {
            if (completed || cleaned) return Optional.of(result());
            if (failureCode != null) {
                cleanup();
                return Optional.of(result());
            }
            ticks++;
            if (state == null || masterPosition == null
                    || !(level.getBlockEntity(masterPosition)
                    instanceof MultiblockBlockEntityMaster<?>)) {
                fail("MULTIBLOCK_CHANGED");
            } else {
                observedEnergyConsumed = Math.max(observedEnergyConsumed,
                        expectedEnergyFe - state.getEnergy().getEnergyStored());
                ItemEntity output = findExpectedOutput();
                if (output != null) {
                    ownedEntities.add(output.getUUID());
                    journal.add("OUTPUT_VERIFIED:" + output.getItem().getCount());
                    completed = true;
                    cleanup();
                    return Optional.of(result());
                }
                if (ticks >= TIMEOUT_TICKS) fail("PROCESS_TIMEOUT");
            }
            if (failureCode != null) {
                cleanup();
                return Optional.of(result());
            }
            return Optional.empty();
        }

        private ItemEntity findExpectedOutput() {
            if (outputPosition == null) return null;
            AABB search = new AABB(outputPosition).inflate(4.0D);
            return level.getEntitiesOfClass(ItemEntity.class, search, entity ->
                    entity.isAlive()
                            && ItemStack.isSameItemSameTags(entity.getItem(), expectedOutput)
                            && entity.getItem().getCount() >= expectedOutput.getCount())
                    .stream().findFirst().orElse(null);
        }

        private void fail(String code) {
            if (failureCode == null) {
                failureCode = code;
                journal.add("FAILED:" + code);
            }
        }

        private void cleanup() {
            if (cleaned) return;
            for (UUID entityId : ownedEntities) {
                var entity = level.getEntity(entityId);
                if (entity != null) entity.discard();
            }
            priorStates.forEach(level::setBlockAndUpdate);
            cleaned = true;
            boolean restored = priorStates.entrySet().stream().allMatch(entry ->
                    level.getBlockState(entry.getKey()).equals(entry.getValue()));
            if (!restored && failureCode == null) fail("CLEANUP_MISMATCH");
            journal.add(restored ? "RESTORED" : "RESTORE_FAILED");
        }

        public Result result() {
            boolean success = completed && failureCode == null && cleaned
                    && inputAccepted && observedEnergyConsumed == expectedEnergyFe;
            return new Result(sessionId, success,
                    success ? "OK" : failureCode == null ? "IN_PROGRESS" : failureCode,
                    ticks, observedEnergyConsumed, inputAccepted, completed, cleaned,
                    List.copyOf(journal));
        }
    }

    public record Result(
            UUID sessionId,
            boolean success,
            String code,
            int ticks,
            int energyConsumedFe,
            boolean inputAccepted,
            boolean outputVerified,
            boolean restored,
            List<String> journal) {
        public Result {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(code, "code");
            journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
        }
    }
}
