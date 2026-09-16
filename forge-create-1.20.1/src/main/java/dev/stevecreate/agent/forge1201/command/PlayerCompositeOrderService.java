package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.execution.construction.VerifiedPlanMaterialSnapshot;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlan;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlanFactory;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositeSettlementV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.industrial.CompositeResumePlanV1;
import dev.stevecreate.agent.core.industrial.IndustrialCompletionReportV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderPlanV1;
import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606BranchMergeExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606CompositeExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ThreeModeExecution;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Transaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Starts one reviewed Composite order from a player, through the same order envelope,
 * material ledger and real wrapper the single-stage projects already use.
 *
 * <p>Before IPO-02 a Composite graph could only run from an acceptance fixture that
 * hand-placed every chest and seeded every input.  That is why a graph was explicitly
 * not a construction authorization: nothing connected it to a player's reserved
 * material.  This service is that connection.  It plans every stage against the live
 * runtime, merges the verified stage snapshots into one reservation authority, binds
 * the result to the durable {@code industrial-player-order-v1} envelope, reserves and
 * physically withdraws every stage and route material from the player's own chest, and
 * only then starts the existing wrapper.</p>
 *
 * <p>Nothing here is free-built and nothing completes on graph evidence alone: the
 * order's report is derived from the wrapper's physical stage and route evidence plus
 * the ledger, and a run that cannot balance both pauses instead of reporting success.</p>
 */
