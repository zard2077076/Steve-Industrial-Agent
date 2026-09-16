package dev.stevecreate.agent.forge1201.command;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.PlayerFleetSizingPolicy;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ThreeModeExecution;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Transaction;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.navigation.BoundedBotNavigation;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Player-project bridge into the existing verified Direct/Bots/Hybrid executor. */
public final class PlayerConstructionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private PlayerConstructionService() {}

    public static Result start(ServerPlayer player, UUID projectId, long projectNonce) {
        if (!player.serverLevel().getServer().isSameThread()) return Result.failure("SERVER_THREAD_REQUIRED");
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = workflow.entry(player.getUUID()).orElse(null);
        if (project == null || !project.projectId().equals(projectId)) return Result.failure("PROJECT_NOT_FOUND");
        if (project.nonce() != projectNonce) return Result.failure("STALE_PROJECT_REQUEST");
        if (project.stage() != WorkflowStage.MATERIAL_RESERVED) return Result.failure("PROJECT_STAGE_MISMATCH");
        if (ACTIVE.containsKey(projectId)) return Result.failure("CONSTRUCTION_ALREADY_ACTIVE");
        PlayerMaterialSavedData materialData = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry entry = materialData.entry(projectId).orElse(null);
        if (entry == null || entry.reservations().isEmpty()) return Result.failure("MATERIAL_RESERVATION_MISSING");
        if (!entry.transactions().isEmpty()) {
            boolean onlyPrepared = entry.transactions().stream().allMatch(value ->
                    value.state() == MaterialTransactionState.PREPARED);
            boolean sourcesUnchanged = onlyPrepared && entry.sources().stream().allMatch(source -> {
                Source current = PlayerMaterialService.scan(player, source.position(),
                        PlayerMaterialService.direction(source.accessFace()), source.priority());
                return current != null && PlayerMaterialService.sameSnapshot(source, current);
            });
            if (!sourcesUnchanged) return pause(player, project, "MATERIAL_RECONCILIATION_REQUIRED");
            discardCourierEntities(player.serverLevel(), projectId);
            entry = releasePrepared(entry, player.serverLevel().getGameTime(),
                    "PREPARED_RECOVERY_RELEASED").withoutTransactions(
                            Instant.now().toEpochMilli(), "PREPARED_RECOVERY_RELEASED");
            materialData.put(entry);
        }
        long epochNow = Instant.now().toEpochMilli();
        if (entry.sources().stream().anyMatch(value -> value.expiresAt() <= epochNow)
                || entry.reservations().stream().anyMatch(value -> value.expiresAt() <= epochNow)) {
            return pause(player, project, "MATERIAL_RESERVATION_EXPIRED");
        }
        SitePreparationCommand.PreparedExecutionContext prepared =
                SitePreparationCommand.preparedExecutionContext(player).orElse(null);
        if (prepared == null) return Result.failure("PREPARED_SITE_NOT_CURRENT");
        PlayerVerifiedMaterialPlanResolver.Resolution materialResolution =
                PlayerVerifiedMaterialPlanResolver.resolve(player, project, prepared);
        if (!(materialResolution instanceof PlayerVerifiedMaterialPlanResolver.Ready resolved)) {
            return pause(player, project, ((PlayerVerifiedMaterialPlanResolver.Refused)
                    materialResolution).code());
        }
        if (!resolved.materialPlan().legacyRequirementTotals().equals(entry.requirements())
                || !resolved.materialPlan().planSha256().equals(entry.planHash())
                || !resolved.materialPlan().runtimeFingerprint().equals(entry.runtimeFingerprint())) {
            return Result.failure("MATERIAL_PLAN_CHANGED");
        }
        var ready = resolved.execution();
        Instant now = Instant.now();
        PreparedSiteExecutionGate.PlanningEvidence evidence = new PreparedSiteExecutionGate.PlanningEvidence(
                prepared.prepared().worldIdentity(), prepared.prepared().dimension(),
                prepared.prepared().preparedSiteIdentity(), prepared.prepared().cleanSiteSnapshotHash(),
                ready.executionReadyPlan().physicalPlan().id(),
                ready.executionReadyPlan().physicalPlan().candidate().snapshotFingerprint(),
                now, true, "forge1201:player-material-authoritative-planning-snapshot");
        var authorizationResult = new PreparedSiteExecutionGate().authorize(prepared.prepared(),
                prepared.selection(), ready.executionReadyPlan(), evidence, now);
        if (!(authorizationResult instanceof PreparedSiteExecutionGate.Authorized authorized)) {
            return pause(player, project, "PREPARED_SITE_REVALIDATION_FAILED");
        }
        Set<BlockPos3i> excluded = new HashSet<>(authorized.authorization().coveredCells());
        entry.sources().forEach(value -> {
            excluded.add(value.position());
            // A player-selected container is not usable if temporary logistics occupy
            // every cell from which the courier can actually reach it. Preserve its
            // immediate access ring before choosing any staging or worker cell.
            excluded.add(value.position().translate(1, 0, 0));
            excluded.add(value.position().translate(-1, 0, 0));
            excluded.add(value.position().translate(0, 0, 1));
            excluded.add(value.position().translate(0, 0, -1));
        });
        ExecutionMode executionMode = mode(project.executionMode());
        int workerCount = executionMode == ExecutionMode.DIRECT ? 0
                : PlayerFleetSizingPolicy.constructionWorkers(
                        SingleMachineGoalResolver.resolve(player.serverLevel(), project.target())
                                .orElseThrow(() -> new IllegalStateException(
                                        "player target disappeared from live catalog: "
                                                + project.target()))
                                .capability());
        int logisticsCount = 2 + workerCount;
        List<BlockPos3i> logistics = findLogisticsCells(player.serverLevel(),
                prepared.selection().authorizedBounds(), excluded, logisticsCount);
        if (logistics.size() < logisticsCount) {
            return pause(player, project, "CONSTRUCTION_LOGISTICS_SPACE_UNAVAILABLE");
        }
        BlockPos3i staging = logistics.get(0);
        BlockPos3i delivery = logistics.get(1);
        List<BlockPos3i> workers = workerCount == 0
                ? List.of() : List.copyOf(logistics.subList(2, logisticsCount));
        if (!placeEmptyChest(player.serverLevel(), staging)
                || !placeEmptyChest(player.serverLevel(), delivery)) {
            cleanupEmptyChest(player.serverLevel(), staging);
            cleanupEmptyChest(player.serverLevel(), delivery);
            return pause(player, project, "CONSTRUCTION_LOGISTICS_CHEST_FAILED");
        }
        entry = entry.withLogistics(new PlayerMaterialSavedData.Logistics(staging, delivery),
                Instant.now().toEpochMilli(), "CONSTRUCTION_LOGISTICS_BOUND");
        materialData.put(entry);
        player.serverLevel().getServer().overworld().getDataStorage().save();
        PendingStart pendingStart = new PendingStart(authorized.authorization(),
                prepared.prepared().worldIdentity(), ready.runtime(), executionMode,
                ready.executionReadyPlan().physicalPlan(),
                new CreateV606ThreeModeExecution.TestRegion(
                        prepared.selection().authorizedBounds().minimum(),
                        prepared.selection().authorizedBounds().maximum()),
                workers, ready.executionMetadata(), resolved.materialPlan().installedTotals());
        if (mode(project.executionMode()) != ExecutionMode.DIRECT) {
            Entry preparedEntry = prepareWithdrawal(player.serverLevel(), entry,
                    executor(project.executionMode()));
            MaterialCourier courier;
            try {
                courier = MaterialCourier.spawn(player.serverLevel(), projectId, preparedEntry,
                        staging, workers.get(0), prepared.selection().authorizedBounds());
            } catch (RuntimeException failure) {
                LOGGER.warn("MATERIAL_COURIER_START_REFUSED project={} staging={} worker={} bounds={}..{} reason={}",
                        projectId, staging, workers.get(0),
                        prepared.selection().authorizedBounds().minimum(),
                        prepared.selection().authorizedBounds().maximum(),
                        failure.toString(), failure);
                materialData.put(releasePrepared(preparedEntry, player.serverLevel().getGameTime(),
                        "MATERIAL_COURIER_START_REFUSED"));
                cleanupEmptyChest(player.serverLevel(), staging);
                cleanupEmptyChest(player.serverLevel(), delivery);
                return pause(player, project, "MATERIAL_COURIER_START_REFUSED");
            }
            long epoch = Instant.now().toEpochMilli();
            PlayerWorkflowSavedData.ProjectEntry next = project.withStage(WorkflowStage.CONSTRUCTION,
                    PlayerWorkflowService.nextNonce(), epoch, "BOT_FETCHING_PLAYER_MATERIALS");
            workflow.put(next);
            ACTIVE.put(projectId, new Active(player.getUUID(), projectId, staging, delivery,
                    null, courier, pendingStart, preparedEntry, next,
                    player.serverLevel().dimension()));
            IndustrialPlayerOrderService.checkpoint(player, projectId, IndustrialLifecyclePhase.RUNNING,
                    "CONSTRUCTION_MATERIAL_WITHDRAWAL", Set.of(ResourceId.parse("steve_industrial:materials_withdrawn")));
            return new Result(true, "OK", next, preparedEntry);
        }
        WithdrawResult withdrawal = withdrawToStaging(player, entry, staging,
                executor(project.executionMode()));
        if (!withdrawal.success()) {
            cleanupEmptyChest(player.serverLevel(), staging);
            cleanupEmptyChest(player.serverLevel(), delivery);
            return pause(player, project, withdrawal.code());
        }
        Entry withdrawn = withdrawal.entry();
        CreateV606ThreeModeExecution.StartResult start = CreateV606ThreeModeExecution.start(
                player.serverLevel(), pendingStart.authorization(), pendingStart.worldIdentity(),
                pendingStart.runtime(), staging, delivery, pendingStart.mode(),
                pendingStart.region(), pendingStart.workers(), pendingStart.metadata(),
                pendingStart.installationMaterials());
        if (!(start instanceof CreateV606ThreeModeExecution.Started started)) {
            boolean returned = returnStaging(player, withdrawn, staging);
            cleanupEmptyChest(player.serverLevel(), staging);
            cleanupEmptyChest(player.serverLevel(), delivery);
            var rejected = (CreateV606ThreeModeExecution.Rejected) start;
            return pause(player, project, returned
                    ? "CONSTRUCTION_START_REFUSED:" + rejected.code()
                    : "RETURN_PENDING:CONSTRUCTION_START_REFUSED");
        }
        long epoch = Instant.now().toEpochMilli();
        PlayerWorkflowSavedData.ProjectEntry next = project.withStage(WorkflowStage.CONSTRUCTION,
                PlayerWorkflowService.nextNonce(), epoch, "CONSTRUCTION_RUNNING");
        workflow.put(next);
        materialData.put(withdrawn);
        IndustrialPlayerOrderService.checkpoint(player, projectId, IndustrialLifecyclePhase.RUNNING,
                "CONSTRUCTION_RUNNING", Set.of(ResourceId.parse("steve_industrial:materials_withdrawn")));
        ACTIVE.put(projectId, new Active(player.getUUID(), projectId, staging, delivery,
                started.session(), null, null, withdrawn, next, player.serverLevel().dimension()));
        return new Result(true, "OK", next, withdrawn);
    }

    public static void tick(MinecraftServer server) {
        for (Active active : List.copyOf(ACTIVE.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(active.playerId());
            if (player == null || !player.serverLevel().dimension().equals(active.dimension())) continue;
            try {
                if (active.courier() != null) {
                    CourierTick courierTick = active.courier().tick();
                    if (courierTick == CourierTick.PROGRESS) continue;
                    if (courierTick == CourierTick.FAILED) {
                        failCourier(player, active, "MATERIAL_COURIER_FAILED");
                        ACTIVE.remove(active.projectId());
                        continue;
                    }
                    Entry delivered = PlayerMaterialSavedData.forLevel(player.serverLevel())
                            .entry(active.projectId()).orElseThrow();
                    PendingStart pending = Objects.requireNonNull(active.pendingStart());
                    CreateV606ThreeModeExecution.StartResult start = CreateV606ThreeModeExecution.start(
                            player.serverLevel(), pending.authorization(), pending.worldIdentity(),
                            pending.runtime(), active.staging(), active.delivery(), pending.mode(),
                            pending.region(), pending.workers(), pending.metadata(),
                            pending.installationMaterials());
                    if (!(start instanceof CreateV606ThreeModeExecution.Started started)) {
                        failCourier(player, active, "CONSTRUCTION_START_REFUSED_AFTER_COURIER");
                        ACTIVE.remove(active.projectId());
                        continue;
                    }
                    ACTIVE.put(active.projectId(), new Active(active.playerId(), active.projectId(),
                            active.staging(), active.delivery(), started.session(), null, null,
                            delivered, active.project(), active.dimension()));
                    continue;
                }
                CreateV606ThreeModeExecution.TickResult result = active.session().tick();
                if (result instanceof CreateV606ThreeModeExecution.Progress progress) {
                    updateStatus(player, active, "CONSTRUCTION_" + progress.outcome());
                } else if (result instanceof CreateV606ThreeModeExecution.Completed completed) {
                    complete(player, active, completed);
                    ACTIVE.remove(active.projectId());
                } else if (result instanceof CreateV606ThreeModeExecution.Failed failed) {
                    failAndReturn(player, active, "CONSTRUCTION_FAILED:" + failed.taskResult().detail());
                    ACTIVE.remove(active.projectId());
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Construction tick exception project={} courierActive={} reason={}",
                        active.projectId(), active.courier() != null, failure.toString(), failure);
                try {
                    if (active.courier() != null) failCourier(player, active, "MATERIAL_COURIER_EXCEPTION");
                    else failAndReturn(player, active, "CONSTRUCTION_EXCEPTION");
                }
                finally { ACTIVE.remove(active.projectId()); }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
            PlayerWorkflowSavedData.ProjectEntry project = data.entry(player.getUUID()).orElse(null);
            if (project != null && project.stage() == WorkflowStage.CONSTRUCTION
                    && !ACTIVE.containsKey(project.projectId())) {
                data.put(project.withStage(WorkflowStage.PAUSED,
                        PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(),
                        "MATERIAL_RECONCILIATION_REQUIRED"));
            }
        }
    }

    public static Result cancel(ServerPlayer player, UUID projectId, long nonce) {
        Active active = ACTIVE.get(projectId);
        if (active == null || !active.playerId().equals(player.getUUID())) {
            return Result.failure("CONSTRUCTION_NOT_ACTIVE");
        }
        if (active.project().nonce() != nonce) return Result.failure("STALE_PROJECT_REQUEST");
        try {
            if (active.courier() != null) {
                boolean returned = active.courier().cancelAndReturn();
                cleanupEmptyChest(player.serverLevel(), active.staging());
                cleanupEmptyChest(player.serverLevel(), active.delivery());
                ACTIVE.remove(projectId);
                WorkflowStage stage = returned ? WorkflowStage.CANCELLED : WorkflowStage.PAUSED;
                String code = returned ? "CANCELLED_MATERIALS_RETURNED" : "RETURN_PENDING";
                PlayerWorkflowSavedData.ProjectEntry next = active.project().withStage(stage,
                        PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(), code);
                PlayerWorkflowSavedData.forLevel(player.serverLevel()).put(next);
                IndustrialPlayerOrderService.cancel(player, projectId, returned);
                return new Result(returned, code, next, PlayerMaterialSavedData.forLevel(
                        player.serverLevel()).entry(projectId).orElse(active.entry()));
            }
            active.session().cancelDetailed(ResourceId.parse("steve_industrial:player_cancel"));
            boolean returned = returnStaging(player, active.entry(), active.staging());
            cleanupEmptyChest(player.serverLevel(), active.staging());
            cleanupEmptyChest(player.serverLevel(), active.delivery());
            ACTIVE.remove(projectId);
            WorkflowStage stage = returned ? WorkflowStage.CANCELLED : WorkflowStage.PAUSED;
            String code = returned ? "CANCELLED_MATERIALS_RETURNED" : "RETURN_PENDING";
            PlayerWorkflowSavedData.ProjectEntry next = active.project().withStage(stage,
                    PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(), code);
            PlayerWorkflowSavedData.forLevel(player.serverLevel()).put(next);
            IndustrialPlayerOrderService.cancel(player, projectId, returned);
            return new Result(returned, code, next,
                    PlayerMaterialSavedData.forLevel(player.serverLevel()).entry(projectId).orElse(active.entry()));
        } catch (RuntimeException failure) {
            return pause(player, active.project(), "RETURN_PENDING");
        }
    }

    public static Result recoverCancel(ServerPlayer player, UUID projectId, long nonce) {
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = workflow.entry(player.getUUID()).orElse(null);
        if (project == null || !project.projectId().equals(projectId)) return Result.failure("PROJECT_NOT_FOUND");
        if (project.nonce() != nonce) return Result.failure("STALE_PROJECT_REQUEST");
        if (project.stage() != WorkflowStage.PAUSED || !isMaterialPause(project.statusCode())) {
            return Result.failure("PROJECT_STAGE_MISMATCH");
        }
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry entry = data.entry(projectId).orElse(null);
        if (entry == null) return Result.failure("MATERIAL_PLAN_NOT_READY");
        boolean returned;
        if (entry.transactions().isEmpty() || entry.transactions().stream().allMatch(value ->
                value.state() == MaterialTransactionState.PREPARED
                        || value.state() == MaterialTransactionState.RELEASED)) {
            ArrayList<Transaction> released = new ArrayList<>();
            for (Transaction transaction : entry.transactions()) {
                released.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                        transaction.taskId(), transaction.executor(), MaterialTransactionState.RELEASED,
                        transaction.quantity(), player.serverLevel().getGameTime()));
            }
            entry = entry.withTransactions(released, Instant.now().toEpochMilli(),
                    "MATERIAL_RESERVATIONS_RELEASED", null);
            data.put(entry);
            discardCourierEntities(player.serverLevel(), projectId);
            returned = true;
        } else if (entry.transactions().stream().allMatch(value ->
                value.state() == MaterialTransactionState.RETURNED)) {
            returned = true;
        } else if (entry.logistics() != null) {
            try { returned = recoverMaterialRows(player, entry); }
            catch (RuntimeException failure) { returned = false; }
        } else returned = false;
        if (entry.logistics() != null) {
            cleanupEmptyChest(player.serverLevel(), entry.logistics().staging());
            cleanupEmptyChest(player.serverLevel(), entry.logistics().delivery());
        }
        WorkflowStage nextStage = returned ? WorkflowStage.CANCELLED : WorkflowStage.PAUSED;
        String code = returned ? "CANCELLED_MATERIALS_RETURNED" : "RETURN_PENDING";
        PlayerWorkflowSavedData.ProjectEntry next = project.withStage(nextStage,
                PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(), code);
        workflow.put(next);
        IndustrialPlayerOrderService.cancel(player, projectId, returned);
        return new Result(returned, code, next, data.entry(projectId).orElse(entry));
    }

    public static boolean isMaterialPause(String statusCode) {
        return statusCode.startsWith("MATERIAL_") || statusCode.startsWith("RETURN_PENDING")
                || statusCode.startsWith("CONSTRUCTION_");
    }

    public static void clearPlayer(UUID playerId) { /* Keep bounded live sessions across logout. */ }

    public static void clearServerState() { ACTIVE.clear(); }

    public static boolean isActive(UUID projectId) { return ACTIVE.containsKey(projectId); }

    /** Read-only view of the exact active project-owned plan and transport. */
    public static java.util.Optional<DiagnosticContext> diagnosticContext(UUID projectId) {
        Active active = ACTIVE.get(Objects.requireNonNull(projectId, "projectId"));
        if (active == null) return java.util.Optional.empty();
        CreateV606ThreeModeExecution.DiagnosticSnapshot session = active.session() == null
                ? null : active.session().diagnosticSnapshot();
        VerifiedPhysicalPlan plan = session != null ? session.physicalPlan()
                : Objects.requireNonNull(active.pendingStart(), "pendingStart").physicalPlan();
        LogisticsDiagnosticSnapshot logistics = active.courier() == null
                ? null : active.courier().diagnosticSnapshot();
        return java.util.Optional.of(new DiagnosticContext(active.projectId(), active.staging(),
                active.delivery(), plan, java.util.Optional.ofNullable(session),
                java.util.Optional.ofNullable(logistics)));
    }

    private static WithdrawResult withdrawToStaging(ServerPlayer player, Entry entry,
            BlockPos3i stagingPosition, MaterialExecutorKind executor) {
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(block(stagingPosition)) instanceof ChestBlockEntity staging)) {
            return WithdrawResult.failure("MATERIAL_STAGING_UNAVAILABLE");
        }
        Map<UUID, Source> sources = new LinkedHashMap<>();
        for (Source source : entry.sources()) {
            Source current = PlayerMaterialService.scan(player, source.position(),
                    PlayerMaterialService.direction(source.accessFace()), source.priority());
            if (current == null || !PlayerMaterialService.sameSnapshot(source, current)) {
                return WithdrawResult.failure("MATERIAL_SOURCE_CHANGED");
            }
            sources.put(source.sourceId(), source);
        }
        long tick = level.getGameTime();
        Entry preparedEntry = prepareWithdrawal(level, entry, executor);
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        ArrayList<Removed> removed = new ArrayList<>();
        Entry journalEntry = preparedEntry;
        try {
            int stagingSlot = 0;
            for (int index = 0; index < entry.reservations().size(); index++) {
                Reservation reservation = entry.reservations().get(index);
                Source source = sources.get(reservation.sourceId());
                BlockPos sourcePos = block(source.position());
                if (!(level.getBlockEntity(sourcePos) instanceof Container container)) {
                    throw new IllegalStateException("source disappeared");
                }
                // By identity rather than by slot. This is the path with the long
                // window — a player previews, walks away, tidies the chest, comes back
                // and approves — and it is where slot-exactness cost the most.
                ItemStack extracted =
                        PlayerMaterialService.withdrawReserved(container, reservation);
                removed.add(new Removed(source, reservation, extracted.copy()));
                journalEntry = advanceTransaction(journalEntry, reservation.reservationId(),
                        MaterialTransactionState.WITHDRAWN, tick, "MATERIAL_WITHDRAWN");
                data.put(journalEntry);
                level.getServer().overworld().getDataStorage().save();
                while (stagingSlot < staging.getContainerSize()
                        && !staging.getItem(stagingSlot).isEmpty()) stagingSlot++;
                if (stagingSlot >= staging.getContainerSize()) throw new IllegalStateException("staging full");
                staging.setItem(stagingSlot++, extracted);
                staging.setChanged();
                journalEntry = advanceTransaction(journalEntry, reservation.reservationId(),
                        MaterialTransactionState.DELIVERED, tick, "MATERIAL_DELIVERED");
                data.put(journalEntry);
                level.getServer().overworld().getDataStorage().save();
            }
            staging.setChanged();
        } catch (RuntimeException failure) {
            rollbackRemoved(level, staging, removed);
            ArrayList<Transaction> released = new ArrayList<>();
            for (Transaction transaction : preparedEntry.transactions()) {
                released.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                        transaction.taskId(), transaction.executor(), MaterialTransactionState.RELEASED,
                        transaction.quantity(), tick));
            }
            data.put(preparedEntry.withTransactions(released, Instant.now().toEpochMilli(),
                    "MATERIAL_WITHDRAWAL_ROLLED_BACK", null));
            level.getServer().overworld().getDataStorage().save();
            return WithdrawResult.failure("MATERIAL_WITHDRAWAL_ROLLED_BACK");
        }
        Entry deliveredEntry = journalEntry.withTransactions(journalEntry.transactions(),
                Instant.now().toEpochMilli(), "MATERIALS_DELIVERED_TO_CONSTRUCTION", null);
        data.put(deliveredEntry);
        return new WithdrawResult(true, "OK", deliveredEntry);
    }

    static Entry prepareWithdrawal(ServerLevel level, Entry entry,
            MaterialExecutorKind executor) {
        Map<UUID, Source> sources = new LinkedHashMap<>();
        entry.sources().forEach(value -> sources.put(value.sourceId(), value));
        long tick = level.getGameTime();
        ArrayList<Transaction> prepared = new ArrayList<>(entry.transactions());
        for (Reservation reservation : entry.reservations()) {
            Objects.requireNonNull(sources.get(reservation.sourceId()));
            prepared.add(new Transaction(UUID.randomUUID(), reservation.reservationId(),
                    "material-" + reservation.requirement(), executor,
                    MaterialTransactionState.PREPARED, reservation.quantity(), tick));
        }
        Entry preparedEntry = entry.withTransactions(prepared, Instant.now().toEpochMilli(),
                "MATERIAL_WITHDRAWAL_PREPARED", null);
        PlayerMaterialSavedData.forLevel(level).put(preparedEntry);
        level.getServer().overworld().getDataStorage().save();
        return preparedEntry;
    }

    private static Entry releasePrepared(Entry entry, long tick, String code) {
        ArrayList<Transaction> released = new ArrayList<>();
        for (Transaction transaction : entry.transactions()) {
            released.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                    transaction.taskId(), transaction.executor(), MaterialTransactionState.RELEASED,
                    transaction.quantity(), tick));
        }
        return entry.withTransactions(released, Instant.now().toEpochMilli(), code, null);
    }

    private static boolean returnStaging(ServerPlayer player, Entry entry, BlockPos3i stagingPosition) {
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(block(stagingPosition)) instanceof Container staging)) return false;
        for (Reservation reservation : entry.reservations()) {
            Source source = entry.sources().stream().filter(value -> value.sourceId().equals(reservation.sourceId()))
                    .findFirst().orElse(null);
            if (source == null || !(level.getBlockEntity(block(source.position())) instanceof Container container)) return false;
            ItemStack current = container.getItem(reservation.slot());
            Item item = item(reservation.identity().itemId());
            int capacity = current.isEmpty() ? item.getMaxStackSize()
                    : identity(current).equals(reservation.identity())
                            ? current.getMaxStackSize() - current.getCount() : 0;
            if (capacity < reservation.quantity()) return false;
        }
        Map<MaterialIdentity, Long> requiredReturns = new LinkedHashMap<>();
        for (Reservation reservation : entry.reservations()) {
            requiredReturns.merge(reservation.identity(), reservation.quantity(), Math::addExact);
        }
        for (Map.Entry<MaterialIdentity, Long> required : requiredReturns.entrySet()) {
            long available = 0;
            for (int slot = 0; slot < staging.getContainerSize(); slot++) {
                ItemStack current = staging.getItem(slot);
                if (!current.isEmpty() && identity(current).equals(required.getKey())) {
                    available = Math.addExact(available, current.getCount());
                }
            }
            if (available < required.getValue()) return false;
        }
        ArrayList<Transaction> pendingRows = new ArrayList<>();
        for (Transaction transaction : entry.transactions()) {
            pendingRows.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                    transaction.taskId(), transaction.executor(), MaterialTransactionState.RETURN_PENDING,
                    transaction.quantity(), level.getGameTime()));
        }
        Entry pending = entry.withTransactions(pendingRows, Instant.now().toEpochMilli(),
                "RETURN_PENDING", null);
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        data.put(pending);
        level.getServer().overworld().getDataStorage().save();
        for (Reservation reservation : entry.reservations()) {
            Source source = entry.sources().stream().filter(value -> value.sourceId().equals(reservation.sourceId()))
                    .findFirst().orElseThrow();
            Container container = (Container) level.getBlockEntity(block(source.position()));
            ItemStack returned = removeExact(staging, reservation.identity(), reservation.quantity());
            ItemStack current = container.getItem(reservation.slot());
            if (current.isEmpty()) container.setItem(reservation.slot(), returned);
            else current.grow(returned.getCount());
            container.setChanged();
        }
        ArrayList<Transaction> returnedRows = new ArrayList<>();
        for (Transaction transaction : pending.transactions()) {
            returnedRows.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                    transaction.taskId(), transaction.executor(), MaterialTransactionState.RETURNED,
                    transaction.quantity(), level.getGameTime()));
        }
        Entry updated = pending.withTransactions(returnedRows, Instant.now().toEpochMilli(),
                "MATERIALS_RETURNED", null);
        data.put(updated);
        return true;
    }

    private static boolean recoverMaterialRows(ServerPlayer player, Entry entry) {
        ServerLevel level = player.serverLevel();
        if (entry.logistics() == null) return false;
        Map<UUID, Source> sources = new LinkedHashMap<>();
        entry.sources().forEach(value -> sources.put(value.sourceId(), value));
        List<ConstructionBotEntity> couriers = courierEntities(level, entry.projectId());
        Container staging = level.getBlockEntity(block(entry.logistics().staging())) instanceof Container value
                ? value : null;
        Map<MaterialIdentity, Long> stagingRequired = new LinkedHashMap<>();
        for (Reservation reservation : entry.reservations()) {
            Transaction transaction = transaction(entry, reservation.reservationId());
            if (transaction == null || transaction.state() == MaterialTransactionState.RELEASED
                    || transaction.state() == MaterialTransactionState.RETURNED) continue;
            if (transaction.state() == MaterialTransactionState.CONSUMED) return false;
            if (transaction.state() == MaterialTransactionState.PREPARED) continue;
            Source source = sources.get(reservation.sourceId());
            if (source == null || !(level.getBlockEntity(block(source.position())) instanceof Container container)) {
                return false;
            }
            ItemStack current = container.getItem(reservation.slot());
            int capacity = current.isEmpty() ? item(reservation.identity().itemId()).getMaxStackSize()
                    : identity(current).equals(reservation.identity())
                            ? current.getMaxStackSize() - current.getCount() : 0;
            if (capacity < reservation.quantity()) return false;
            ConstructionBotEntity carrier = carrier(couriers, reservation);
            if (carrier == null) stagingRequired.merge(reservation.identity(), reservation.quantity(), Math::addExact);
        }
        if (!stagingRequired.isEmpty() && staging == null) return false;
        for (Map.Entry<MaterialIdentity, Long> required : stagingRequired.entrySet()) {
            long available = count(staging, required.getKey());
            if (available < required.getValue()) return false;
        }
        Entry currentEntry = entry;
        for (Reservation reservation : entry.reservations()) {
            Transaction transaction = transaction(currentEntry, reservation.reservationId());
            if (transaction == null || transaction.state() == MaterialTransactionState.RELEASED
                    || transaction.state() == MaterialTransactionState.RETURNED) continue;
            if (transaction.state() == MaterialTransactionState.PREPARED) {
                currentEntry = advanceTransaction(currentEntry, reservation.reservationId(),
                        MaterialTransactionState.RELEASED, level.getGameTime(),
                        "MATERIAL_RESERVATION_RELEASED");
                PlayerMaterialSavedData.forLevel(level).put(currentEntry);
                continue;
            }
            currentEntry = advanceTransaction(currentEntry, reservation.reservationId(),
                    MaterialTransactionState.RETURN_PENDING, level.getGameTime(), "RETURN_PENDING");
            PlayerMaterialSavedData.forLevel(level).put(currentEntry);
            level.getServer().overworld().getDataStorage().save();
            ConstructionBotEntity carrier = carrier(couriers, reservation);
            ItemStack returned;
            if (carrier != null) {
                returned = carrier.getItemBySlot(EquipmentSlot.MAINHAND).copy();
                if (returned.getCount() != reservation.quantity()
                        || !identity(returned).equals(reservation.identity())) return false;
                carrier.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                carrier.removeTag(MaterialCourier.RESERVATION_TAG + reservation.reservationId()
                        .toString().replace("-", ""));
            } else {
                returned = removeExact(staging, reservation.identity(), reservation.quantity());
            }
            Source source = sources.get(reservation.sourceId());
            Container destination = (Container) level.getBlockEntity(block(source.position()));
            ItemStack existing = destination.getItem(reservation.slot());
            if (existing.isEmpty()) destination.setItem(reservation.slot(), returned);
            else existing.grow(returned.getCount());
            destination.setChanged();
            currentEntry = advanceTransaction(currentEntry, reservation.reservationId(),
                    MaterialTransactionState.RETURNED, level.getGameTime(),
                    "MATERIAL_RETURNED_TO_PLAYER_SOURCE");
            PlayerMaterialSavedData.forLevel(level).put(currentEntry);
        }
        discardCourierEntities(level, entry.projectId());
        return true;
    }

    private static Transaction transaction(Entry entry, UUID reservationId) {
        return entry.transactions().stream().filter(value -> value.reservationId().equals(reservationId))
                .findFirst().orElse(null);
    }

    private static long count(Container container, MaterialIdentity identity) {
        long total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && identity(stack).equals(identity)) total += stack.getCount();
        }
        return total;
    }

    private static List<ConstructionBotEntity> courierEntities(ServerLevel level, UUID projectId) {
        String projectTag = MaterialCourier.PROJECT_TAG + projectId.toString().replace("-", "");
        ArrayList<ConstructionBotEntity> result = new ArrayList<>();
        for (Entity value : level.getAllEntities()) {
            if (value instanceof ConstructionBotEntity bot
                    && bot.getTags().contains(MaterialCourier.COURIER_TAG)
                    && bot.getTags().contains(projectTag)) result.add(bot);
        }
        return List.copyOf(result);
    }

    private static ConstructionBotEntity carrier(
            List<ConstructionBotEntity> couriers, Reservation reservation) {
        String tag = MaterialCourier.RESERVATION_TAG
                + reservation.reservationId().toString().replace("-", "");
        return couriers.stream().filter(value -> value.getTags().contains(tag)).findFirst().orElse(null);
    }

    /** Remove any persisted courier for a project, including one whose active handle was lost
     * after a failure was checkpointed.  This is package-visible so an industrial order can
     * perform the same bounded cleanup during its player-facing cancel path. */
    static void discardCourierEntities(ServerLevel level, UUID projectId) {
        courierEntities(level, projectId).forEach(Entity::discard);
    }

    private static void complete(ServerPlayer player, Active active,
            CreateV606ThreeModeExecution.Completed completed) {
        boolean baselineRestored;
        try {
            CreateV606ThreeModeExecution.CleanupReport cleanup = active.session().cleanup();
            baselineRestored = cleanup.remainingPositions() == 0
                    && cleanup.clearedPositions() == cleanup.plannedPositions();
        } catch (RuntimeException failure) {
            baselineRestored = false;
        }
        Entry entry = active.entry();
        ArrayList<Transaction> consumed = new ArrayList<>();
        for (Transaction transaction : entry.transactions()) {
            consumed.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                    transaction.taskId(), transaction.executor(), MaterialTransactionState.CONSUMED,
                    transaction.quantity(), player.serverLevel().getGameTime()));
        }
        Entry consumedEntry = entry.withTransactions(consumed, Instant.now().toEpochMilli(),
                "MATERIALS_CONSUMED", null);
        long planned = entry.requirements().values().stream().mapToLong(Long::longValue).sum();
        long withdrawn = consumed.stream().mapToLong(Transaction::quantity).sum();
        long observed = completed.process().observedQuantity();
        Set<UUID> salvageReservations = entry.reservations().stream().filter(reservation ->
                entry.sources().stream().anyMatch(source -> source.sourceId().equals(reservation.sourceId())
                        && source.projectSalvage()))
                .map(Reservation::reservationId).collect(java.util.stream.Collectors.toSet());
        long salvageTransferred = consumed.stream()
                .filter(value -> salvageReservations.contains(value.reservationId()))
                .mapToLong(Transaction::quantity).sum();
        long duplicateWithdrawals = duplicateEvents(consumedEntry,
                MaterialTransactionState.WITHDRAWN);
        long duplicateReturns = duplicateEvents(consumedEntry,
                MaterialTransactionState.RETURNED);
        long unaccounted = Math.abs(planned - withdrawn) + Math.max(0, withdrawn
                - consumed.stream().mapToLong(Transaction::quantity).sum());
        boolean balanced = planned == withdrawn && duplicateWithdrawals == 0
                && duplicateReturns == 0 && unaccounted == 0;
        var report = new PlayerMaterialSavedData.CompletionReport(planned, withdrawn, withdrawn,
                0, salvageTransferred, observed, active.project().quantity(), duplicateWithdrawals,
                duplicateReturns, unaccounted, 0, balanced);
        Entry updated = consumedEntry.withTransactions(consumed, Instant.now().toEpochMilli(),
                balanced ? "MATERIAL_LEDGER_BALANCED" : "MATERIAL_LEDGER_RECONCILIATION_REQUIRED",
                report);
        PlayerMaterialSavedData.forLevel(player.serverLevel()).put(updated);
        if (balanced && baselineRestored) {
            IndustrialPlayerOrderService.complete(player, active.project(), updated, true);
        } else {
            IndustrialPlayerOrderService.checkpoint(player, active.project().projectId(),
                    IndustrialLifecyclePhase.PAUSED,
                    baselineRestored ? "MATERIAL_LEDGER_RECONCILIATION_REQUIRED"
                            : "BASELINE_RESTORE_REQUIRED",
                    Set.of(ResourceId.parse("steve_industrial:output_observed")));
        }
        cleanupEmptyChest(player.serverLevel(), active.staging());
        PlayerWorkflowSavedData.ProjectEntry project = active.project().withStage(
                balanced && baselineRestored ? WorkflowStage.COMPLETED : WorkflowStage.PAUSED,
                PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(),
                balanced && baselineRestored ? "COMPLETED_MATERIAL_LEDGER_BALANCED"
                        : baselineRestored ? "MATERIAL_LEDGER_RECONCILIATION_REQUIRED"
                                : "BASELINE_RESTORE_REQUIRED");
        PlayerWorkflowSavedData.forLevel(player.serverLevel()).put(project);
        if (project.stage() == WorkflowStage.COMPLETED) {
            SitePreparationCommand.releasePlayerWorkflow(player, project.projectId());
        }
        PlayerWorkflowNetwork.sendMaterialSnapshot(player,
                PlayerMaterialService.snapshot(player, active.projectId()), project);
    }

    private static long duplicateEvents(Entry entry, MaterialTransactionState state) {
        return entry.journal().stream().filter(value -> value.state() == state)
                .collect(java.util.stream.Collectors.groupingBy(
                        PlayerMaterialSavedData.JournalEvent::transactionId,
                        java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(value -> Math.max(0, value - 1)).sum();
    }

    private static void failAndReturn(ServerPlayer player, Active active, String code) {
        LOGGER.warn("Construction failed project={} code={} staging={} delivery={}",
                active.projectId(), code, active.staging(), active.delivery());
        boolean executorClean = true;
        try { active.session().cleanupFailed(); } catch (RuntimeException ignored) { executorClean = false; }
        boolean returned = false;
        if (executorClean) {
            try { returned = returnStaging(player, active.entry(), active.staging()); }
            catch (RuntimeException ignored) { returned = false; }
        }
        cleanupEmptyChest(player.serverLevel(), active.staging());
        cleanupEmptyChest(player.serverLevel(), active.delivery());
        PlayerWorkflowSavedData.ProjectEntry project = active.project().withStage(WorkflowStage.PAUSED,
                PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(),
                returned ? code + ":MATERIALS_RETURNED" : "RETURN_PENDING");
        PlayerWorkflowSavedData.forLevel(player.serverLevel()).put(project);
        checkpointMaterialPause(player, active.projectId(), project.statusCode(), returned);
    }

    private static void failCourier(ServerPlayer player, Active active, String code) {
        LOGGER.warn("Material courier failed project={} code={} detail={}",
                active.projectId(), code, active.courier().failureCode());
        boolean returned;
        try { returned = active.courier().cancelAndReturn(); }
        catch (RuntimeException failure) { returned = false; }
        cleanupEmptyChest(player.serverLevel(), active.staging());
        cleanupEmptyChest(player.serverLevel(), active.delivery());
        PlayerWorkflowSavedData.ProjectEntry project = active.project().withStage(WorkflowStage.PAUSED,
                PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(),
                returned ? code + ":MATERIALS_RETURNED" : "RETURN_PENDING");
        PlayerWorkflowSavedData.forLevel(player.serverLevel()).put(project);
        checkpointMaterialPause(player, active.projectId(), project.statusCode(), returned);
    }

    private static void checkpointMaterialPause(
            ServerPlayer player, UUID projectId, String stage, boolean returned) {
        IndustrialPlayerOrderService.checkpoint(player, projectId, IndustrialLifecyclePhase.PAUSED,
                stage, Set.of(ResourceId.parse(returned
                        ? "steve_industrial:materials_returned"
                        : "steve_industrial:return_pending")));
    }

    private static void updateStatus(ServerPlayer player, Active active, String code) {
        // Progress is transient executor state. Keep the project nonce stable so controls remain
        // valid, and publish at human-readable cadence rather than making the client or acceptance
        // harness hammer a refresh control.
        long tick = player.serverLevel().getGameTime();
        if (tick % 20L != 0L) return;
        SitePreparationCommand.PlayerClearingSnapshot snapshot =
                new SitePreparationCommand.PlayerClearingSnapshot(
                        true, false, true, "CONSTRUCTION", 0, 0, 0, 0, 0, 1,
                        code, true);
        PlayerWorkflowNetwork.sendClearingStatus(player, active.project(), snapshot);
    }

    static Entry advanceTransaction(Entry entry, UUID reservationId,
            MaterialTransactionState state, long tick, String code) {
        ArrayList<Transaction> rows = new ArrayList<>();
        boolean found = false;
        for (Transaction transaction : entry.transactions()) {
            if (transaction.reservationId().equals(reservationId)) {
                rows.add(new Transaction(transaction.transactionId(), transaction.reservationId(),
                        transaction.taskId(), transaction.executor(), state,
                        transaction.quantity(), tick));
                found = true;
            } else rows.add(transaction);
        }
        if (!found) throw new IllegalStateException("reservation transaction missing");
        return entry.withTransactions(rows, Instant.now().toEpochMilli(), code, null);
    }

    private static Result pause(ServerPlayer player, PlayerWorkflowSavedData.ProjectEntry project, String code) {
        PlayerWorkflowSavedData.ProjectEntry next = project.withStage(WorkflowStage.PAUSED,
                PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(), code);
        PlayerWorkflowSavedData.forLevel(player.serverLevel()).put(next);
        return new Result(false, code, next,
                PlayerMaterialSavedData.forLevel(player.serverLevel()).entry(project.projectId()).orElse(null));
    }

    private static List<BlockPos3i> findLogisticsCells(ServerLevel level,
            DeploymentBoundingBox bounds, Set<BlockPos3i> excluded, int count) {
        ArrayList<BlockPos3i> values = new ArrayList<>();
        for (int y = bounds.minimum().y(); y <= bounds.maximum().y() && values.size() < count; y++) {
            for (int z = bounds.minimum().z(); z <= bounds.maximum().z() && values.size() < count; z++) {
                for (int x = bounds.minimum().x(); x <= bounds.maximum().x() && values.size() < count; x++) {
                    BlockPos3i candidate = new BlockPos3i(x, y, z);
                    if (excluded.contains(candidate)) continue;
                    BlockPos pos = block(candidate);
                    if (canStand(level, pos)) {
                        if (values.size() < 2) {
                            if (!values.isEmpty() && horizontallyAdjacent(values.get(0), candidate)) {
                                continue;
                            }
                            BlockPos3i access = findReservedContainerAccess(
                                    level, bounds, candidate, excluded, values);
                            if (access == null) continue;
                            values.add(candidate);
                            excluded.add(candidate);
                            excluded.add(access);
                        } else {
                            values.add(candidate);
                            excluded.add(candidate);
                        }
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    private static BlockPos3i findReservedContainerAccess(
            ServerLevel level, DeploymentBoundingBox bounds, BlockPos3i container,
            Set<BlockPos3i> excluded, List<BlockPos3i> selected) {
        for (BlockPos3i candidate : List.of(
                container.translate(1, 0, 0), container.translate(-1, 0, 0),
                container.translate(0, 0, 1), container.translate(0, 0, -1))) {
            if (candidate.x() < bounds.minimum().x() || candidate.x() > bounds.maximum().x()
                    || candidate.y() < bounds.minimum().y() || candidate.y() > bounds.maximum().y()
                    || candidate.z() < bounds.minimum().z() || candidate.z() > bounds.maximum().z()
                    || excluded.contains(candidate) || selected.contains(candidate)) continue;
            if (canStand(level, block(candidate))) return candidate;
        }
        return null;
    }

    static boolean horizontallyAdjacent(BlockPos3i first, BlockPos3i second) {
        return first.y() == second.y()
                && Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z()) == 1;
    }

    private static boolean placeEmptyChest(ServerLevel level, BlockPos3i position) {
        BlockPos pos = block(position);
        if (!level.getBlockState(pos).isAir() || level.getBlockEntity(pos) != null) return false;
        return level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
                && level.getBlockEntity(pos) instanceof ChestBlockEntity;
    }

    private static void cleanupEmptyChest(ServerLevel level, BlockPos3i position) {
        BlockPos pos = block(position);
        if (level.getBlockEntity(pos) instanceof Container container && container.isEmpty()) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void rollbackRemoved(ServerLevel level, Container staging, List<Removed> removed) {
        staging.clearContent();
        for (Removed row : removed) {
            if (level.getBlockEntity(block(row.source().position())) instanceof Container container) {
                ItemStack current = container.getItem(row.reservation().slot());
                if (current.isEmpty()) container.setItem(row.reservation().slot(), row.stack());
                else if (identity(current).equals(row.reservation().identity())) current.grow(row.stack().getCount());
                else throw new IllegalStateException("rollback source slot changed");
                container.setChanged();
            }
        }
    }

    private static ItemStack removeExact(Container container, MaterialIdentity identity, long quantity) {
        int remaining = Math.toIntExact(quantity);
        ItemStack result = new ItemStack(item(identity.itemId()), remaining);
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack current = container.getItem(slot);
            if (!current.isEmpty() && identity(current).equals(identity)) {
                int take = Math.min(remaining, current.getCount());
                container.removeItem(slot, take); remaining -= take;
            }
        }
        if (remaining != 0) throw new IllegalStateException("staging lacks exact return material");
        container.setChanged(); return result;
    }

    private static MaterialIdentity identity(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("empty stack has no material identity");
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) throw new IllegalArgumentException("unregistered material item");
        String payload = PlayerMaterialService.canonicalPayload(stack);
        return new MaterialIdentity(ResourceId.parse(key.toString()), payload);
    }

    private static Item item(ResourceId id) {
        Item item = ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path()));
        if (item == null) throw new IllegalArgumentException("unregistered material item " + id);
        return item;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ExecutionMode mode(PlayerExecutionMode mode) {
        return switch (mode) {
            case DIRECT -> ExecutionMode.DIRECT;
            case HYBRID -> ExecutionMode.HYBRID;
            case BOTS, SMART_RECOMMENDED -> ExecutionMode.BOTS;
        };
    }

    private static MaterialExecutorKind executor(PlayerExecutionMode mode) {
        return switch (mode) {
            case DIRECT -> MaterialExecutorKind.DIRECT;
            case HYBRID -> MaterialExecutorKind.HYBRID;
            case BOTS, SMART_RECOMMENDED -> MaterialExecutorKind.BOT;
        };
    }

    private static BlockPos block(BlockPos3i value) { return new BlockPos(value.x(), value.y(), value.z()); }

    private static boolean canStand(ServerLevel level, BlockPos position) {
        return BoundedBotNavigation.canStand(level, position);
    }

    public record Result(boolean success, String statusCode,
            PlayerWorkflowSavedData.ProjectEntry project, Entry entry) {
        static Result failure(String code) { return new Result(false, code, null, null); }
    }

    private record WithdrawResult(boolean success, String code, Entry entry) {
        static WithdrawResult failure(String code) { return new WithdrawResult(false, code, null); }
    }
    private record Removed(Source source, Reservation reservation, ItemStack stack) {}
    private record Active(UUID playerId, UUID projectId, BlockPos3i staging, BlockPos3i delivery,
            CreateV606ThreeModeExecution.Session session, MaterialCourier courier,
            PendingStart pendingStart, Entry entry,
            PlayerWorkflowSavedData.ProjectEntry project,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {}

    private record PendingStart(
            dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionAuthorization authorization,
            String worldIdentity,
            dev.stevecreate.agent.adapter.api.RuntimeFingerprint runtime,
            ExecutionMode mode,
            VerifiedPhysicalPlan physicalPlan,
            CreateV606ThreeModeExecution.TestRegion region,
            List<BlockPos3i> workers,
            dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606VerifiedExecutionMetadata metadata,
            Map<ResourceId, Long> installationMaterials) {
        private PendingStart {
            Objects.requireNonNull(physicalPlan, "physicalPlan");
            installationMaterials = Map.copyOf(installationMaterials);
        }
    }

    public record DiagnosticContext(
            UUID projectId,
            BlockPos3i staging,
            BlockPos3i delivery,
            VerifiedPhysicalPlan physicalPlan,
            java.util.Optional<CreateV606ThreeModeExecution.DiagnosticSnapshot> session,
            java.util.Optional<LogisticsDiagnosticSnapshot> logistics) {
        public DiagnosticContext {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(staging, "staging");
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(physicalPlan, "physicalPlan");
            session = Objects.requireNonNull(session, "session");
            logistics = Objects.requireNonNull(logistics, "logistics");
        }
    }

    public record LogisticsDiagnosticSnapshot(
            long expectedTransfers,
            long completedTransfers,
            long lastProgressTick,
            long observedTick,
            boolean explicitlyBlocked,
            boolean carrying) {
        public LogisticsDiagnosticSnapshot {
            if (expectedTransfers < 1 || completedTransfers < 0
                    || completedTransfers > expectedTransfers || lastProgressTick < 0
                    || observedTick < lastProgressTick) {
                throw new IllegalArgumentException("material courier diagnostic snapshot is invalid");
            }
        }
    }

    enum CourierTick { PROGRESS, COMPLETED, FAILED }

    /** Visible, bounded pre-construction transport from each exact player-selected slot. */
    static final class MaterialCourier {
        private static final String COURIER_TAG = "steve_industrial_player_material_courier";
        private static final String PROJECT_TAG = "material_project_";
        private static final String RESERVATION_TAG = "material_reservation_";
        private static final int MAX_PATH_VISITS = 8_192;
        private final ServerLevel level;
        private final UUID projectId;
        private final BlockPos3i staging;
        private final Map<UUID, Source> sources;
        private final List<Reservation> reservations;
        private final UUID entityId;
        private final BlockPos minimum;
        private final BlockPos maximum;
        private final ServerPlayer ownerOverride;
        private Entry entry;
        private int index;
        private boolean carrying;
        private ItemStack carried = ItemStack.EMPTY;
        private List<BlockPos> path = List.of();
        private BlockPos pathTarget;
        private int repathFailures;
        private int missingEntityTicks;
        private boolean failed;
        private String failureCode = "NONE";
        private long lastProgressTick;
        private boolean retainAfterDelivery;

        private MaterialCourier(ServerLevel level, UUID projectId, Entry entry,
                BlockPos3i staging, UUID entityId, BlockPos minimum, BlockPos maximum,
                ServerPlayer ownerOverride) {
            this.level = level;
            this.projectId = projectId;
            this.entry = entry;
            this.staging = staging;
            this.entityId = entityId;
            this.minimum = minimum;
            this.maximum = maximum;
            this.ownerOverride = ownerOverride;
            this.lastProgressTick = level.getGameTime();
            LinkedHashMap<UUID, Source> indexed = new LinkedHashMap<>();
            entry.sources().forEach(value -> indexed.put(value.sourceId(), value));
            this.sources = Map.copyOf(indexed);
            this.reservations = entry.reservations();
        }

        static MaterialCourier spawn(ServerLevel level, UUID projectId, Entry entry,
                BlockPos3i staging, BlockPos3i start, DeploymentBoundingBox preparedBounds) {
            BlockPos startPosition = block(start);
            if (!canStand(level, startPosition)) {
                throw new IllegalStateException("material courier start is not standable");
            }
            ConstructionBotEntity entity = Objects.requireNonNull(
                    ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level),
                    "could not create material courier");
            entity.setRole(ConstructionBotEntity.Role.LOGISTICS);
            entity.moveTo(start.x() + 0.5D, start.y(), start.z() + 0.5D, 0, 0);
            entity.setCustomName(Component.literal("Steve Material Courier"));
            entity.setCustomNameVisible(true);
            entity.addTag(COURIER_TAG);
            entity.addTag(PROJECT_TAG + projectId.toString().replace("-", ""));
            if (!level.addFreshEntity(entity)) throw new IllegalStateException("could not spawn material courier");
            int minX = Math.min(preparedBounds.minimum().x(), staging.x());
            int minY = Math.min(preparedBounds.minimum().y(), staging.y());
            int minZ = Math.min(preparedBounds.minimum().z(), staging.z());
            int maxX = Math.max(preparedBounds.maximum().x(), staging.x());
            int maxY = Math.max(preparedBounds.maximum().y(), staging.y());
            int maxZ = Math.max(preparedBounds.maximum().z(), staging.z());
            for (Source source : entry.sources()) {
                minX = Math.min(minX, source.position().x() - 4);
                minY = Math.min(minY, source.position().y() - 2);
                minZ = Math.min(minZ, source.position().z() - 4);
                maxX = Math.max(maxX, source.position().x() + 4);
                maxY = Math.max(maxY, source.position().y() + 2);
                maxZ = Math.max(maxZ, source.position().z() + 4);
            }
            return new MaterialCourier(level, projectId, entry, staging, entity.getUUID(),
                    new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), null);
        }

        static MaterialCourier spawnForOwner(ServerLevel level, UUID projectId, Entry entry,
                BlockPos3i staging, BlockPos3i start, DeploymentBoundingBox preparedBounds,
                ServerPlayer owner) {
            MaterialCourier result = spawn(level, projectId, entry, staging, start, preparedBounds);
            return new MaterialCourier(level, projectId, entry, staging, result.entityId,
                    result.minimum, result.maximum, Objects.requireNonNull(owner, "owner"));
        }

        /** Retains this exact Bot for a reviewed industrial order's reload binding. */
        MaterialCourier retainAfterDelivery() {
            retainAfterDelivery = true;
            return this;
        }

        UUID entityId() { return entityId; }

        static MaterialCourier resume(ServerLevel level, UUID projectId, Entry entry,
                BlockPos3i staging, DeploymentBoundingBox preparedBounds) {
            List<ConstructionBotEntity> candidates = courierEntities(level, projectId);
            if (candidates.size() != 1) {
                throw new IllegalStateException("exactly one persisted material courier is required");
            }
            ConstructionBotEntity entity = candidates.get(0);
            int minX = Math.min(preparedBounds.minimum().x(), staging.x());
            int minY = Math.min(preparedBounds.minimum().y(), staging.y());
            int minZ = Math.min(preparedBounds.minimum().z(), staging.z());
            int maxX = Math.max(preparedBounds.maximum().x(), staging.x());
            int maxY = Math.max(preparedBounds.maximum().y(), staging.y());
            int maxZ = Math.max(preparedBounds.maximum().z(), staging.z());
            for (Source source : entry.sources()) {
                minX = Math.min(minX, source.position().x() - 4);
                minY = Math.min(minY, source.position().y() - 2);
                minZ = Math.min(minZ, source.position().z() - 4);
                maxX = Math.max(maxX, source.position().x() + 4);
                maxY = Math.max(maxY, source.position().y() + 2);
                maxZ = Math.max(maxZ, source.position().z() + 4);
            }
            MaterialCourier result = new MaterialCourier(level, projectId, entry, staging,
                    entity.getUUID(), new BlockPos(minX, minY, minZ),
                    new BlockPos(maxX, maxY, maxZ), null);
            for (Reservation reservation : entry.reservations()) {
                Transaction transaction = transaction(entry, reservation.reservationId());
                if (transaction == null) throw new IllegalStateException("courier transaction missing");
                if (transaction.state() == MaterialTransactionState.DELIVERED) {
                    result.index++;
                    continue;
                }
                if (transaction.state() == MaterialTransactionState.WITHDRAWN) {
                    ItemStack hand = entity.getItemBySlot(EquipmentSlot.MAINHAND);
                    String reservationTag = RESERVATION_TAG
                            + reservation.reservationId().toString().replace("-", "");
                    if (hand.isEmpty() || hand.getCount() != reservation.quantity()
                            || !identity(hand).equals(reservation.identity())
                            || !entity.getTags().contains(reservationTag)) {
                        throw new IllegalStateException("persisted courier hand evidence diverged");
                    }
                    result.carrying = true;
                    result.carried = hand.copy();
                    return result;
                }
                if (transaction.state() == MaterialTransactionState.PREPARED) return result;
                throw new IllegalStateException("courier cannot resume transaction "
                        + transaction.state());
            }
            return result;
        }

        static MaterialCourier resumeForOwner(ServerLevel level, UUID projectId, Entry entry,
                BlockPos3i staging, DeploymentBoundingBox preparedBounds, ServerPlayer owner) {
            MaterialCourier result = resume(level, projectId, entry, staging, preparedBounds);
            MaterialCourier owned = new MaterialCourier(level, projectId, entry, staging,
                    result.entityId, result.minimum, result.maximum,
                    Objects.requireNonNull(owner, "owner"));
            owned.index = result.index;
            owned.carrying = result.carrying;
            owned.carried = result.carried.copy();
            owned.lastProgressTick = result.lastProgressTick;
            return owned;
        }

        CourierTick tick() {
            if (failed) return CourierTick.FAILED;
            if (index >= reservations.size()) {
                if (!retainAfterDelivery) discard();
                return CourierTick.COMPLETED;
            }
            try {
                ConstructionBotEntity bot = entity();
                if (bot == null) {
                    missingEntityTicks++;
                    return missingEntityTicks > 5
                            ? fail("ENTITY_NOT_VISIBLE_AFTER_SPAWN_GRACE")
                            : CourierTick.PROGRESS;
                }
                missingEntityTicks = 0;
                Reservation reservation = reservations.get(index);
                Source source = Objects.requireNonNull(sources.get(reservation.sourceId()));
                BlockPos target = safeAccess(carrying ? block(staging) : block(source.position()));
                if (!atTarget(bot, target)) {
                    if (!move(bot, target)) {
                        repathFailures++;
                        resetPath();
                        failed = repathFailures > 3;
                        if (failed) failureCode = "PATH_UNAVAILABLE";
                        return failed ? CourierTick.FAILED : CourierTick.PROGRESS;
                    }
                    repathFailures = 0;
                    return CourierTick.PROGRESS;
                }
                if (!carrying) {
                    ServerPlayer owner = ownerOverride != null ? ownerOverride
                            : level.getServer().getPlayerList().getPlayer(entry.playerId());
                    if (owner == null || !sourceMatchesExpected(owner, source)) {
                        return fail("SOURCE_SNAPSHOT_CHANGED");
                    }
                    if (!(level.getBlockEntity(block(source.position())) instanceof Container container)) {
                        return fail("SOURCE_CONTAINER_MISSING");
                    }
                    // The courier walks there, which takes time, and the chest is the
                    // player's own the whole way. Failing here because the iron moved
                    // slot is the same wrong answer as everywhere else.
                    try {
                        carried = PlayerMaterialService.withdrawReserved(container, reservation);
                    } catch (IllegalStateException gone) {
                        return fail("RESERVED_MATERIAL_GONE");
                    }
                    carrying = true;
                    bot.setItemSlot(EquipmentSlot.MAINHAND, carried.copy());
                    bot.addTag(RESERVATION_TAG
                            + reservation.reservationId().toString().replace("-", ""));
                    persist(reservation.reservationId(), MaterialTransactionState.WITHDRAWN,
                            "BOT_WITHDREW_PLAYER_MATERIAL");
                    lastProgressTick = level.getGameTime();
                    resetPath();
                    return CourierTick.PROGRESS;
                }
                if (!(level.getBlockEntity(block(staging)) instanceof Container container)
                        || !insert(container, carried.copy())) {
                    return fail("STAGING_CONTAINER_FULL_OR_MISSING");
                }
                carried = ItemStack.EMPTY;
                carrying = false;
                bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                bot.removeTag(RESERVATION_TAG
                        + reservation.reservationId().toString().replace("-", ""));
                persist(reservation.reservationId(), MaterialTransactionState.DELIVERED,
                        "BOT_DELIVERED_PLAYER_MATERIAL");
                index++;
                lastProgressTick = level.getGameTime();
                resetPath();
                return CourierTick.PROGRESS;
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? "NO_MESSAGE"
                        : failure.getMessage().replaceAll("[^A-Za-z0-9 _:-]", "_")
                                .replace(' ', '_');
                if (message.length() > 80) message = message.substring(0, 80);
                return fail("EXCEPTION:" + failure.getClass().getSimpleName() + ":" + message);
            }
        }

        String failureCode() { return failureCode; }

        LogisticsDiagnosticSnapshot diagnosticSnapshot() {
            return new LogisticsDiagnosticSnapshot(reservations.size(), index,
                    lastProgressTick, level.getGameTime(), failed || repathFailures > 3,
                    carrying);
        }

        private CourierTick fail(String code) {
            failed = true;
            failureCode = code;
            return CourierTick.FAILED;
        }

        boolean cancelAndReturn() {
            try {
                Map<UUID, MaterialTransactionState> states = new LinkedHashMap<>();
                entry.transactions().forEach(value -> states.put(value.reservationId(), value.state()));
                Reservation carriedReservation = carrying && index < reservations.size()
                        ? reservations.get(index) : null;
                for (Reservation reservation : reservations) {
                    MaterialTransactionState state = states.get(reservation.reservationId());
                    if (state != MaterialTransactionState.WITHDRAWN
                            && state != MaterialTransactionState.DELIVERED
                            && state != MaterialTransactionState.RETURN_PENDING) continue;
                    Source source = sources.get(reservation.sourceId());
                    if (!(level.getBlockEntity(block(source.position())) instanceof Container container)) return false;
                    ItemStack current = container.getItem(reservation.slot());
                    int capacity = current.isEmpty() ? item(reservation.identity().itemId()).getMaxStackSize()
                            : identity(current).equals(reservation.identity())
                                    ? current.getMaxStackSize() - current.getCount() : 0;
                    if (capacity < reservation.quantity()) return false;
                    if (state == MaterialTransactionState.WITHDRAWN
                            && (carriedReservation == null
                                    || !carriedReservation.reservationId().equals(reservation.reservationId())
                                    || carried.getCount() != reservation.quantity()
                                    || !identity(carried).equals(reservation.identity()))) return false;
                }
                for (Reservation reservation : reservations) {
                    MaterialTransactionState state = states.get(reservation.reservationId());
                    if (state == MaterialTransactionState.PREPARED) {
                        persist(reservation.reservationId(), MaterialTransactionState.RELEASED,
                                "MATERIAL_RESERVATION_RELEASED");
                        continue;
                    }
                    if (state != MaterialTransactionState.WITHDRAWN
                            && state != MaterialTransactionState.DELIVERED
                            && state != MaterialTransactionState.RETURN_PENDING) continue;
                    ItemStack returned = state == MaterialTransactionState.WITHDRAWN
                            ? carried.copy() : removeExact((Container) level.getBlockEntity(block(staging)),
                                    reservation.identity(), reservation.quantity());
                    Source source = sources.get(reservation.sourceId());
                    Container container = (Container) level.getBlockEntity(block(source.position()));
                    ItemStack current = container.getItem(reservation.slot());
                    if (current.isEmpty()) container.setItem(reservation.slot(), returned);
                    else current.grow(returned.getCount());
                    container.setChanged();
                    if (state == MaterialTransactionState.WITHDRAWN) {
                        carried = ItemStack.EMPTY; carrying = false;
                    }
                    persist(reservation.reservationId(), MaterialTransactionState.RETURNED,
                            "MATERIAL_RETURNED_TO_PLAYER_SOURCE");
                }
                discard();
                return true;
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private void persist(UUID reservationId, MaterialTransactionState state, String code) {
            entry = advanceTransaction(entry, reservationId, state, level.getGameTime(), code);
            PlayerMaterialSavedData.forLevel(level).put(entry);
            level.getServer().overworld().getDataStorage().save();
        }

        private boolean insert(Container container, ItemStack stack) {
            for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack current = container.getItem(slot);
                if (!current.isEmpty() && identity(current).equals(identity(stack))) {
                    int move = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());
                    if (move > 0) { current.grow(move); stack.shrink(move); }
                }
            }
            for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                if (container.getItem(slot).isEmpty()) {
                    int move = Math.min(stack.getCount(), stack.getMaxStackSize());
                    ItemStack placed = stack.copy(); placed.setCount(move);
                    container.setItem(slot, placed); stack.shrink(move);
                }
            }
            container.setChanged();
            return stack.isEmpty();
        }

        private boolean sourceMatchesExpected(ServerPlayer owner, Source source) {
            Source actual = PlayerMaterialService.scan(owner, source.position(),
                    PlayerMaterialService.direction(source.accessFace()), source.priority());
            if (actual == null || !source.position().equals(actual.position())
                    || !source.blockEntityType().equals(actual.blockEntityType())
                    || !source.blockStateHash().equals(actual.blockStateHash())) return false;
            // The courier's own withdrawals, discounted before comparing. This bookkeeping
            // was always here and always right; what was wrong is that it counted by slot,
            // and so did the comparison beneath it. A courier that took eight iron from
            // slot three expects slot three to be eight lighter — which is a statement
            // about an arrangement, and a player who tidied the chest in the meantime
            // makes it false without having taken anything.
            Map<MaterialIdentity, Long> withdrawnAlready = new LinkedHashMap<>();
            for (int prior = 0; prior < index; prior++) {
                Reservation reservation = reservations.get(prior);
                if (reservation.sourceId().equals(source.sourceId())) {
                    withdrawnAlready.merge(reservation.identity(), reservation.quantity(),
                            Math::addExact);
                }
            }
            Map<MaterialIdentity, Long> expected = new LinkedHashMap<>();
            source.slots().forEach(slot ->
                    expected.merge(slot.identity(), slot.quantity(), Math::addExact));
            for (var taken : withdrawnAlready.entrySet()) {
                long remaining = expected.getOrDefault(taken.getKey(), 0L) - taken.getValue();
                // More taken than was ever bound is a broken ledger, not a tidy chest.
                if (remaining < 0) return false;
                expected.put(taken.getKey(), remaining);
            }
            Map<MaterialIdentity, Long> held = new LinkedHashMap<>();
            actual.slots().forEach(slot ->
                    held.merge(slot.identity(), slot.quantity(), Math::addExact));
            return expected.entrySet().stream().allMatch(entry ->
                    held.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
        }

        private BlockPos safeAccess(BlockPos work) {
            for (int radius = 1; radius <= 4; radius++) {
                for (BlockPos candidate : List.of(work.offset(radius, 0, 0), work.offset(-radius, 0, 0),
                        work.offset(0, 0, radius), work.offset(0, 0, -radius))) {
                    if (inside(candidate) && canStand(level, candidate)) return candidate;
                }
            }
            throw new IllegalStateException("no safe courier access cell");
        }

        private boolean move(ConstructionBotEntity bot, BlockPos target) {
            BlockPos current = bot.blockPosition();
            if (!target.equals(pathTarget) || path.isEmpty()) {
                pathTarget = target.immutable();
                path = planPath(current, target);
                if (path.isEmpty() && !current.equals(target)) return false;
            }
            if (path.isEmpty()) return true;
            BlockPos next = path.get(0);
            // During a stair transition the entity is intentionally between two
            // integer stand cells.  Recomputing adjacency from blockPosition() here
            // would see that in-flight position as a third, unsupported node and
            // falsely fail the route.  The path was adjacency-checked when planned;
            // this tick only revalidates its bounded destination before advancing.
            if (!inside(next) || !canStand(level, next)) { path = List.of(); return false; }
            if (bot.advanceToward(next)) {
                path = List.copyOf(path.subList(1, path.size()));
                lastProgressTick = level.getGameTime();
            }
            return true;
        }

        private List<BlockPos> planPath(BlockPos start, BlockPos target) {
            if (!inside(start) || !inside(target) || !canStand(level, target)) return List.of();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            Map<BlockPos, BlockPos> parents = new LinkedHashMap<>();
            queue.add(start.immutable()); parents.put(start.immutable(), null);
            while (!queue.isEmpty() && parents.size() <= MAX_PATH_VISITS) {
                BlockPos current = queue.removeFirst();
                if (current.equals(target)) break;
                for (BlockPos next : BoundedBotNavigation.neighbours(level, current)) {
                    next = next.immutable();
                    if (!parents.containsKey(next) && inside(next) && canStand(level, next)) {
                        parents.put(next, current); queue.addLast(next);
                    }
                }
            }
            if (!parents.containsKey(target)) return List.of();
            ArrayList<BlockPos> reversed = new ArrayList<>();
            for (BlockPos cursor = target; !cursor.equals(start); cursor = parents.get(cursor)) {
                if (cursor == null) return List.of();
                reversed.add(cursor);
            }
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed);
        }

        private boolean inside(BlockPos value) {
            return value.getX() >= minimum.getX() && value.getX() <= maximum.getX()
                    && value.getY() >= minimum.getY() && value.getY() <= maximum.getY()
                    && value.getZ() >= minimum.getZ() && value.getZ() <= maximum.getZ();
        }

        private boolean atTarget(ConstructionBotEntity bot, BlockPos target) {
            double dx = bot.getX() - (target.getX() + 0.5D);
            double dy = bot.getY() - target.getY();
            double dz = bot.getZ() - (target.getZ() + 0.5D);
            return dx * dx + dy * dy + dz * dz < 1.0E-6D;
        }

        private ConstructionBotEntity entity() {
            Entity value = level.getEntity(entityId);
            if (value == null) {
                for (Entity candidate : level.getAllEntities()) {
                    if (candidate.getUUID().equals(entityId)) {
                        value = candidate;
                        break;
                    }
                }
            }
            return value instanceof ConstructionBotEntity bot && bot.isAlive()
                    && bot.getTags().contains(COURIER_TAG) ? bot : null;
        }

        private void resetPath() { path = List.of(); pathTarget = null; }

        private void discard() {
            Entity value = level.getEntity(entityId);
            if (value != null && value.getTags().contains(COURIER_TAG)) value.discard();
        }
    }
}
