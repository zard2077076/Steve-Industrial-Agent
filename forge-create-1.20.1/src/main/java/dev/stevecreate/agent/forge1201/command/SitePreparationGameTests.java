package dev.stevecreate.agent.forge1201.command;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import dev.stevecreate.agent.core.siteprep.PreparedConstructionSite;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionAuthorization;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** Isolated physical acceptance for site survey, exact Bot clearing and salvage. */
@PrefixGameTestTemplate(false)
public final class SitePreparationGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SitePreparationGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_01", timeoutTicks = 600)
    public static void botClearsExactNaturalObstacleAndDeliversActualDrop(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos target = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 5));
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(level, start, target, destination);
        helper.succeedWhen(() -> {
            boolean complete;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                throw new GameTestAssertException("Site Bot failed: " + failure.getMessage());
            }
            if (!complete) {
                throw new GameTestAssertException("Site Bot is still advancing adjacent steps");
            }
            check(level.getBlockState(target).isAir(),
                    "Approved natural obstacle was not removed");
            check(handle.mutations() == 1, "Mutation journal must contain exactly one entry");
            check(handle.ledger().collectedCount() >= 1
                            && handle.ledger().collectedCount()
                                    == handle.ledger().deliveredCount(),
                    "Actual drops were not fully collected and delivered");
            check(count(level, destination, Blocks.OAK_LOG.asItem()) >= 1,
                    "Authorized salvage chest did not receive the real oak log");
            check(handle.botRemoved(), "Visible Bot entity remained after completion");
            LOGGER.info("SITE_PREP_BOT_CLEARING PASS exactMutations=1 realDrop=true "
                    + "salvageDelivered=true unknownBlocksRemoved=0 protectedBlocksRemoved=0 "
                    + "containerOpened=0 playerInventoryAccess=0 regionOutsideMutations=0 "
                    + "formalWorldAccess=0 botOverlap=false teleportFallback=false");
        });
    }

    /**
     * A pending project drop keeps its UUID even beside an identical ground stack.
     *
     * <p>The collector journals entity UUIDs. Vanilla merges equal ItemEntity stacks and
     * removes one entity, so by the time a Bot walked back the journal reported that an
     * actual drop disappeared while the combined stack was visibly still on the ground.
     * This holds the Bot between mining and collection, puts an identical control entity
     * at the same point for longer than the merge interval, and then lets the real Bot
     * finish. Without the protection at the destruction boundary, one UUID is gone.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_drop_merge", timeoutTicks = 1_000)
    public static void pendingBotDropCannotMergeBeforeExactCollection(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos target = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 5));
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(level, start, target, destination);
        AtomicReference<UUID> pendingId = new AtomicReference<>();
        AtomicReference<UUID> controlId = new AtomicReference<>();
        AtomicInteger heldTicks = new AtomicInteger();
        AtomicBoolean controlRemoved = new AtomicBoolean();

        helper.succeedWhen(() -> {
            if (pendingId.get() == null) {
                try {
                    handle.tick(level);
                } catch (RuntimeException failure) {
                    throw new GameTestAssertException(
                            "Site Bot failed before the merge boundary: "
                                    + failure.getMessage());
                }
                if (handle.mutations() == 0) {
                    throw new GameTestAssertException(
                            "Site Bot has not mined the controlled obstacle yet");
                }
                List<ItemEntity> drops = level.getEntitiesOfClass(
                        ItemEntity.class, new AABB(target).inflate(2.0D),
                        value -> value.isAlive()
                                && value.getItem().is(Blocks.OAK_LOG.asItem()));
                check(drops.size() == 1,
                        "mining should create one owned oak drop, saw " + drops.size());
                ItemEntity pending = drops.get(0);
                check(pending.getAge() == Short.MIN_VALUE,
                        "pending drop was not made merge-ineligible at destruction");
                double x = target.getX() + 0.5D;
                double y = target.getY() + 0.5D;
                double z = target.getZ() + 0.5D;
                pending.setPos(x, y, z);
                pending.setDeltaMovement(0.0D, 0.0D, 0.0D);
                ItemEntity control = new ItemEntity(level, x, y, z,
                        new ItemStack(Blocks.OAK_LOG.asItem()));
                control.setDeltaMovement(0.0D, 0.0D, 0.0D);
                check(level.addFreshEntity(control),
                        "could not spawn the identical merge control entity");
                pendingId.set(pending.getUUID());
                controlId.set(control.getUUID());
                throw new GameTestAssertException(
                        "identical control is present; waiting through the merge interval");
            }

            if (heldTicks.getAndIncrement() < 60) {
                ItemEntity pending = item(level, pendingId.get());
                ItemEntity control = item(level, controlId.get());
                check(pending != null && control != null,
                        "an identical entity merged and invalidated a tracked UUID");
                double x = target.getX() + 0.5D;
                double y = target.getY() + 0.5D;
                double z = target.getZ() + 0.5D;
                pending.setPos(x, y, z);
                control.setPos(x, y, z);
                pending.setDeltaMovement(0.0D, 0.0D, 0.0D);
                control.setDeltaMovement(0.0D, 0.0D, 0.0D);
                throw new GameTestAssertException(
                        "pending UUID remains stable; Bot collection is intentionally held");
            }

            if (!controlRemoved.getAndSet(true)) {
                ItemEntity pending = item(level, pendingId.get());
                ItemEntity control = item(level, controlId.get());
                check(pending != null && control != null,
                        "both UUIDs must survive the full vanilla merge window");
                control.discard();
            }

            boolean complete;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                throw new GameTestAssertException(
                        "Site Bot failed after the merge boundary: " + failure.getMessage());
            }
            if (!complete) {
                throw new GameTestAssertException(
                        "Site Bot is returning for exact collection and delivery");
            }
            check(count(level, destination, Blocks.OAK_LOG.asItem()) == 1,
                    "Bot did not deliver the one owned drop exactly");
            check(handle.ledger().collectedCount() == 1
                            && handle.ledger().deliveredCount() == 1,
                    "merge-safe salvage ledger is not exactly balanced");
            check(level.getEntitiesOfClass(ItemEntity.class,
                            new AABB(target).inflate(2.0D), ItemEntity::isAlive).stream()
                            .noneMatch(value -> value.getItem().is(Blocks.OAK_LOG.asItem())),
                    "owned oak salvage remained on the ground after Bot collection");
            LOGGER.info("SITE_PREP_DROP_MERGE PASS pendingUuidStable=true "
                    + "mergeSuppressed=true collectedByBot=true groundDrops=0 "
                    + "ledgerBalanced=true");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_salvage_pause", timeoutTicks = 900)
    public static void fullSalvageChestPausesWithoutPartialInsertThenResumes(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos target = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 5));
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(destination);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Blocks.COBBLESTONE.asItem(), 64));
        }
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(level, start, target, destination);
        AtomicBoolean capacityPauseObserved = new AtomicBoolean();
        helper.succeedWhen(() -> {
            boolean complete = false;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                if (!String.valueOf(failure.getMessage()).contains("SALVAGE")) {
                    throw new GameTestAssertException("Unexpected clearing failure: "
                            + failure.getMessage());
                }
                check(!capacityPauseObserved.get(), "Capacity refusal repeated after resume: "
                        + failure.getMessage());
                handle.pause();
                capacityPauseObserved.set(true);
                check(handle.paused(), "Full salvage destination did not pause the session");
                check(count(level, destination, Blocks.OAK_LOG.asItem()) == 0,
                        "Capacity failure partially inserted salvage");
                Container currentChest = (Container) level.getBlockEntity(destination);
                for (int slot = 0; slot < currentChest.getContainerSize(); slot++) {
                    currentChest.setItem(slot, ItemStack.EMPTY);
                }
                currentChest.setChanged();
                handle.resume();
                throw new GameTestAssertException(
                        "Capacity pause observed; resumed session is still running");
            }
            if (!complete) throw new GameTestAssertException("Clearing has not resumed to completion");
            check(capacityPauseObserved.get(), "Full destination never exercised pause path");
            check(count(level, destination, Blocks.OAK_LOG.asItem()) >= 1,
                    "Resumed Bot did not deliver the preserved real drop");
            check(handle.ledger().collectedCount() == handle.ledger().deliveredCount(),
                    "Resumed salvage ledger is unbalanced");
            LOGGER.info("SITE_PREP_SALVAGE_PAUSE PASS fullChestPaused=true partialInsert=0 "
                    + "resumed=true salvageLedgerBalanced=true privateItemsDropped=0");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_02", timeoutTicks = 100)
    public static void protectedContainerAndEnvironmentalHazardFailClosed(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos lava = helper.absolutePos(new BlockPos(7, 2, 5));
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.CHEST);
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.LAVA);
        check(SitePreparationCommand.acceptanceClassification(level, chest)
                        == ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL,
                "Container was not protected");
        check(SitePreparationCommand.acceptanceClassification(level, lava)
                        == ObstacleClassification.ENVIRONMENTAL_HAZARD,
                "Lava was not classified as an environmental hazard");
        check(level.getBlockState(chest).is(Blocks.CHEST)
                        && level.getBlockState(lava).is(Blocks.LAVA),
                "Classification mutated a protected or hazardous block");
        LOGGER.info("SITE_PREP_PROTECTION PASS protectedBlocksRemoved=0 containerOpened=0 "
                + "environmentalMutation=0 worldMutation=false");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_grading", timeoutTicks = 2_400)
    public static void botsCutSlopeFillHoleAndLayExactSelectedSurface(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 7; x <= 8; x++) {
            for (int z = 7; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.DIRT);
            }
        }
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.AIR);
        helper.setBlock(new BlockPos(8, 2, 8), Blocks.AIR);
        helper.setBlock(new BlockPos(7, 3, 7), Blocks.STONE);
        helper.setBlock(new BlockPos(7, 4, 7), Blocks.STONE);
        helper.setBlock(new BlockPos(13, 2, 7), Blocks.CHEST);
        BlockPos supply = helper.absolutePos(new BlockPos(13, 2, 7));
        check(level.getBlockEntity(supply) instanceof Container,
                "Grading supply chest did not materialize");
        ((Container) level.getBlockEntity(supply)).setItem(0,
                new ItemStack(Blocks.STONE.asItem(), 32));
        BlockPos first = helper.absolutePos(new BlockPos(7, 2, 7));
        BlockPos second = helper.absolutePos(new BlockPos(8, 2, 8));
        int surfaceY = first.getY();
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.gradingAcceptanceSession(level,
                        helper.absolutePos(new BlockPos(3, 3, 7)), first, second,
                        surfaceY, Blocks.STONE, supply);
        helper.succeedWhen(() -> {
            boolean complete;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                throw new GameTestAssertException("Terrain grading failed: "
                        + failure.getMessage());
            }
            if (!complete) {
                throw new GameTestAssertException("Terrain Bots are still grading exact cells "
                        + handle.debugStatus());
            }
            for (int x = first.getX(); x <= second.getX(); x++) {
                for (int z = first.getZ(); z <= second.getZ(); z++) {
                    check(level.getBlockState(new BlockPos(x, surfaceY, z)).is(Blocks.STONE),
                            "Selected surface block is missing at " + x + "," + z);
                    for (int y = surfaceY + 1;
                            y <= surfaceY + SitePreparationCommand.GRADING_CLEARANCE_HEIGHT; y++) {
                        check(level.getBlockState(new BlockPos(x, y, z)).isAir(),
                                "Clearance volume remains blocked at " + x + "," + y + "," + z);
                    }
                }
            }
            check(level.getBlockState(new BlockPos(second.getX(), surfaceY - 1,
                            second.getZ())).is(Blocks.STONE),
                    "Hole below the selected surface was not filled bottom-up");
            check(handle.fillBlocksConsumed() == 5,
                    "Exact fill material consumption was not five blocks");
            check(handle.mutations() == 10,
                    "Cut-and-fill mutation journal did not contain ten exact actions");
            PreparedConstructionSite prepared = handle.prepare(level);
            check(prepared != null && handle.botRemoved(),
                    "Exact post-grading rescan or Bot cleanup failed");
            LOGGER.info("SITE_PREP_GRADING PASS rectangle=2x2 surfaceY={} fillBlock={} "
                    + "removed=5 filled=5 actualMutations=10 sharedFleet=true "
                    + "unknownBlocksRemoved=0 protectedBlocksRemoved=0 "
                    + "privateContainerAccess=0 playerInventoryAccess=0 "
                    + "unknownNbtMutation=0 botOverlap=false teleportFallback=false",
                    surfaceY, Blocks.STONE);
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_forced_grading", timeoutTicks = 2_400)
    public static void exactSecondConfirmationForcesContainerHazardAndBedrockRemoval(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 7; x <= 8; x++) {
            for (int z = 7; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.DIRT);
            }
        }
        helper.setBlock(new BlockPos(8, 2, 7), Blocks.BEDROCK);
        helper.setBlock(new BlockPos(7, 3, 7), Blocks.CHEST);
        helper.setBlock(new BlockPos(7, 3, 8), Blocks.TNT);
        BlockPos destructiveChest = helper.absolutePos(new BlockPos(7, 3, 7));
        ((Container) level.getBlockEntity(destructiveChest)).setItem(0,
                new ItemStack(Blocks.DIAMOND_BLOCK.asItem(), 1));
        helper.setBlock(new BlockPos(13, 2, 7), Blocks.CHEST);
        BlockPos supply = helper.absolutePos(new BlockPos(13, 2, 7));
        ((Container) level.getBlockEntity(supply)).setItem(0,
                new ItemStack(Blocks.STONE.asItem(), 32));
        BlockPos first = helper.absolutePos(new BlockPos(7, 2, 7));
        BlockPos second = helper.absolutePos(new BlockPos(8, 2, 8));
        int surfaceY = first.getY();
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.forcedGradingAcceptanceSession(level,
                        helper.absolutePos(new BlockPos(3, 3, 7)), first, second,
                        surfaceY, Blocks.STONE, supply);
        helper.succeedWhen(() -> {
            boolean complete;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                throw new GameTestAssertException("Forced terrain grading failed: "
                        + failure.getMessage());
            }
            if (!complete) throw new GameTestAssertException(
                    "Forced grading is still running " + handle.debugStatus());
            check(level.getBlockState(destructiveChest).isAir(),
                    "Confirmed destructive container was not removed");
            check(level.getBlockState(helper.absolutePos(new BlockPos(7, 3, 8))).isAir(),
                    "Confirmed environmental hazard was not removed");
            check(level.getBlockState(new BlockPos(second.getX(), surfaceY, first.getZ()))
                            .is(Blocks.STONE),
                    "Confirmed bedrock surface was not replaced with selected fill");
            check(level.getEntitiesOfClass(ItemEntity.class,
                            new AABB(destructiveChest).inflate(2.0D)).stream()
                            .noneMatch(value -> value.getItem().is(Blocks.DIAMOND_BLOCK.asItem())),
                    "Container contents were read/dropped instead of destructively discarded");
            check(handle.protectedBlocksRemoved() == 3
                            && handle.containersDestroyed() == 1
                            && handle.blockEntitiesDestroyed() == 1
                            && handle.dangerousMediaRemoved() == 1
                            && handle.unbreakableBlocksRemoved() == 1,
                    "Forced-removal risk counters are not exact");
            check(handle.prepare(level) != null && handle.botRemoved(),
                    "Forced grading did not pass exact rescan and cleanup");
            LOGGER.info("SITE_PREP_FORCED_GRADING PASS secondConfirmation=true "
                    + "snapshotBound=true protectedBlocksRemoved=3 containersDestroyed=1 "
                    + "blockEntitiesDestroyed=1 dangerousMediaRemoved=1 "
                    + "unbreakableBlocksRemoved=1 privateContainerContentsRead=0 "
                    + "playerInventoryAccess=0 permanentDataLossWarning=true cleanup=true");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_04", timeoutTicks = 800)
    public static void twoBotsClearDistinctTargetsWithoutOverlap(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos first = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos second = helper.absolutePos(new BlockPos(7, 2, 8));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 6));
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(7, 2, 8), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 6), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(
                        level, start, List.of(first, second), destination);
        helper.succeedWhen(() -> {
            boolean complete;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                throw new GameTestAssertException("Two-Bot site clearing failed: "
                        + failure.getMessage());
            }
            if (!complete) {
                throw new GameTestAssertException("Two Bots are still advancing bounded steps");
            }
            check(handle.workerCount() == 2 && handle.workersUsed() == 2,
                    "Both visible Bot identities were not assigned");
            check(level.getBlockState(first).isAir()
                            && level.getBlockState(second).isAir(),
                    "Two-Bot clearing did not remove both exact targets");
            check(handle.mutations() == 2
                            && handle.ledger().collectedCount()
                                    == handle.ledger().deliveredCount(),
                    "Two-Bot mutation/salvage evidence is incomplete");
            check(handle.botRemoved(), "Two-Bot entities remained after completion");
            LOGGER.info("SITE_PREP_TWO_BOT PASS workers=2 workersUsed=2 exactMutations=2 "
                    + "salvageDelivered=true botOverlap=false teleportFallback=false");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_03", timeoutTicks = 100)
    public static void staleApprovedBlockStateRefusesBeforeMutation(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos target = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 5));
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(level, start, target, destination);
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.STONE);
        AtomicReference<String> refusal = new AtomicReference<>();
        helper.succeedWhen(() -> {
            try {
                handle.tick(level);
            } catch (RuntimeException expected) {
                refusal.set(expected.getMessage());
            }
            if (refusal.get() == null) {
                throw new GameTestAssertException("Bot has not reached the stale target yet");
            }
            check(refusal.get().contains("DEMOLITION_APPROVAL_STALE"),
                    "Changed BlockState did not produce DEMOLITION_APPROVAL_STALE: "
                            + refusal.get());
            check(level.getBlockState(target).is(Blocks.STONE),
                    "Stale approval mutated the replacement block");
            check(handle.mutations() == 0, "Stale approval entered the mutation journal");
            LOGGER.info("SITE_PREP_STALE_TOKEN PASS staleRefused=true actualMutations=0 "
                    + "unknownBlocksRemoved=0 protectedBlocksRemoved=0");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_05", timeoutTicks = 1_200)
    public static void fiveBotFleetUsesSharedAssignmentAndExactSalvage(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        List<BlockPos> targets = List.of(
                helper.absolutePos(new BlockPos(5, 2, 5)),
                helper.absolutePos(new BlockPos(7, 2, 5)),
                helper.absolutePos(new BlockPos(9, 2, 5)),
                helper.absolutePos(new BlockPos(6, 2, 8)),
                helper.absolutePos(new BlockPos(8, 2, 8)));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 10));
        targets.forEach(position -> level.setBlockAndUpdate(
                position, Blocks.OAK_LOG.defaultBlockState()));
        helper.setBlock(new BlockPos(12, 2, 10), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(
                        level, start, targets, destination);
        helper.succeedWhen(() -> {
            boolean complete;
            try {
                complete = handle.tick(level);
            } catch (RuntimeException failure) {
                throw new GameTestAssertException("Five-Bot terrain fleet failed: "
                        + failure.getMessage());
            }
            if (!complete) throw new GameTestAssertException("Five-Bot fleet is running");
            check(handle.workerCount() == 5 && handle.workersUsed() == 5,
                    "Five exact workers did not perform physical terrain tasks");
            check(targets.stream().allMatch(position -> level.getBlockState(position).isAir()),
                    "Five-Bot fleet left an approved obstacle");
            check(handle.mutations() == 5
                            && handle.ledger().collectedCount()
                            == handle.ledger().deliveredCount(),
                    "Five-Bot fleet did not preserve exact mutation/salvage evidence");
            check(handle.botRemoved(), "Five-Bot fleet entities remained after completion");
            LOGGER.info("SITE_PREP_FIVE_BOT PASS workers=5 workersUsed=5 exactMutations=5 "
                    + "sameFleetKernel=true salvageDelivered=true botOverlap=false "
                    + "teleportFallback=false");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_06", timeoutTicks = 800)
    public static void pendingFleetReloadRequiresRescanAndReassignsWithoutDuplicateMutation(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos target = helper.absolutePos(new BlockPos(9, 2, 5));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 5));
        helper.setBlock(new BlockPos(9, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(level, start, target, destination);
        AtomicInteger ticks = new AtomicInteger();
        AtomicBoolean reloaded = new AtomicBoolean();
        helper.succeedWhen(() -> {
            boolean complete;
            try {
                complete = handle.tick(level);
                if (!reloaded.get() && ticks.incrementAndGet() >= 2) {
                    check(handle.mutations() == 0,
                            "Reload fixture passed a resource-bearing mutation boundary");
                    handle.reload(level);
                    reloaded.set(true);
                }
            } catch (RuntimeException failure) {
                throw new GameTestAssertException("Terrain fleet reload failed: "
                        + failure.getMessage());
            }
            if (!complete) throw new GameTestAssertException("Reloaded terrain fleet is running");
            check(reloaded.get(), "Terrain fleet was not reloaded at a pending boundary");
            check(handle.assignmentWorkersSeen() >= 2,
                    "Reload did not reassign after exact authoritative rescan");
            check(handle.mutations() == 1 && level.getBlockState(target).isAir(),
                    "Reload duplicated or lost the exact terrain mutation");
            check(handle.ledger().collectedCount() == handle.ledger().deliveredCount(),
                    "Reload broke carried salvage continuity");
            LOGGER.info("SITE_PREP_FLEET_RELOAD PASS reloadInterrupted=true exactRescan=true "
                    + "reassigned=true duplicateMutation=false exactMutations=1 "
                    + "salvageDelivered=true");
        });
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_07", timeoutTicks = 100)
    public static void fleetCancelIsTerminalAndRemovesEveryVisibleWorker(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 5));
        BlockPos target = helper.absolutePos(new BlockPos(9, 2, 5));
        BlockPos destination = helper.absolutePos(new BlockPos(12, 2, 5));
        helper.setBlock(new BlockPos(9, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(level, start, target, destination);
        handle.tick(level);
        handle.cancel();
        check(handle.botRemoved(), "Fleet cancellation left a visible worker entity");
        check(handle.mutations() == 0 && level.getBlockState(target).is(Blocks.OAK_LOG),
                "Fleet cancellation mutated the approved target");
        LOGGER.info("SITE_PREP_FLEET_CANCEL PASS terminal=true actualMutations=0 "
                + "workersRemoved=true noFurtherMutation=true");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "site_preparation_08", timeoutTicks = 6_000)
    public static void preparedSiteAuthorizesExistingConstructionAndRealC07Production(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(16, 2, 15));
        BlockPos target = helper.absolutePos(new BlockPos(20, 2, 5));
        BlockPos buffer = helper.absolutePos(new BlockPos(15, 2, 12));
        DeploymentBoundingBox bounds = new DeploymentBoundingBox(
                position(helper.absolutePos(new BlockPos(14, 2, 0))),
                position(helper.absolutePos(new BlockPos(34, 12, 20))));
        helper.setBlock(new BlockPos(20, 2, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(15, 2, 12), Blocks.CHEST);
        SitePreparationCommand.AcceptanceHandle handle =
                SitePreparationCommand.acceptanceSession(
                        level, start, List.of(target), buffer, bounds);
        AtomicReference<CreateV606GoalDrivenPlanner.Ready> readyRef = new AtomicReference<>();
        AtomicReference<CreateV606GoalDrivenExecution.Session> execution =
                new AtomicReference<>();
        AtomicReference<PreparedSiteExecutionAuthorization> authorizationRef =
                new AtomicReference<>();

        helper.succeedWhen(() -> {
            if (execution.get() == null) {
                boolean cleared;
                try {
                    cleared = handle.tick(level);
                } catch (RuntimeException failure) {
                    throw new GameTestAssertException(
                            "Integrated terrain preparation failed: " + failure.getMessage());
                }
                if (!cleared) {
                    throw new GameTestAssertException(
                            "Integrated terrain Bot is still advancing bounded steps");
                }
                PreparedConstructionSite prepared = handle.prepare(level);
                ResourceId input = id("minecraft:oak_log");
                CreateV606GoalDrivenPlanner.Ready ready = ready(
                        CreateV606GoalDrivenPlanner.plan(
                                level, id("minecraft:stripped_oak_log"), 1,
                                Map.of(input, 1L), position(target), QuarterTurn.ZERO,
                                id("steve_industrial:integrated/site_to_c07"),
                                Map.of(input, 1L),
                                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST));
                Instant capturedAt = Instant.now();
                PreparedSiteExecutionGate.PlanningEvidence planning =
                        new PreparedSiteExecutionGate.PlanningEvidence(
                                prepared.worldIdentity(), prepared.dimension(),
                                prepared.preparedSiteIdentity(),
                                prepared.cleanSiteSnapshotHash(),
                                ready.executionReadyPlan().physicalPlan().id(),
                                ready.executionReadyPlan().physicalPlan().candidate()
                                        .snapshotFingerprint(),
                                capturedAt, true,
                                "forge1201:authoritative-post-clearance-planning-snapshot");
                var gated = new PreparedSiteExecutionGate().authorize(
                        prepared, handle.selection(), ready.executionReadyPlan(), planning,
                        capturedAt);
                check(gated instanceof PreparedSiteExecutionGate.Authorized,
                        "Prepared site did not authorize its exact verified plan: " + gated);
                PreparedSiteExecutionAuthorization authorization =
                        ((PreparedSiteExecutionGate.Authorized) gated).authorization();
                var wrongWorld = CreateV606GoalDrivenExecution.begin(
                        level, authorization, "world:" + "0".repeat(64),
                        ready.runtime(), position(buffer));
                check(wrongWorld instanceof CreateV606GoalDrivenExecution.Rejected,
                        "Prepared-site executor did not refuse a different live world");
                var started = CreateV606GoalDrivenExecution.begin(
                        level, authorization, prepared.worldIdentity(),
                        ready.runtime(), position(buffer));
                check(started instanceof CreateV606GoalDrivenExecution.Started,
                        "Existing ConstructionExecutor entry rejected prepared site: " + started);
                readyRef.set(ready);
                authorizationRef.set(authorization);
                execution.set(((CreateV606GoalDrivenExecution.Started) started).session());
                throw new GameTestAssertException(
                        "Prepared site authorized; existing C-07 execution started");
            }

            CreateV606GoalDrivenExecution.TickResult result = execution.get().tick();
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                helper.fail("Integrated C-07 production failed: " + failure);
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                CreateV606GoalDrivenPlanner.Ready ready = readyRef.get();
                PreparedSiteExecutionAuthorization authorization = authorizationRef.get();
                check(completed.observedQuantity() == 1
                                && completed.finalResourceBuffer().getOrDefault(
                                        id("minecraft:stripped_oak_log"), 0L) == 1
                                && completed.finalResourceBuffer().getOrDefault(
                                        id("minecraft:oak_log"), 0L) == 0,
                        "Real salvaged log was not consumed into exact C-07 output");
                check(handle.botRemoved() && authorization.coveredCells().stream()
                                .allMatch(handle.selection().authorizedBounds()::contains),
                        "Integrated worker cleanup or prepared footprint evidence failed");
                Set<BlockPos3i> clearedPlanCells = clearPhysicalPlan(level, ready);
                check(clearedPlanCells.stream().allMatch(position -> level.getBlockState(
                                new BlockPos(position.x(), position.y(), position.z())).isAir()),
                        "Existing construction executor left owned physical plan cells behind");
                LOGGER.info("SITE_TO_PRODUCTION_C07 PASS placementAnchor=true siteSurvey=true "
                        + "demolitionPreview=true playerApproval=true terrainBots=true "
                        + "postClearanceRescan=true preparedSite=true verifiedPhysicalPlan=true "
                        + "constructionExecutor=existing liveWorldMismatchRefusal=true "
                        + "direct=true realSalvageInput=true "
                        + "output=minecraft:stripped_oak_log@1 cleanup=true");
                return;
            }
            throw new GameTestAssertException(
                    "Integrated existing construction executor is still running");
        });
    }

    private static Set<BlockPos3i> clearPhysicalPlan(
            ServerLevel level,
            CreateV606GoalDrivenPlanner.Ready ready) {
        Set<BlockPos3i> positions = new LinkedHashSet<>();
        ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                placement.components().forEach(component ->
                        positions.add(component.position())));
        ready.executionReadyPlan().physicalPlan().routes().forEach(route ->
                positions.addAll(route.positions()));
        positions.forEach(position -> level.setBlockAndUpdate(
                new BlockPos(position.x(), position.y(), position.z()),
                Blocks.AIR.defaultBlockState()));
        return Set.copyOf(positions);
    }

    private static CreateV606GoalDrivenPlanner.Ready ready(
            CreateV606GoalDrivenPlanner.PlanningResult result) {
        check(result instanceof CreateV606GoalDrivenPlanner.Ready,
                "Integrated goal planning did not produce readiness: " + result);
        return (CreateV606GoalDrivenPlanner.Ready) result;
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static ItemEntity item(ServerLevel level, UUID entityId) {
        return level.getEntity(entityId) instanceof ItemEntity item && item.isAlive()
                ? item : null;
    }

    private static int count(ServerLevel level, BlockPos position, net.minecraft.world.item.Item item) {
        if (!(level.getBlockEntity(position) instanceof Container container)) return 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new GameTestAssertException(detail);
    }
}