public final class PlayerCompositeOrderService {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PlayerCompositeOrderService.class);
    private static final int MAX_ORIGIN_DISTANCE_SQUARED = 48 * 48;
    private static final ResourceId CHEST = id("minecraft:chest");
    private static final ResourceId HOPPER = id("minecraft:hopper");
    private static final ResourceId REDSTONE_BLOCK = id("minecraft:redstone_block");
    private static final Set<ResourceId> INFRASTRUCTURE = Set.of(CHEST, HOPPER, REDSTONE_BLOCK);
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private PlayerCompositeOrderService() {}

    public static StartResult create(
            ServerPlayer player,
            BlockPos sourcePosition,
            BlockPos origin,
            ResourceId orderType,
            ExecutionMode mode) {
        return create(player, List.of(sourcePosition), origin, orderType, mode);
    }

    /**
     * Places an order drawing on more than one container.
     *
     * <p>The warehouse projection has always reserved across a list of sources and the
     * selection call has always appended to it; only this entry point insisted on
     * exactly one, which capped an order at whatever fits in a single chest.
     *
     * <p>The first position is the primary. Refunds, salvage and the produced output all
     * go back there, because a player who spread material across containers still needs
     * one place to look, and splitting a return across sources would have to invent a
     * rule for which item goes where.
     *
     * <p>Discovery is deliberately not part of this. Every source still goes through the
     * same selection call, with its ownership, distance and permission checks — being
     * able to name several containers is not permission to take from containers the
     * player did not name.</p>
     */
    public static StartResult create(
            ServerPlayer player,
            List<BlockPos> sourcePositions,
            BlockPos origin,
            ResourceId orderType,
            ExecutionMode mode) {
        return create(player, sourcePositions, origin, orderType, mode, false);
    }

    /**
     * Places an order that may leave its site standing.
     *
     * <p>Every order so far has torn its site down on completion, which makes a
     * production line a single batch. A resident line is the point of C5-B, and the
     * accounting has to say which of the two happened: the infrastructure came out of the
     * player's chest either way, and settlement distinguishes a site kept on purpose from
     * one merely left behind.</p>
     *
     * @param retainSite leave the built site standing instead of clearing it
     */
    public static StartResult create(
            ServerPlayer player,
            List<BlockPos> sourcePositions,
            BlockPos origin,
            ResourceId orderType,
            ExecutionMode mode,
            boolean retainSite) {
        if (sourcePositions.isEmpty()) return StartResult.failure("MATERIAL_SOURCE_REQUIRED");
        BlockPos sourcePosition = sourcePositions.get(0);
        if (sourcePositions.stream().distinct().count() != sourcePositions.size()) {
            return StartResult.failure("MATERIAL_SOURCE_DUPLICATED");
        }
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) return StartResult.failure("SERVER_THREAD_REQUIRED");
        PilotWorldMarkerSavedData.Marker marker =
                PilotWorldMarkerSavedData.forLevel(level).marker().orElse(null);
        if (marker == null) return StartResult.failure("WORLD_NOT_AUTHORIZED");
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return StartResult.failure("ORDER_DIMENSION_UNSUPPORTED");
        }
        if (!player.mayBuild()) return StartResult.failure("PLAYER_BUILD_PERMISSION_DENIED");
        if (player.distanceToSqr(origin.getX() + 0.5D, origin.getY() + 0.5D,
                origin.getZ() + 0.5D) > MAX_ORIGIN_DISTANCE_SQUARED) {
            return StartResult.failure("COMPOSITE_ORIGIN_OUT_OF_RANGE");
        }
        if (ACTIVE.values().stream().anyMatch(value -> value.ownerId().equals(player.getUUID()))) {
            return StartResult.failure("PLAYER_ALREADY_HAS_ACTIVE_COMPOSITE_ORDER");
        }
        UUID projectId = UUID.randomUUID();
        Prepared prepared = prepare(level, orderType, pos(origin), projectId);
        if (!prepared.success()) return StartResult.failure(prepared.code());
        CompositePlayerOrderSpecV1 spec = prepared.spec();
        CompositeSiteLayout layout = prepared.layout();
        List<PlannedStage> planned = prepared.stages();
        String runtime = prepared.runtime();
        VerifiedProjectMaterialPlan materialPlan = prepared.materialPlan();
        Map<ResourceId, Long> requirements = materialPlan.legacyRequirementTotals();
        if (sourcePositions.stream().anyMatch(value -> prepared.siteCells().contains(pos(value)))) {
            return StartResult.failure("MATERIAL_SOURCE_OVERLAPS_ORDER_SITE");
        }
        String occupied = firstOccupiedCell(level, prepared.siteCells());
        if (occupied != null) return StartResult.failure("COMPOSITE_SITE_NOT_EMPTY:" + occupied);

        String baselineHash = baselineHash(level, prepared.siteCells());
        IndustrialPlayerOrderPlanV1 plan;
        try {
            plan = new IndustrialPlayerOrderPlanV1(
                    spec.orderType(), materialPlan.projectId(), spec.target(), spec.targetQuantity(),
                    materialPlan, Optional.empty(), Optional.of(spec.graph()), Optional.empty(),
                    Optional.empty(), spec.capabilities(), mode,
                    mode == ExecutionMode.DIRECT ? 0 : 3, runtime, materialPlan.planSha256());
        } catch (IllegalArgumentException failure) {
            return StartResult.failure("COMPOSITE_ORDER_PLAN_REFUSED:" + failure.getMessage());
        }
        var bound = IndustrialPlayerOrderService.bindPlan(player,
                new IndustrialPlayerOrderService.OrderIdentity(projectId, player.getUUID(),
                        id(level.dimension().location().toString()), pos(origin), baselineHash,
                        Instant.now().toEpochMilli()),
                plan, marker.worldIdentity());
        if (!bound.success()) return StartResult.failure(bound.code());

        var opened = PlayerMaterialService.openStandalonePlan(player, projectId, requirements,
                materialPlan.planSha256(), runtime);
        if (!opened.success()) return StartResult.failure(opened.statusCode());
        // Each source in turn; the selection call appends and ranks them by the order
        // they arrive, which makes the primary the one drawn from first.
        for (BlockPos source : sourcePositions) {
            var selected = PlayerMaterialService.selectStandaloneSource(player, projectId,
                    pos(source), Direction.UP);
            if (!selected.success()) return StartResult.failure(selected.statusCode());
        }
        var reserved = PlayerMaterialService.confirmStandalone(player, projectId,
                marker.worldIdentity());
        if (!reserved.success()) return StartResult.failure(reserved.statusCode());
        IndustrialPlayerOrderService.checkpoint(player, projectId,
                IndustrialLifecyclePhase.PLANNED, "MATERIALS_RESERVED",
                Set.of(id("steve_industrial:materials_reserved")));

        DistributionResult distribution = distribute(player, reserved.entry(), spec, layout, planned,
                mode == ExecutionMode.DIRECT ? MaterialExecutorKind.DIRECT : MaterialExecutorKind.BOT);
        if (!distribution.success()) {
            clearOwnedCells(level, layout.ownedCells());
            IndustrialPlayerOrderService.checkpoint(player, projectId,
                    IndustrialLifecyclePhase.PAUSED, distribution.code(),
                    Set.of(id("steve_industrial:materials_reserved")));
            return StartResult.failure(distribution.code());
        }

        StartedRun startedRun = startWrapper(level, spec, layout, planned, mode);
        if (!startedRun.success()) {
            // A refused start must not cost the player their material. Give back
            // everything the distribution moved before recording the refusal.
            UnwindResult unwound = unwind(level, layout, pos(sourcePosition));
            releaseLedger(level, distribution.entry(), unwound.complete()
                    ? "COMPOSITE_START_REFUSED_MATERIALS_RETURNED"
                    : "COMPOSITE_START_REFUSED_RETURN_PENDING");
            IndustrialPlayerOrderService.checkpoint(player, projectId,
                    IndustrialLifecyclePhase.PAUSED,
                    unwound.complete() ? "COMPOSITE_START_REFUSED_MATERIALS_RETURNED"
                            : "COMPOSITE_START_REFUSED_RETURN_PENDING",
                    Set.of(id(unwound.complete()
                            ? "steve_industrial:materials_returned"
                            : "steve_industrial:return_pending")));
            return StartResult.failure(startedRun.code()
                    + (unwound.complete() ? "" : ":STRANDED=" + unwound.strandedStacks()));
        }
        IndustrialPlayerOrderService.checkpoint(player, projectId, IndustrialLifecyclePhase.RUNNING,
                "COMPOSITE_RUNNING", Set.of(id("steve_industrial:materials_withdrawn")));
        ACTIVE.put(projectId, new Active(projectId, player.getUUID(), level.dimension(), spec,
                layout, startedRun.run(), distribution.entry(), mode, baselineHash,
                pos(sourcePosition), player, retainSite));
        return new StartResult(true, "COMPOSITE_ORDER_STARTED", projectId, spec.orderType());
    }

    /**
     * The exact material a Composite order would reserve at this site, without touching
     * the world.  Planning is what decides the real machine bill, so a preview has to
     * run the same planner the order does rather than restate a hand-written BOM.
     */
    public static PreviewResult preview(ServerLevel level, ResourceId orderType, BlockPos origin) {
        Prepared prepared = prepare(level, orderType, pos(origin),
                new UUID(0L, 0L));
        if (!prepared.success()) return new PreviewResult(false, prepared.code(), Map.of(), Map.of());
        return new PreviewResult(true, "OK",
                prepared.materialPlan().legacyRequirementTotals(),
                prepared.spec().intermediateSalvage());
    }

    /**
     * Shared planning step.  Both the preview and the real order derive the layout,
     * plan every stage against the live runtime and merge the verified stage snapshots
     * here, so a player can never be quoted one bill and charged another.
     */
    private static Prepared prepare(
            ServerLevel level, ResourceId orderType, BlockPos3i origin, UUID projectId) {
        return prepare(level, orderType, origin, projectId, 0);
    }

    /**
     * Prepares an order, planning only from {@code firstPlannedStage} onward.
     *
     * <p>A fresh order plans everything. A resumed one must not: machines are built when
     * their stage runs, not when the order is placed, so by the time a run is interrupted
     * the completed stages already stand in the world — and planning them again collides
     * with the blocks they are. That collision check is what stops planning from
     * overwriting a player's build, so the fix is to not ask about stages whose work is
     * done rather than to weaken it.</p>
     */
    private static Prepared prepare(
            ServerLevel level, ResourceId orderType, BlockPos3i origin, UUID projectId,
            int firstPlannedStage) {
        // A reviewed graph if one exists, otherwise one derived from live recipes. Both
        // go through the same layout, contract, ledger and wrapper from here on.
        CompositeOrderResolver.Resolution resolution =
                CompositeOrderResolver.resolve(level, orderType, 1);
        if (!resolution.success()) return Prepared.failure(resolution.code());
        CompositePlayerOrderSpecV1 spec = resolution.spec();
        // A derived chain chose one recipe per stage; the planner chooses independently
        // from the same registry and picks a different variant — raw_copper_block where
        // the expander assumed raw_copper — so the inputs it then requires are absent
        // from the bill. Forbidding the rejected variants' inputs makes the planner
        // reach the same recipe. Only derived orders are constrained: a reviewed graph
        // already plans correctly, and narrowing it could change verified behaviour.
        boolean constrainToChosenRecipe = resolution.derived();
        CompositeSiteLayout layout;
        try {
            layout = CompositeSiteLayout.forSpec(spec, origin);
        } catch (IllegalArgumentException failure) {
            return Prepared.failure("COMPOSITE_LAYOUT_UNSUPPORTED:" + failure.getMessage());
        }
        if (!layout.matchesDeclaredInfrastructure()) {
            return Prepared.failure("COMPOSITE_INFRASTRUCTURE_DIVERGED");
        }
        List<PlannedStage> planned = new ArrayList<>();
        List<CompositeSiteLayout.StageCells> layoutStages = layout.stages();
        if (firstPlannedStage < 0 || firstPlannedStage >= layoutStages.size()) {
            return Prepared.failure("COMPOSITE_RESUME_STAGE_OUT_OF_RANGE:" + firstPlannedStage);
        }
        for (CompositeSiteLayout.StageCells cells
                : layoutStages.subList(firstPlannedStage, layoutStages.size())) {
            CompositePlayerOrderSpecV1.StageSpec stage = spec.stage(cells.nodeId());
            CreateV606GoalDrivenPlanner.PlanningResult result = CreateV606GoalDrivenPlanner.plan(
                    level, stage.target(), Math.toIntExact(stage.targetQuantity()),
                    stage.processInputs(), cells.machineAnchor(), QuarterTurn.ZERO,
                    ResourceId.parse(stage.nodeId() + "_player"), stage.processInputs(),
                    ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                    constrainToChosenRecipe
                            ? rejectedVariantInputs(level, spec, stage)
                            : MaterialConstraints.none());
            if (!(result instanceof CreateV606GoalDrivenPlanner.Ready ready)) {
                // Carry the planner's own detail. The code alone says a stage was
                // refused, which is what left a survey reporting an aggregate and sent
                // two rounds of work at the wrong cause.
                CreateV606GoalDrivenPlanner.Failure planFailure =
                        (CreateV606GoalDrivenPlanner.Failure) result;
                return Prepared.failure("COMPOSITE_STAGE_PLAN_REFUSED:" + stage.nodeId()
                        + ":" + planFailure.code() + ":" + planFailure.detail());
            }
            planned.add(new PlannedStage(stage, cells, ready, installationMaterials(ready),
                    externalInputs(spec, stage)));
        }
        String runtime = planned.get(0).ready().runtime().toString();
        if (planned.stream().anyMatch(value -> !value.ready().runtime().toString().equals(runtime))) {
            return Prepared.failure("COMPOSITE_RUNTIME_IDENTITY_DIVERGED");
        }
        List<BlockPos3i> siteCells = new ArrayList<>(layout.ownedCells());
        planned.forEach(stage -> siteCells.addAll(componentCells(stage.ready())));
        if (siteCells.stream().distinct().count() != siteCells.size()) {
            return Prepared.failure("COMPOSITE_SITE_CELLS_OVERLAP");
        }
        VerifiedProjectMaterialPlan materialPlan;
        try {
            materialPlan = mergedMaterialPlan(projectId, spec, layout, planned, runtime);
        } catch (IllegalArgumentException failure) {
            return Prepared.failure("COMPOSITE_MATERIAL_PLAN_REFUSED:" + failure.getMessage());
        }
        // Names what is not an item. A reservation can only hold exact stacks, so this
        // refusal is right — but saying only that something was not an item cost two
        // seven-minute survey runs to work out which something, and the survey is the
        // only place that asks this question across the whole catalog.
        List<ResourceId> notItems = materialPlan.legacyRequirementTotals().keySet().stream()
                .filter(resource -> item(resource) == null).sorted(
                        java.util.Comparator.comparing(ResourceId::toString)).toList();
        if (!notItems.isEmpty()) {
            return Prepared.failure("COMPOSITE_EXACT_ITEM_IDENTITY_REQUIRED:" + notItems);
        }
        return new Prepared(true, "OK", spec, layout, planned, runtime, materialPlan,
                List.copyOf(siteCells));
    }

    public static void tick(MinecraftServer server) {
        for (Active active : List.copyOf(ACTIVE.values())) {
            // Prefer the currently online instance so a reconnect is picked up, and fall
            // back to the owner this order started with. An acceptance fixture's owner is
            // never in the player list, and without the fallback its session would simply
            // never be ticked and the order would sit in RUNNING until it timed out.
            ServerPlayer player = server.getPlayerList().getPlayer(active.ownerId());
            if (player == null) player = active.owner();
            if (player == null || !player.serverLevel().dimension().equals(active.dimension())) continue;
            CompositeRun.Tick result;
            try {
                result = active.run().tick();
            } catch (RuntimeException failure) {
                ACTIVE.remove(active.projectId());
                // Carry the wrapper's own message: a bare COMPOSITE_TICK_FAILED says
                // nothing about which physical precondition the run actually violated.
                pause(player, active, "COMPOSITE_TICK_FAILED:"
                        + failure.getClass().getSimpleName() + ":" + failure.getMessage());
                continue;
            }
            if (result instanceof CompositeRun.Progress) continue;
            ACTIVE.remove(active.projectId());
            if (result instanceof CompositeRun.Failed failed) {
                pause(player, active, failed.detail());
                continue;
            }
            complete(player, active, (CompositeRun.Completed) result);
        }
    }

    public static StatusResult status(ServerPlayer player) {
        Active active = ACTIVE.values().stream()
                .filter(value -> value.ownerId().equals(player.getUUID())).findFirst().orElse(null);
        if (active == null) return new StatusResult(false, "NO_ACTIVE_COMPOSITE_ORDER", null, null);
        return new StatusResult(true, "OK", active.projectId(), active.run().snapshot());
    }

    /**
     * Reads the last durable Composite settlement for this player.  Completion is
     * intentionally sourced from the persisted industrial order envelope rather than
     * from {@link #ACTIVE}: the wrapper is removed as soon as it settles, and a client
     * opening the status command one tick later must still see the report that was
     * actually committed.  Only Composite order types are projected on this channel;
     * another industrial adapter's report is not silently relabelled as Composite.
     */
    public static CompletionResult completion(ServerPlayer player) {
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(
                        player.serverLevel()).orders().values().stream()
                .filter(value -> value.ownerId().equals(player.getUUID()))
                .filter(value -> value.orderType().path().startsWith("composite/"))
                .filter(value -> value.report().isPresent())
                .max(Comparator.comparingLong(IndustrialPlayerOrderV1::updatedAt))
                .orElse(null);
        if (order == null) return CompletionResult.failure("NO_COMPOSITE_COMPLETION_REPORT");
        return new CompletionResult(true, "OK", order, order.report().orElseThrow());
    }

    public static StartResult cancel(ServerPlayer player) {
        Active active = ACTIVE.values().stream()
                .filter(value -> value.ownerId().equals(player.getUUID())).findFirst().orElse(null);
        if (active == null) return cancelPausedOrder(player);
        ACTIVE.remove(active.projectId());
        try {
            active.run().cancel(id("steve_industrial:player_cancelled"));
        } catch (RuntimeException ignored) {
            // Cancellation evidence is the wrapper's; a refusal still leaves the order paused.
        }
        UnwindResult unwound = unwind(player.serverLevel(), active.layout(), active.sourcePosition());
        releaseLedger(player.serverLevel(), active.entry(), unwound.complete()
                ? "COMPOSITE_CANCELLED_MATERIALS_RETURNED" : "COMPOSITE_CANCELLED_RETURN_PENDING");
        IndustrialPlayerOrderService.cancel(player, active.projectId(), unwound.complete());
        return new StartResult(true, unwound.complete()
                ? "COMPOSITE_ORDER_CANCELLED" : "COMPOSITE_ORDER_CANCELLED_RETURN_PENDING",
                active.projectId(), active.spec().orderType());
    }

    /**
     * Cancels an order this process never started.
     *
     * <p>A restart empties {@link #ACTIVE}, so a paused order had no owner in memory and
     * cancellation answered NO_ACTIVE_COMPOSITE_ORDER — leaving the player with a site
     * they could not clear and material they could not recover, which is the worst
     * outcome the order can reach and the one a restart guarantees.</p>
     *
     * <p>Nothing about the run is replayed. The layout is a pure function of the
     * reviewed spec and the site origin the envelope already records, and the player's
     * own chest is on the material entry, so the site can be given back without knowing
     * how far the run got.</p>
     */
    private static StartResult cancelPausedOrder(ServerPlayer player) {
        return cancelPausedOrder(player, null);
    }

    /**
     * Resumes an order this process never started, from wherever the site says it got to.
     *
     * <p>B2 gave a restarted player a way out: cancel and take the material back. It is
     * still a loss — whatever the run consumed is spent and the time is gone. This is the
     * other half, and it deliberately reuses B2's discovery: the spec and the layout are
     * pure functions of what the envelope already records, so neither has to be
     * remembered. Only the stage cursor was ever in memory, and that is read back off the
     * chests instead of restored from a file, because a cursor from a file can disagree
     * with the world while chests cannot disagree with themselves.
     *
     * <p>The execution mode is the caller's, not the order's. It is not recorded, and it
     * is a preference about how work gets done rather than a property of what is being
     * built — the output is the same either way.
     *
     * <p>No material is reserved and no site is built. Both already happened, and the
     * ledger still carries the original entry; charging again would be the exact double
     * spend the resume decision refuses ambiguous sites to avoid.</p>
     */
    public static StartResult resumePausedOrder(ServerPlayer player, ExecutionMode mode) {
        return resumePausedOrder(player, null, mode);
    }

    /** Resumes a specific order; the gate needs this because its owner is a FakePlayer. */
    public static StartResult resumeAsOwner(
            ServerPlayer player, UUID projectId, ExecutionMode mode) {
        return resumePausedOrder(player, Objects.requireNonNull(projectId, "projectId"), mode);
    }

    private static StartResult resumePausedOrder(
            ServerPlayer player, UUID projectId, ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        ServerLevel level = player.serverLevel();
        if (projectId != null && ACTIVE.containsKey(projectId)) {
            return StartResult.failure("COMPOSITE_ORDER_ALREADY_RUNNING");
        }
        IndustrialPlayerOrderV1 order = resumableOrder(level, player, projectId);
        if (order == null) return StartResult.failure("NO_ACTIVE_COMPOSITE_ORDER");

        Entry entry = PlayerMaterialSavedData.forLevel(level).entry(order.orderId()).orElse(null);
        if (entry == null || entry.sources().isEmpty()) {
            return StartResult.failure("COMPOSITE_MATERIAL_ENTRY_MISSING");
        }

        // Read the site before planning anything. Where to resume decides what to plan,
        // and planning a completed stage collides with the machines that stage built.
        Optional<CompositeResumePlanV1> read = resumePlan(level, order.orderId());
        if (read.isEmpty()) return StartResult.failure("COMPOSITE_RESUME_SITE_UNREADABLE");
        CompositeResumePlanV1 resume = read.get();
        if (resume.decision() == CompositeResumePlanV1.Decision.REFUSE) {
            // Refusing leaves the order exactly as it was found, still cancellable. The
            // player loses nothing they had not already lost, which is the point.
            return StartResult.failure(resume.reason());
        }
        if (resume.decision() == CompositeResumePlanV1.Decision.SETTLE_ONLY) {
            // The output is already made. Producing again would run the last stage twice,
            // so the only honest work left is to hand it over and clear the site.
            return StartResult.failure("COMPOSITE_RESUME_SETTLEMENT_PENDING");
        }

        Prepared prepared = prepare(level, order.orderType(), order.anchor(),
                order.orderId(), resume.stageIndex());
        if (!prepared.success()) return StartResult.failure(prepared.code());
        CompositePlayerOrderSpecV1 spec = prepared.spec();
        CompositeSiteLayout layout = prepared.layout();

        StartedRun startedRun = startWrapper(level, spec, layout, prepared.stages(), mode);
        if (!startedRun.success()) {
            // Nothing was moved, so nothing is unwound. A refused resume must leave the
            // site untouched or the next attempt reads a different world than this one.
            return StartResult.failure(startedRun.code());
        }
        IndustrialPlayerOrderService.checkpoint(player, order.orderId(),
                IndustrialLifecyclePhase.RUNNING, "COMPOSITE_RESUMED:" + resume.stageIndex(),
                Set.of(id("steve_industrial:materials_withdrawn")));
        // A resumed order clears its site, as the original would have. Resumption
        // restores a run, not a preference the envelope never recorded.
        ACTIVE.put(order.orderId(), new Active(order.orderId(), order.ownerId(),
                level.dimension(), spec, layout, startedRun.run(), entry, mode,
                order.baselineHash(), entry.sources().get(0).position(), player, false));
        LOGGER.info("COMPOSITE_ORDER_RESUMED project={} target={} fromStage={} mode={}",
                order.orderId(), spec.target(), resume.stageIndex(), mode);
        return new StartResult(true, "COMPOSITE_ORDER_RESUMED", order.orderId(),
                spec.orderType());
    }

    /**
     * The resume decision for an order, without acting on it.
     *
     * <p>The acceptance gate needs this to stop its write phase at a point that is
     * actually resumable instead of at an arbitrary tick count. A fixed tick budget can
     * land mid-recipe, where the input is consumed and the output does not exist yet, and
     * a gate that fails on timing rather than on behaviour teaches nothing.</p>
     */
    public static Optional<CompositeResumePlanV1> resumePlan(ServerLevel level, UUID projectId) {
        IndustrialPlayerOrderV1 order = resumableOrder(level, null,
                Objects.requireNonNull(projectId, "projectId"));
        if (order == null) return Optional.empty();
        // Resolve and lay out, but do not plan. Planning is what the executor needs, not
        // what reading chests needs, and prepare() does both — calling it here put a full
        // re-plan of every stage inside a per-tick poll and hung the server past its
        // watchdog. Cancellation takes this same shorter path for the same reason.
        CompositeOrderResolver.Resolution resolution =
                CompositeOrderResolver.resolve(level, order.orderType(), 1);
        if (!resolution.success()) return Optional.empty();
        CompositePlayerOrderSpecV1 spec = resolution.spec();
        CompositeSiteLayout layout;
        try {
            layout = CompositeSiteLayout.forSpec(spec, order.anchor());
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
        return Optional.of(CompositeResumePlanV1.from(spec, observe(layout, level, spec)));
    }

    /**
     * A paused or running order with no report. Either the one named, or this player's;
     * {@code player} may be null exactly when a projectId names the order outright.
     */
    private static IndustrialPlayerOrderV1 resumableOrder(
            ServerLevel level, ServerPlayer player, UUID projectId) {
        return IndustrialPlayerOrderSavedData.forLevel(level).orders().values().stream()
                .filter(value -> projectId != null
                        ? value.orderId().equals(projectId)
                        : value.ownerId().equals(player.getUUID()))
                .filter(value -> value.report().isEmpty())
                .filter(value -> value.phase() == IndustrialLifecyclePhase.PAUSED
                        || value.phase() == IndustrialLifecyclePhase.RUNNING)
                .findFirst().orElse(null);
    }

    /** Cancels a specific paused order; the gate needs this because its owner is a FakePlayer. */
    public static StartResult cancelAsOwner(ServerPlayer player, UUID projectId) {
        return cancelPausedOrder(player, Objects.requireNonNull(projectId, "projectId"));
    }

    private static StartResult cancelPausedOrder(ServerPlayer player, UUID projectId) {
        ServerLevel level = player.serverLevel();
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(level)
                .orders().values().stream()
                .filter(value -> projectId != null
                        ? value.orderId().equals(projectId)
                        : value.ownerId().equals(player.getUUID()))
                .filter(value -> value.report().isEmpty())
                .filter(value -> value.phase() == IndustrialLifecyclePhase.PAUSED
                        || value.phase() == IndustrialLifecyclePhase.RUNNING)
                .findFirst().orElse(null);
        if (order == null) return StartResult.failure("NO_ACTIVE_COMPOSITE_ORDER");
        // Resolve the same way the order was placed. Looking only in the reviewed
        // catalog would leave a derived order impossible to cancel, which is exactly
        // the "material the player cannot recover" failure this path exists to prevent.
        CompositeOrderResolver.Resolution resolution =
                CompositeOrderResolver.resolve(level, order.orderType(), 1);
        if (!resolution.success()) return StartResult.failure(resolution.code());
        CompositePlayerOrderSpecV1 spec = resolution.spec();
        Entry entry = PlayerMaterialSavedData.forLevel(level).entry(order.orderId()).orElse(null);
        if (entry == null || entry.sources().isEmpty()) {
            return StartResult.failure("COMPOSITE_MATERIAL_ENTRY_MISSING");
        }
        CompositeSiteLayout layout;
        try {
            layout = CompositeSiteLayout.forSpec(spec, order.anchor());
        } catch (IllegalArgumentException failure) {
            return StartResult.failure("COMPOSITE_LAYOUT_UNRECOVERABLE");
        }
        UnwindResult unwound = unwind(level, layout, entry.sources().get(0).position());
        releaseLedger(level, entry, unwound.complete()
                ? "COMPOSITE_CANCELLED_MATERIALS_RETURNED" : "COMPOSITE_CANCELLED_RETURN_PENDING");
        IndustrialPlayerOrderService.cancel(player, order.orderId(), unwound.complete());
        return new StartResult(true, unwound.complete()
                ? "COMPOSITE_ORDER_CANCELLED" : "COMPOSITE_ORDER_CANCELLED_RETURN_PENDING",
                order.orderId(), spec.orderType());
    }

    public static void clearServerState() { ACTIVE.clear(); }

    /**
     * Whether this process is still driving a wrapper for the project.  A reload gate
     * needs this to prove a restarted server did not silently resume execution.
     */
    public static boolean isActive(UUID projectId) {
        return ACTIVE.containsKey(Objects.requireNonNull(projectId, "projectId"));
    }

    /**
     * Settles the finished run.  Every reserved item is now either an installed
     * machine component, a consumed process input, an installed route block or a
     * physically produced output, so the ledger closes at CONSUMED and the report
     * carries the wrapper's own stage/route evidence rather than a success flag.
     */
    private static void complete(
            ServerPlayer player, Active active, CompositeRun.Completed completed) {
        ServerLevel level = player.serverLevel();
        // The wrapper's cleanup retires bots and releases routes either way; only the
        // built infrastructure is kept. Skipping bot retirement to keep a site standing
        // would leave a fleet running with nothing to do.
        boolean cleaned;
        try {
            cleaned = active.run().cleanup().complete();
        } catch (RuntimeException failure) {
            cleaned = false;
        }
        Map<ResourceId, Long> salvage = observedSalvage(level, active);
        Map<ResourceId, Long> output = Map.of(active.spec().target(),
                count(level, finalDelivery(active), active.spec().target()));
        Entry entry = active.entry();
        List<Transaction> consumed = entry.transactions().stream()
                .map(transaction -> new Transaction(transaction.transactionId(),
                        transaction.reservationId(), transaction.taskId(), transaction.executor(),
                        MaterialTransactionState.CONSUMED, transaction.quantity(),
                        level.getGameTime()))
                .toList();
        long planned = entry.requirements().values().stream().mapToLong(Long::longValue).sum();
        long withdrawn = consumed.stream().mapToLong(Transaction::quantity).sum();
        // One shared definition of "complete", tested in core against both graphs.
        CompositeSettlementV1 settlement = CompositeSettlementV1.of(active.spec(), planned,
                withdrawn, output, salvage, completed.routesProved(), cleaned,
                active.retainSite());
        Entry settled = entry.withTransactions(new ArrayList<>(consumed),
                Instant.now().toEpochMilli(),
                settlement.ledgerBalanced() ? "COMPOSITE_MATERIAL_LEDGER_BALANCED"
                        : "COMPOSITE_MATERIAL_LEDGER_RECONCILIATION_REQUIRED", null);
        PlayerMaterialSavedData.forLevel(level).put(settled);

        if (!settlement.complete()) {
            IndustrialPlayerOrderService.checkpoint(player, active.projectId(),
                    IndustrialLifecyclePhase.PAUSED, settlement.pauseReason(),
                    Set.of(id("steve_industrial:output_observed")));
            return;
        }
        // The output and the salvage are the player's, and they are sitting in chests
        // this order owns and is about to remove. Hand them back before cleanup rather
        // than deleting the very material the run was started to produce.
        if (!returnProduced(level, active)) {
            IndustrialPlayerOrderService.checkpoint(player, active.projectId(),
                    IndustrialLifecyclePhase.PAUSED, "COMPOSITE_OUTPUT_RETURN_PENDING",
                    Set.of(id("steve_industrial:output_observed")));
            return;
        }
        // An unattended site that produced past its promise settles, but the fact does
        // not get to vanish into a success: it is the difference between a site that
        // stopped when asked and one that kept running.
        if (settlement.outputSurplus() > 0) {
            LOGGER.info("COMPOSITE_OUTPUT_SURPLUS project={} target={} promised={} delivered={}"
                            + " surplus={}",
                    active.projectId(), active.spec().target(), settlement.promisedOutput(),
                    settlement.observedOutput(), settlement.outputSurplus());
        }
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                entry.requirements(), entry.requirements(), entry.requirements(), Map.of(),
                Map.of(), Map.of(), output, salvage.values().stream().mapToLong(Long::longValue).sum(),
                0, 0, 0, 0, 0, 0, true, active.baselineHash());
        IndustrialPlayerOrderService.completeWithReport(player, active.projectId(), report);
        // The durable report is now committed.  Send it before the in-memory wrapper is
        // retired so a real client can render the immutable settlement instead of
        // racing the next status refresh into NO_ACTIVE_COMPOSITE_ORDER.
        dev.stevecreate.agent.forge1201.player.net.CompositeOrderNetwork.sendCompletion(
                player, dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets
                        .CompositeCompletionS2C.from(player, "COMPLETED"));
        if (active.retainSite()) {
            LOGGER.info("COMPOSITE_SITE_RETAINED project={} target={} cells={}",
                    active.projectId(), active.spec().target(),
                    active.layout().ownedCells().size());
            return;
        }
        clearOwnedCells(level, active.layout().ownedCells());
    }

    /**
     * Moves the finished output and every settled salvage stack back into the chest the
     * player selected.  A partial move would leave produced material in a chest cleanup
     * is about to delete, so anything that does not fit rolls the whole handback back
     * and pauses the order with its infrastructure still standing.
     */
    private static boolean returnProduced(ServerLevel level, Active active) {
        if (!(level.getBlockEntity(block(active.sourcePosition())) instanceof Container source)) {
            return false;
        }
        List<BlockPos3i> produced = new ArrayList<>();
        produced.add(finalDelivery(active));
        active.layout().routes().forEach(route -> route.overflow().ifPresent(produced::add));
        ArrayList<ItemStack> moved = new ArrayList<>();
        for (BlockPos3i position : produced) {
            if (!(level.getBlockEntity(block(position)) instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                moved.add(stack.copy());
                container.setItem(slot, ItemStack.EMPTY);
            }
            container.setChanged();
        }
        ArrayList<ItemStack> placed = new ArrayList<>();
        for (ItemStack stack : moved) {
            int slot = firstEmptySlot(source);
            if (slot < 0) {
                placed.forEach(value -> removeExactStack(source, value));
                restoreProduced(level, produced, moved);
                return false;
            }
            source.setItem(slot, stack);
            placed.add(stack);
        }
        source.setChanged();
        return true;
    }

    private static void restoreProduced(
            ServerLevel level, List<BlockPos3i> produced, List<ItemStack> stacks) {
        int index = 0;
        for (BlockPos3i position : produced) {
            if (!(level.getBlockEntity(block(position)) instanceof Container container)) continue;
            while (index < stacks.size()) {
                int slot = firstEmptySlot(container);
                if (slot < 0) break;
                container.setItem(slot, stacks.get(index++));
            }
            container.setChanged();
        }
    }

    private static void removeExactStack(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot) == stack) {
                container.setItem(slot, ItemStack.EMPTY);
                return;
            }
        }
    }

    private static int firstEmptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static void pause(ServerPlayer player, Active active, String code) {
        try {
            active.run().cleanup();
        } catch (RuntimeException ignored) {
            // Leave owned infrastructure in place for inspection rather than guessing.
        }
        // The envelope bounds its stage string; a wrapper detail must not turn a pause
        // into a second failure that loses the reason entirely.
        IndustrialPlayerOrderService.checkpoint(player, active.projectId(),
                IndustrialLifecyclePhase.PAUSED,
                code.length() > 256 ? code.substring(0, 256) : code,
                Set.of(id("steve_industrial:materials_withdrawn")));
    }

    /**
     * Physically moves every reserved item out of the player's chest: infrastructure
     * becomes an installed block, everything else lands in the stage source chest that
     * needs it.  A single failure rolls the whole distribution back to RELEASED, so a
     * half-built composite can never start.
     */
    private record Prepared(
            boolean success,
            String code,
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            List<PlannedStage> stages,
            String runtime,
            VerifiedProjectMaterialPlan materialPlan,
            List<BlockPos3i> siteCells) {
        static Prepared failure(String code) {
            return new Prepared(false, code, null, null, List.of(), null, null, List.of());
        }
    }

    private static DistributionResult distribute(
            ServerPlayer player,
            Entry reserved,
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            List<PlannedStage> planned,
            MaterialExecutorKind executor) {
        ServerLevel level = player.serverLevel();
        Entry entry = PlayerConstructionService.prepareWithdrawal(level, reserved, executor);
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Map<UUID, Source> sources = new LinkedHashMap<>();
        reserved.sources().forEach(value -> sources.put(value.sourceId(), value));
        Map<BlockPos3i, Map<ResourceId, Long>> demand = stageDemand(layout, spec, planned);
        ArrayList<BlockPos3i> infrastructureCells = new ArrayList<>(layout.ownedCells());
        ArrayList<Removed> removed = new ArrayList<>();
        List<Reservation> ordered = reserved.reservations().stream()
                .sorted(Comparator.comparing((Reservation value) ->
                                INFRASTRUCTURE.contains(value.identity().itemId()) ? 0 : 1)
                        .thenComparing(value -> value.identity().itemId().toString()))
                .toList();
        try {
            for (Reservation reservation : ordered) {
                Source source = sources.get(reservation.sourceId());
                if (source == null) throw new IllegalStateException("reservation source missing");
                ItemStack extracted = extract(level, source, reservation);
                removed.add(new Removed(source, reservation, extracted.copy()));
                entry = PlayerConstructionService.advanceTransaction(entry,
                        reservation.reservationId(), MaterialTransactionState.WITHDRAWN,
                        level.getGameTime(), "COMPOSITE_MATERIAL_WITHDRAWN");
                data.put(entry);
                // Infrastructure first, then whatever a stage still needs.  Splitting one
                // stack across both is deliberate: a machine component that happens to be
                // a chest or hopper must not starve either the route or the stage.
                int installed = install(level, layout, infrastructureCells,
                        reservation.identity().itemId(), extracted.getCount());
                if (installed < extracted.getCount()) {
                    ItemStack remainder = extracted.copy();
                    remainder.setCount(extracted.getCount() - installed);
                    deliver(level, demand, reservation.identity().itemId(), remainder);
                }
                entry = PlayerConstructionService.advanceTransaction(entry,
                        reservation.reservationId(), MaterialTransactionState.DELIVERED,
                        level.getGameTime(), "COMPOSITE_MATERIAL_DELIVERED");
                data.put(entry);
                level.getServer().overworld().getDataStorage().save();
            }
            if (!infrastructureCells.isEmpty()) {
                throw new IllegalStateException("composite infrastructure cell unfilled");
            }
            if (demand.values().stream().anyMatch(value -> !value.isEmpty())) {
                throw new IllegalStateException("composite stage demand unfilled");
            }
        } catch (RuntimeException failure) {
            // Carrying the cause. A rollback that says only that it rolled back costs a
            // whole physical run to diagnose, and the three ways this can fail — an
            // unfilled infrastructure cell, unfilled stage demand, a source that changed
            // underneath — need completely different fixes.
            String cause = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            rollback(level, layout, removed);
            ArrayList<Transaction> released = new ArrayList<>();
            for (Transaction transaction : entry.transactions()) {
                released.add(new Transaction(transaction.transactionId(),
                        transaction.reservationId(), transaction.taskId(), transaction.executor(),
                        MaterialTransactionState.RELEASED, transaction.quantity(),
                        level.getGameTime()));
            }
            data.put(entry.withTransactions(released, Instant.now().toEpochMilli(),
                    "COMPOSITE_DISTRIBUTION_ROLLED_BACK", null));
            level.getServer().overworld().getDataStorage().save();
            return new DistributionResult(false,
                    "COMPOSITE_DISTRIBUTION_ROLLED_BACK:" + cause, null);
        }
        try {
            orderSplitSource(level, spec, layout);
        } catch (RuntimeException failure) {
            rollback(level, layout, removed);
            return new DistributionResult(false, "COMPOSITE_RAW_SOURCE_ORDER_FAILED", null);
        }
        return new DistributionResult(true, "OK", entry);
    }

    private static ItemStack extract(ServerLevel level, Source source, Reservation reservation) {
        if (!(level.getBlockEntity(block(source.position())) instanceof Container container)) {
            throw new IllegalStateException("material source disappeared");
        }
        // By identity rather than by slot. The reserved slot is still tried first, so a
        // chest nobody touched behaves exactly as before; a chest the player tidied
        // between reserving and building still holds the same material and the order
        // survives it.
        return PlayerMaterialService.withdrawReserved(container, reservation);
    }

    /**
     * The direction the layout says this hopper must push.  Both wrappers validate
     * facing against the neighbour they expect to receive the intermediate, and a
     * branch/merge site needs three different facings, so this can never be a constant.
     */
    private static Direction hopperFacing(CompositeSiteLayout layout, BlockPos3i hopper) {
        CompositeSiteLayout.RouteCells route = layout.routes().stream()
                .filter(value -> value.hopper().equals(hopper)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no composite route owns hopper " + hopper));
        BlockPos from = block(hopper);
        BlockPos to = block(route.pushesInto());
        for (Direction direction : Direction.values()) {
            if (from.relative(direction).equals(to)) return direction;
        }
        throw new IllegalStateException("composite hopper " + hopper + " is not adjacent to its target");
    }

    /** Installs as much of one withdrawn stack as the site still needs; returns that count. */
    private static int install(
            ServerLevel level,
            CompositeSiteLayout layout,
            List<BlockPos3i> remaining,
            ResourceId resource,
            int quantity) {
        int placed = 0;
        for (; placed < quantity; placed++) {
            BlockPos3i cell = remaining.stream()
                    .filter(value -> ownedBlock(layout, value).equals(resource))
                    .findFirst().orElse(null);
            if (cell == null) return placed;
            remaining.remove(cell);
            BlockPos position = block(cell);
            BlockState state = resource.equals(CHEST) ? Blocks.CHEST.defaultBlockState()
                    : resource.equals(HOPPER) ? Blocks.HOPPER.defaultBlockState()
                            .setValue(HopperBlock.FACING, hopperFacing(layout, cell))
                    : Blocks.REDSTONE_BLOCK.defaultBlockState();
            if (!level.setBlockAndUpdate(position, state)) {
                throw new IllegalStateException("composite infrastructure placement refused");
            }
            if (resource.equals(CHEST)
                    && !(level.getBlockEntity(position) instanceof ChestBlockEntity)) {
                throw new IllegalStateException("composite boundary chest was not created");
            }
        }
        return placed;
    }

    private static void deliver(
            ServerLevel level,
            Map<BlockPos3i, Map<ResourceId, Long>> demand,
            ResourceId resource,
            ItemStack extracted) {
        int remaining = extracted.getCount();
        for (Map.Entry<BlockPos3i, Map<ResourceId, Long>> destination : demand.entrySet()) {
            long wanted = destination.getValue().getOrDefault(resource, 0L);
            if (wanted <= 0 || remaining <= 0) continue;
            int moved = (int) Math.min(remaining, wanted);
            if (!(level.getBlockEntity(block(destination.getKey())) instanceof Container container)) {
                throw new IllegalStateException("composite stage chest missing");
            }
            ItemStack portion = extracted.copy();
            portion.setCount(moved);
            insert(container, portion);
            remaining -= moved;
            long left = wanted - moved;
            if (left == 0) destination.getValue().remove(resource);
            else destination.getValue().put(resource, left);
        }
        if (remaining != 0) throw new IllegalStateException("composite delivery has no destination");
    }

    private static void insert(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                container.setItem(slot, stack);
                container.setChanged();
                return;
            }
        }
        throw new IllegalStateException("composite stage chest is full");
    }

    /**
     * Gives a refused order's material back after distribution has already happened.
     *
     * <p>{@link #rollback} can only undo a distribution it watched, so it is useless
     * once distribution has succeeded and the wrapper then refuses to start. That was
     * the worst outcome the order had: every reserved item was out of the player's
     * chest and built into a site, the order paused, and a paused order cannot resume —
     * so a refused start silently cost the player everything they had reserved.</p>
     *
     * <p>Anything that does not fit back is deliberately left standing rather than
     * dropped or deleted, and reported, so the failure is visible instead of lossy.</p>
     */
    private static UnwindResult unwind(
            ServerLevel level, CompositeSiteLayout layout, BlockPos3i playerChest) {
        List<ItemStack> recovered = new ArrayList<>();
        for (BlockPos3i cell : layout.ownedCells()) {
            BlockPos position = block(cell);
            if (level.getBlockEntity(position) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) recovered.add(stack.copy());
                }
            }
            // The installed block is reserved material too, so it comes back as its item.
            if (!level.getBlockState(position).isAir()) {
                Item item = item(layout.ownedBlock(cell));
                if (item != null) recovered.add(new ItemStack(item, 1));
            }
        }
        if (!(level.getBlockEntity(block(playerChest)) instanceof Container destination)) {
            return new UnwindResult(false, recovered.size());
        }
        int returned = 0;
        for (ItemStack stack : recovered) {
            int slot = firstEmptySlot(destination);
            if (slot < 0) break;
            destination.setItem(slot, stack);
            returned++;
        }
        destination.setChanged();
        if (returned < recovered.size()) {
            // Leave the site intact: the remainder is still physically there to collect.
            return new UnwindResult(false, recovered.size() - returned);
        }
        clearOwnedCells(level, layout.ownedCells());
        return new UnwindResult(true, 0);
    }

    private record UnwindResult(boolean complete, int strandedStacks) {}

    private static void rollback(
            ServerLevel level, CompositeSiteLayout layout, List<Removed> removed) {
        clearOwnedCells(level, layout.ownedCells());
        for (Removed row : removed) {
            if (!(level.getBlockEntity(block(row.source().position())) instanceof Container container)) {
                continue;
            }
            ItemStack current = container.getItem(row.reservation().slot());
            if (current.isEmpty()) container.setItem(row.reservation().slot(), row.stack());
            else if (identity(current).equals(row.reservation().identity())) {
                current.grow(row.stack().getCount());
            }
            container.setChanged();
        }
    }

    /**
     * Inputs belonging to recipe variants this chain did not choose.
     *
     * <p>Several recipes can make the same product from different materials. The
     * expander picks one and writes its inputs into the bill; the planner, given only a
     * target, may pick another and then require inputs nobody reserved. Forbidding the
     * unchosen variants' inputs — and only those — leaves the planner exactly the recipe
     * the bill was written for.</p>
     */
    private static MaterialConstraints rejectedVariantInputs(
            ServerLevel level,
            CompositePlayerOrderSpecV1 spec,
            CompositePlayerOrderSpecV1.StageSpec stage) {
        // Per stage, never aggregated. One stage's rejected variants are frequently
        // another stage's legitimate inputs, and forbidding the union starves every
        // stage at once — which surfaces as RECIPE_NOT_FOUND and looks like a far
        // deeper problem than it is.
        Set<ResourceId> chosen = new LinkedHashSet<>();
        Set<ResourceId> rejected = new LinkedHashSet<>();
        spec.stages().forEach(value -> chosen.addAll(value.processInputs().keySet()));
        spec.stages().forEach(value -> chosen.add(value.target()));
        LiveRecipeCatalog.admittedRecipes(level).stream()
                .filter(recipe -> recipe.target().equals(stage.target()))
                .filter(recipe -> !recipe.inputsPerBatch().keySet()
                        .equals(stage.processInputs().keySet()))
                .forEach(recipe -> rejected.addAll(recipe.inputsPerBatch().keySet()));
        rejected.removeAll(chosen);
        if (rejected.size() > MaterialConstraints.MAX_CONSTRAINTS) {
            // Beyond the bounded count the restriction cannot be expressed at all;
            // planning then refuses on its own terms rather than silently unconstrained.
            return MaterialConstraints.none();
        }
        return new MaterialConstraints(rejected, Map.of());
    }

    /** Marks every withdrawal released so a returned order does not read as consumed. */
    private static void releaseLedger(ServerLevel level, Entry entry, String reason) {
        List<Transaction> released = entry.transactions().stream()
                .map(transaction -> new Transaction(transaction.transactionId(),
                        transaction.reservationId(), transaction.taskId(), transaction.executor(),
                        MaterialTransactionState.RELEASED, transaction.quantity(),
                        level.getGameTime()))
                .toList();
        PlayerMaterialSavedData.forLevel(level).put(entry.withTransactions(
                new ArrayList<>(released), Instant.now().toEpochMilli(), reason, null));
        level.getServer().overworld().getDataStorage().save();
    }

    private static void clearOwnedCells(ServerLevel level, List<BlockPos3i> cells) {
        cells.forEach(cell -> {
            BlockPos position = block(cell);
            if (level.getBlockEntity(position) instanceof Container container) {
                container.clearContent();
            }
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        });
    }

    private static ResourceId ownedBlock(CompositeSiteLayout layout, BlockPos3i cell) {
        for (CompositeSiteLayout.RouteCells route : layout.routes()) {
            if (route.hopper().equals(cell)) return HOPPER;
            if (route.lock().equals(cell)) return REDSTONE_BLOCK;
        }
        return CHEST;
    }

    /** Destination chests, decided by {@link CompositeMaterialDistribution}. */
    private static Map<BlockPos3i, Map<ResourceId, Long>> stageDemand(
            CompositeSiteLayout layout, CompositePlayerOrderSpecV1 spec, List<PlannedStage> planned) {
        Map<ResourceId, Map<ResourceId, Long>> installation = new LinkedHashMap<>();
        planned.forEach(stage ->
                installation.put(stage.spec().nodeId(), stage.installationMaterials()));
        return CompositeMaterialDistribution.demand(spec, layout, installation);
    }

    private static VerifiedProjectMaterialPlan mergedMaterialPlan(
            UUID projectId,
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            List<PlannedStage> planned,
            String runtime) {
        List<VerifiedPlanMaterialSnapshot> snapshots = planned.stream()
                .map(stage -> VerifiedPlanMaterialSnapshot.from(
                        stage.ready().executionReadyPlan().physicalPlan()))
                .toList();
        VerifiedPlanMaterialSnapshot merged = VerifiedPlanMaterialSnapshot.merge(
                ResourceId.parse("layout:verified_composite_"
                        + spec.graph().fingerprint().substring(0, 24)),
                snapshots, layout.ownedCells());
        return new VerifiedProjectMaterialPlanFactory().create(
                new VerifiedProjectMaterialPlanFactory.Request(
                        ResourceId.parse("player_project:" + projectId.toString().replace("-", "")),
                        spec.target(), spec.targetQuantity(), runtime, merged,
                        spec.externalProcessInputs(), spec.infrastructureMaterials(),
                        Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));
    }

    /**
     * Starts whichever reviewed wrapper this graph shape belongs to.  Neither wrapper is
     * generalised for the other: the linear one takes an ordered stage/route list, the
     * branch/merge one takes a named split, two branches and a merge.
     */
    /**
     * Starts the executor on exactly the stages that were planned.
     *
     * <p>A fresh order plans all of them; a resumed one plans only what is left, and the
     * executor gets a graph describing exactly those. Resuming is therefore not a mode
     * the executor knows about — it cannot tell this from a shorter order, and every rule
     * it enforces on a chain it enforces here. Branch/merge has no suffix that is still a
     * branch/merge, so it starts only from the beginning.</p>
     */
    private static StartedRun startWrapper(
            ServerLevel level,
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            List<PlannedStage> planned,
            ExecutionMode mode) {
        CreateV606ThreeModeExecution.TestRegion region =
                new CreateV606ThreeModeExecution.TestRegion(
                        layout.regionMinimum(), layout.regionMaximum());
        boolean whole = planned.size() == spec.stages().size();
        if (spec.graph().shape() == CompositeProductionGraph.Shape.LINEAR_CHAIN) {
            CompositeProductionGraph graph;
            try {
                graph = whole ? spec.graph() : spec.graph().retaining(
                        planned.stream().map(stage -> stage.spec().nodeId()).toList());
            } catch (IllegalArgumentException failure) {
                // A single remaining stage is not a chain, and the graph says so. That is
                // one machine's work and belongs on the single-machine path, not here.
                return new StartedRun(false,
                        "COMPOSITE_RESUME_REMAINDER_NOT_A_CHAIN:" + failure.getMessage(), null);
            }
            CreateV606CompositeExecution.StartResult start = CreateV606CompositeExecution.start(
                    level, graph, wrapperStages(planned), wrapperRoutes(graph, layout),
                    mode, region);
            if (start instanceof CreateV606CompositeExecution.Started started) {
                return new StartedRun(true, "OK", new CompositeRun.Linear(started.session()));
            }
            return new StartedRun(false, "COMPOSITE_START_REFUSED:"
                    + ((CreateV606CompositeExecution.Rejected) start).detail(), null);
        }
        if (!whole) {
            // No suffix of a branch/merge is a branch/merge, so there is no shorter graph
            // to hand the executor. Refusing leaves the player with cancel-and-refund,
            // which is a worse outcome than resuming and a much better one than a run
            // whose evidence does not describe its shape.
            return new StartedRun(false, "COMPOSITE_RESUME_SHAPE_UNSUPPORTED", null);
        }
        CompositeSiteLayout.SplitCells splitCells = layout.split().orElseThrow();
        CompositePlayerOrderSpecV1.RootSplitSpec splitSpec = spec.rootSplit().orElseThrow();
        List<PlannedStage> branches = planned.stream()
                .filter(stage -> !spec.graph().outgoing(stage.spec().nodeId()).isEmpty())
                .toList();
        PlannedStage mergeStage = planned.stream()
                .filter(stage -> spec.graph().outgoing(stage.spec().nodeId()).isEmpty())
                .findFirst().orElseThrow();
        PlannedStage first = branches.stream()
                .filter(stage -> stage.cells().source().equals(splitCells.firstBranchSource()))
                .findFirst().orElseThrow();
        PlannedStage second = branches.stream()
                .filter(stage -> stage.cells().source().equals(splitCells.secondBranchSource()))
                .findFirst().orElseThrow();
        CreateV606BranchMergeExecution.RootSplit split = rootSplit(spec, splitSpec, splitCells);
        CreateV606BranchMergeExecution.StartResult start = CreateV606BranchMergeExecution.start(
                level, spec.graph(), split, branchStage(first), branchStage(second),
                branchStage(mergeStage), mergeRoutes(spec, layout, first, second), mode, region);
        if (start instanceof CreateV606BranchMergeExecution.Started started) {
            return new StartedRun(true, "OK", new CompositeRun.BranchMerge(started.session()));
        }
        return new StartedRun(false, "COMPOSITE_START_REFUSED:"
                + ((CreateV606BranchMergeExecution.Rejected) start).detail(), null);
    }

    /** Delegates to {@link CompositeMaterialDistribution}, which is unit tested. */
    private static List<CompositeProductionGraph.MaterialEdge> splitDrawOrder(
            CompositePlayerOrderSpecV1 spec, CompositeSiteLayout.SplitCells cells) {
        return CompositeMaterialDistribution.splitDrawOrder(spec, cells);
    }

    /**
     * Rewrites the raw chest so its slots follow the split's draw order.  Distribution
     * fills slots in withdrawal order, which is alphabetical by item id and put
     * create:shaft ahead of the log the facing branch needed, so the hopper served the
     * wrong branch first and the run failed as route contamination.
     */
    private static void orderSplitSource(
            ServerLevel level, CompositePlayerOrderSpecV1 spec, CompositeSiteLayout layout) {
        CompositeSiteLayout.SplitCells cells = layout.split().orElse(null);
        if (cells == null) return;
        if (!(level.getBlockEntity(block(cells.rawSource())) instanceof Container container)) {
            throw new IllegalStateException("composite raw source chest missing");
        }
        List<ItemStack> ordered = new ArrayList<>();
        for (CompositeProductionGraph.MaterialEdge edge : splitDrawOrder(spec, cells)) {
            ItemStack found = ItemStack.EMPTY;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !identity(stack).itemId().equals(edge.resourceId())) continue;
                found = stack.copy();
                container.setItem(slot, ItemStack.EMPTY);
                break;
            }
            if (found.isEmpty()) {
                throw new IllegalStateException(
                        "composite raw source is missing " + edge.resourceId());
            }
            ordered.add(found);
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                throw new IllegalStateException("composite raw source holds an unexpected item");
            }
        }
        for (int slot = 0; slot < ordered.size(); slot++) {
            container.setItem(slot, ordered.get(slot));
        }
        container.setChanged();
    }

    /**
     * The split's three exact raw units, ordered so the facing branch's unit is first.
     */
    private static CreateV606BranchMergeExecution.RootSplit rootSplit(
            CompositePlayerOrderSpecV1 spec,
            CompositePlayerOrderSpecV1.RootSplitSpec splitSpec,
            CompositeSiteLayout.SplitCells cells) {
        List<CompositeProductionGraph.MaterialEdge> draw = splitDrawOrder(spec, cells);
        return new CreateV606BranchMergeExecution.RootSplit(
                splitSpec.nodeId(), cells.rawSource(), cells.hopper(), cells.lock(),
                draw.get(0).resourceId(), draw.get(0).quantity(),
                draw.get(1).resourceId(), draw.get(1).quantity(),
                draw.get(2).resourceId(), draw.get(2).quantity());
    }

    private static CreateV606BranchMergeExecution.HandlerStage branchStage(PlannedStage stage) {
        return new CreateV606BranchMergeExecution.HandlerStage(
                stage.spec().nodeId(), stage.ready().executionReadyPlan(), stage.ready().runtime(),
                stage.cells().source(), stage.cells().delivery(), stage.spec().target(),
                stage.spec().targetQuantity(), stage.cells().workerStarts(),
                stage.installationMaterials());
    }

    private static List<CreateV606BranchMergeExecution.MergeRoute> mergeRoutes(
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            PlannedStage first,
            PlannedStage second) {
        List<CreateV606BranchMergeExecution.MergeRoute> routes = new ArrayList<>();
        for (PlannedStage branch : List.of(first, second)) {
            CompositeProductionGraph.MaterialEdge edge =
                    spec.graph().outgoing(branch.spec().nodeId()).get(0);
            CompositeSiteLayout.RouteCells cells = layout.route(edge.edgeId());
            routes.add(new CreateV606BranchMergeExecution.MergeRoute(
                    edge.edgeId(), branch.spec().nodeId(), edge.resourceId(),
                    cells.hopper(), cells.lock(), cells.overflow().orElseThrow()));
        }
        return List.copyOf(routes);
    }

    private record StartedRun(boolean success, String code, CompositeRun run) {}

    private static List<CreateV606CompositeExecution.Stage> wrapperStages(List<PlannedStage> planned) {
        return planned.stream().map(stage -> new CreateV606CompositeExecution.Stage(
                        stage.spec().nodeId(), stage.ready().executionReadyPlan(),
                        stage.ready().runtime(), stage.cells().source(), stage.cells().delivery(),
                        stage.spec().target(), stage.spec().targetQuantity(),
                        stage.cells().workerStarts(), stage.installationMaterials()))
                .toList();
    }

    private static List<CreateV606CompositeExecution.HopperRoute> wrapperRoutes(
            CompositeProductionGraph graph, CompositeSiteLayout layout) {
        return graph.edges().stream().map(edge -> {
            CompositeSiteLayout.RouteCells cells = layout.route(edge.edgeId());
            return new CreateV606CompositeExecution.HopperRoute(edge.edgeId(),
                    edge.producerNodeId(), edge.consumerNodeId(), edge.resourceId(),
                    edge.quantity(), cells.hopper(), cells.lock(), cells.overflow());
        }).toList();
    }

    private static Map<ResourceId, Long> externalInputs(
            CompositePlayerOrderSpecV1 spec, CompositePlayerOrderSpecV1.StageSpec stage) {
        return CompositeMaterialDistribution.externalInputs(spec, stage);
    }

    private static Map<ResourceId, Long> installationMaterials(
            CreateV606GoalDrivenPlanner.Ready ready) {
        LinkedHashMap<ResourceId, Long> result = new LinkedHashMap<>();
        componentBlocks(ready).forEach(blockId -> {
            // Through the same binding the bill uses. This used to drop any block with no
            // item, which was the quiet half of the same bug: the bill asked for water and
            // was refused for it, while the stage demand pretended water was free. Once
            // the bill buys a bucket, a demand that has not heard of buckets leaves the
            // withdrawn stack with nowhere to go and the whole distribution rolls back.
            ResourceId supplied = PlacementItemBinding.itemFor(blockId).orElse(blockId);
            if (item(supplied) != null) result.merge(supplied, 1L, Math::addExact);
        });
        return Map.copyOf(result);
    }

    private static List<ResourceId> componentBlocks(CreateV606GoalDrivenPlanner.Ready ready) {
        return ready.executionReadyPlan().physicalPlan().placements().stream()
                .flatMap(placement -> placement.components().stream())
                .map(ResolvedGeometryComponent::blockId).toList();
    }

    private static List<BlockPos3i> componentCells(CreateV606GoalDrivenPlanner.Ready ready) {
        return ready.executionReadyPlan().physicalPlan().placements().stream()
                .flatMap(placement -> placement.components().stream())
                .map(ResolvedGeometryComponent::position).toList();
    }

    private static Map<ResourceId, Long> observedSalvage(ServerLevel level, Active active) {
        LinkedHashMap<ResourceId, Long> salvage = new LinkedHashMap<>();
        for (CompositeSiteLayout.RouteCells route : active.layout().routes()) {
            route.overflow().ifPresent(overflow -> active.spec().graph().edges().stream()
                    .filter(edge -> edge.edgeId().equals(route.routeId())).findFirst()
                    .ifPresent(edge -> {
                        long observed = count(level, overflow, edge.resourceId());
                        if (observed > 0) salvage.merge(edge.resourceId(), observed, Math::addExact);
                    }));
        }
        return Map.copyOf(salvage);
    }

    private static BlockPos3i finalDelivery(Active active) {
        return active.layout().stages().get(active.layout().stages().size() - 1).delivery();
    }

    private static long count(ServerLevel level, BlockPos3i position, ResourceId resource) {
        if (!(level.getBlockEntity(block(position)) instanceof Container container)) return 0;
        Item expected = item(resource);
        if (expected == null) return 0;
        long total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.is(expected)) total += stack.getCount();
        }
        return total;
    }

    /** Everything in a container, for reading progress off the site. */
    private static Map<ResourceId, Long> contents(ServerLevel level, BlockPos3i position) {
        if (!(level.getBlockEntity(block(position)) instanceof Container container)) {
            return Map.of();
        }
        Map<ResourceId, Long> result = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) continue;
            result.merge(ResourceId.parse(itemId.toString()),
                    (long) stack.getCount(), Math::addExact);
        }
        return result;
    }

    /**
     * What the site holds right now, in the order the resume decision expects: one
     * reading per stage source, per stage delivery, and per inter-stage hopper.
     */
    private static CompositeResumePlanV1.Observation observe(
            CompositeSiteLayout layout, ServerLevel level, CompositePlayerOrderSpecV1 spec) {
        List<Map<ResourceId, Long>> sources = new ArrayList<>();
        List<Map<ResourceId, Long>> deliveries = new ArrayList<>();
        for (CompositeSiteLayout.StageCells cells : layout.stages()) {
            sources.add(contents(level, cells.source()));
            deliveries.add(contents(level, cells.delivery()));
        }
        List<Map<ResourceId, Long>> hoppers = new ArrayList<>();
        for (CompositeProductionGraph.MaterialEdge edge : orderedEdges(spec)) {
            hoppers.add(contents(level, layout.route(edge.edgeId()).hopper()));
        }
        return new CompositeResumePlanV1.Observation(sources, deliveries, hoppers);
    }

    /** Edges in chain order, matching the stage order the layout lays out. */
    private static List<CompositeProductionGraph.MaterialEdge> orderedEdges(
            CompositePlayerOrderSpecV1 spec) {
        List<CompositeProductionGraph.MaterialEdge> ordered = new ArrayList<>();
        List<CompositePlayerOrderSpecV1.StageSpec> stages = spec.stages();
        for (int index = 0; index < stages.size() - 1; index++) {
            ordered.addAll(spec.graph().outgoing(stages.get(index).nodeId()));
        }
        return ordered;
    }

    private static String firstOccupiedCell(ServerLevel level, List<BlockPos3i> cells) {
        for (BlockPos3i cell : cells) {
            BlockPos position = block(cell);
            if (!level.hasChunkAt(position)) return cell + ":chunk_not_loaded";
            BlockState state = level.getBlockState(position);
            if ((!state.isAir() && !state.canBeReplaced()) || level.getBlockEntity(position) != null) {
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                return cell + ":" + (blockId == null ? "unregistered" : blockId);
            }
        }
        return null;
    }

    private static String baselineHash(ServerLevel level, List<BlockPos3i> cells) {
        StringBuilder canonical = new StringBuilder();
        cells.stream().sorted(Comparator.comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z))
                .forEach(cell -> canonical.append(cell.x()).append(',').append(cell.y()).append(',')
                        .append(cell.z()).append('=')
                        .append(NbtUtils.writeBlockState(level.getBlockState(block(cell))))
                        .append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static MaterialIdentity identity(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) throw new IllegalArgumentException("unregistered material item");
        return new MaterialIdentity(ResourceId.parse(key.toString()),
                PlayerMaterialService.canonicalPayload(stack));
    }

    private static Item item(ResourceId resource) {
        Item value = ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath(resource.namespace(), resource.path()));
        return value == null || value == Items.AIR ? null : value;
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static BlockPos3i pos(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    public record StartResult(boolean success, String code, UUID projectId, ResourceId orderType) {
        static StartResult failure(String code) {
            return new StartResult(false, code, null, null);
        }
    }

    public record PreviewResult(
            boolean success,
            String code,
            Map<ResourceId, Long> requirements,
            Map<ResourceId, Long> expectedSalvage) {}

    public record StatusResult(
            boolean success,
            String code,
            UUID projectId,
            dev.stevecreate.agent.core.execution.composite.CompositeProductionSnapshot snapshot) {}

    public record CompletionResult(
            boolean success,
            String code,
            IndustrialPlayerOrderV1 order,
            IndustrialCompletionReportV1 report) {
        static CompletionResult failure(String code) {
            return new CompletionResult(false, code, null, null);
        }
    }

    private record DistributionResult(boolean success, String code, Entry entry) {}

    private record Removed(Source source, Reservation reservation, ItemStack stack) {}

    private record PlannedStage(
            CompositePlayerOrderSpecV1.StageSpec spec,
            CompositeSiteLayout.StageCells cells,
            CreateV606GoalDrivenPlanner.Ready ready,
            Map<ResourceId, Long> installationMaterials,
            Map<ResourceId, Long> externalInputs) {}

    private record Active(
            UUID projectId,
            UUID ownerId,
            net.minecraft.resources.ResourceKey<Level> dimension,
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            CompositeRun run,
            Entry entry,
            ExecutionMode mode,
            String baselineHash,
            BlockPos3i sourcePosition,
            ServerPlayer owner,
            boolean retainSite) {}
}
