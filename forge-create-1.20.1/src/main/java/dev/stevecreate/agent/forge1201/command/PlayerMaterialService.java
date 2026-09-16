package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialAllocation;
import dev.stevecreate.agent.core.execution.construction.MaterialRequirement;
import dev.stevecreate.agent.core.execution.construction.MaterialRequirementPlan;
import dev.stevecreate.agent.core.execution.construction.MaterialSlotSnapshot;
import dev.stevecreate.agent.core.execution.construction.MaterialSourceBinding;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLedger;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Slot;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerSalvageSavedData;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** Server-only player material-source selection, exact scanning and reservation bridge. */
public final class PlayerMaterialService {
    public static final int MAX_SOURCES = 8;
    public static final int MAX_SELECTION_DISTANCE_SQUARED = 16 * 16;
    public static final long SOURCE_TTL_MILLIS = 10 * 60 * 1_000L;
    private static final java.util.Set<String> SUPPORTED_CONTAINER_TYPES = java.util.Set.of(
            "minecraft:chest", "minecraft:barrel");

    private PlayerMaterialService() {}

    /** Opens a non-Create material project while preserving the frozen player-ledger protocol. */
    public static SelectionResult openStandalonePlan(
            ServerPlayer player,
            UUID projectId,
            Map<ResourceId, Long> requirements,
            String planHash,
            String runtimeFingerprint) {
        if (!player.serverLevel().getServer().isSameThread()) {
            return SelectionResult.failure("SERVER_THREAD_REQUIRED");
        }
        if (requirements == null || requirements.isEmpty() || requirements.size() > 128
                || requirements.entrySet().stream().anyMatch(value ->
                        value.getKey() == null || value.getValue() == null
                                || value.getValue() < 1 || value.getValue() > 1_000_000L)
                || planHash == null || !planHash.matches("[0-9a-f]{64}")
                || runtimeFingerprint == null || runtimeFingerprint.isBlank()
                || runtimeFingerprint.length() > 1_024) {
            return SelectionResult.failure("MATERIAL_PLAN_INVALID");
        }
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry existing = data.entry(projectId).orElse(null);
        if (existing != null) {
            if (!existing.playerId().equals(player.getUUID())) {
                return SelectionResult.failure("PROJECT_OWNER_MISMATCH");
            }
            if (!existing.dimension().equals(ResourceId.parse(
                    player.serverLevel().dimension().location().toString()))
                    || !existing.requirements().equals(requirements)
                    || !existing.planHash().equals(planHash)
                    || !existing.runtimeFingerprint().equals(runtimeFingerprint)) {
                return SelectionResult.failure("MATERIAL_PLAN_CHANGED");
            }
            return success(existing);
        }
        long now = Instant.now().toEpochMilli();
        Entry opened = new Entry(projectId, player.getUUID(), ResourceId.parse(
                player.serverLevel().dimension().location().toString()), requirements,
                planHash, runtimeFingerprint, List.of(), List.of(), List.of(), List.of(),
                null, false, now, now, "MATERIAL_SOURCE_SELECTION_REQUIRED", null);
        data.put(opened);
        return success(opened);
    }

    /** Adds or refreshes an exact server-scanned source for a standalone industrial order. */
    public static SelectionResult selectStandaloneSource(
            ServerPlayer player, UUID projectId, BlockPos3i position, Direction face) {
        if (!player.serverLevel().getServer().isSameThread()) {
            return SelectionResult.failure("SERVER_THREAD_REQUIRED");
        }
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry entry = data.entry(projectId).orElse(null);
        if (entry == null) return SelectionResult.failure("MATERIAL_PLAN_NOT_READY");
        if (!entry.playerId().equals(player.getUUID())) {
            return SelectionResult.failure("PROJECT_OWNER_MISMATCH");
        }
        if (!entry.dimension().equals(ResourceId.parse(
                player.serverLevel().dimension().location().toString()))) {
            return SelectionResult.failure("PROJECT_DIMENSION_MISMATCH");
        }
        if (!entry.reservations().isEmpty() || !entry.transactions().isEmpty()) {
            return SelectionResult.failure("MATERIAL_SOURCE_SELECTION_FROZEN");
        }
        if (!player.mayBuild()) return SelectionResult.failure("MATERIAL_SOURCE_PERMISSION_DENIED");
        if (player.distanceToSqr(position.x() + 0.5D, position.y() + 0.5D,
                position.z() + 0.5D) > MAX_SELECTION_DISTANCE_SQUARED) {
            return SelectionResult.failure("MATERIAL_SOURCE_OUT_OF_RANGE");
        }
        Source scanned = scan(player, position, face, entry.sources().size());
        if (scanned == null) return SelectionResult.failure("MATERIAL_SOURCE_UNAVAILABLE");
        ArrayList<Source> sources = new ArrayList<>(entry.sources());
        int existingIndex = -1;
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).position().equals(position)) {
                existingIndex = index;
                break;
            }
        }
        if (existingIndex >= 0) {
            Source prior = sources.get(existingIndex);
            sources.set(existingIndex, withIdentityAndPriority(
                    scanned, prior.sourceId(), prior.priority()));
        } else {
            if (sources.size() >= MAX_SOURCES) {
                return SelectionResult.failure("MATERIAL_SOURCE_LIMIT_REACHED");
            }
            sources.add(scanned);
        }
        Entry updated = entry.withSources(sources, Instant.now().toEpochMilli(),
                "MATERIAL_SOURCE_SELECTED");
        data.put(updated);
        return success(updated);
    }

    /** Atomically reserves exact slots across the selected standalone sources. */
    public static SelectionResult confirmStandalone(
            ServerPlayer player, UUID projectId, String worldIdentity) {
        if (!player.serverLevel().getServer().isSameThread()) {
            return SelectionResult.failure("SERVER_THREAD_REQUIRED");
        }
        if (worldIdentity == null || worldIdentity.isBlank() || worldIdentity.length() > 1_024) {
            return SelectionResult.failure("WORLD_NOT_AUTHORIZED");
        }
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry entry = data.entry(projectId).orElse(null);
        if (entry == null) return SelectionResult.failure("MATERIAL_PLAN_NOT_READY");
        if (!entry.playerId().equals(player.getUUID())) {
            return SelectionResult.failure("PROJECT_OWNER_MISMATCH");
        }
        if (!entry.reservations().isEmpty()) return success(entry);
        if (entry.sources().isEmpty()) return SelectionResult.failure("MATERIAL_SOURCE_REQUIRED");
        ArrayList<Source> rescanned = new ArrayList<>();
        for (Source bound : entry.sources()) {
            Source current = scan(player, bound.position(), direction(bound.accessFace()),
                    bound.priority());
            if (current == null || !sameSnapshot(bound, current)) {
                return SelectionResult.failure("MATERIAL_SOURCE_CHANGED");
            }
            rescanned.add(withIdentityAndPriority(current, bound.sourceId(), bound.priority()));
        }
        long now = Instant.now().toEpochMilli();
        PlayerWarehouseProjection.Projection warehouse = PlayerWarehouseProjection.reserve(
                entry, rescanned, data.entries().values(), worldIdentity, now);
        if (!warehouse.success()) return SelectionResult.failure(warehouse.statusCode());
        ArrayList<Reservation> reservations = allocate(entry.projectId(), entry.dimension(),
                entry.requirements(), rescanned, data.entries().values());
        long reserved = reservations.stream().mapToLong(Reservation::quantity).sum();
        long required = entry.requirements().values().stream().mapToLong(Long::longValue).sum();
        if (reserved != required) return SelectionResult.failure("MATERIALS_INSUFFICIENT");
        Map<String, Long> physicalTotals = new LinkedHashMap<>();
        reservations.forEach(value -> {
            ResourceId endpoint = new ResourceId("warehouse", "endpoint_"
                    + value.sourceId().toString().replace("-", ""));
            physicalTotals.merge(endpoint + "|" + value.identity().itemId()
                            + "|" + value.identity().payloadSha256(),
                    value.quantity(), Math::addExact);
        });
        if (!physicalTotals.equals(warehouse.endpointResourceTotals())) {
            return SelectionResult.failure("WAREHOUSE_RESERVATION_DIVERGED");
        }
        Entry updated = new Entry(entry.projectId(), entry.playerId(), entry.dimension(),
                entry.requirements(), entry.planHash(), entry.runtimeFingerprint(), rescanned,
                reservations, entry.transactions(), entry.journal(), entry.logistics(), false,
                entry.createdAt(), now, "MATERIALS_RESERVED", entry.report());
        data.put(updated);
        return success(updated);
    }

    public static SelectionResult ensureSelection(
            ServerPlayer player, PlayerWorkflowSavedData.ProjectEntry project) {
        if (project == null || project.stage() != WorkflowStage.MATERIAL_SOURCE_SELECTION) {
            return SelectionResult.failure("PROJECT_STAGE_MISMATCH");
        }
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry existing = data.entry(project.projectId()).orElse(null);
        if (existing == null) {
            SitePreparationCommand.PreparedExecutionContext prepared =
                    SitePreparationCommand.preparedExecutionContext(player).orElse(null);
            if (prepared == null) return SelectionResult.failure("PREPARED_SITE_NOT_CURRENT");
            PlayerVerifiedMaterialPlanResolver.Resolution resolution =
                    PlayerVerifiedMaterialPlanResolver.resolve(player, project, prepared);
            if (!(resolution instanceof PlayerVerifiedMaterialPlanResolver.Ready ready)) {
                PlayerVerifiedMaterialPlanResolver.Refused refused =
                        (PlayerVerifiedMaterialPlanResolver.Refused) resolution;
                return SelectionResult.failure(refused.code(), refused.detail());
            }
            Map<ResourceId, Long> requirements = ready.materialPlan().legacyRequirementTotals();
            long now = Instant.now().toEpochMilli();
            existing = new Entry(project.projectId(), project.playerId(), project.dimension(),
                    requirements, ready.materialPlan().planSha256(),
                    ready.materialPlan().runtimeFingerprint(),
                    List.of(), List.of(), List.of(), List.of(), null, false, now, now,
                    "MATERIAL_SOURCE_SELECTION_REQUIRED", null);
            data.put(existing);
        }
        SitePreparationCommand.PreparedExecutionContext prepared =
                SitePreparationCommand.preparedExecutionContext(player).orElse(null);
        if (prepared == null) return SelectionResult.failure("PREPARED_SITE_NOT_CURRENT");
        IndustrialPlayerOrderService.SyncResult order = IndustrialPlayerOrderService.ensurePlan(
                player, project, existing, prepared.prepared().worldIdentity());
        if (!order.success()) return SelectionResult.failure(order.code());
        return success(existing);
    }

    public static SelectionResult selectSource(
            ServerPlayer player,
            UUID projectId,
            long projectNonce,
            BlockPos3i position,
            Direction face) {
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = workflow.entry(player.getUUID()).orElse(null);
        String invalid = validate(project, projectId, projectNonce, WorkflowStage.MATERIAL_SOURCE_SELECTION);
        if (invalid != null) return SelectionResult.failure(invalid);
        SelectionResult ensured = ensureSelection(player, project);
        if (!ensured.success()) return ensured;
        if (!player.mayBuild()) return SelectionResult.failure("MATERIAL_SOURCE_PERMISSION_DENIED");
        if (player.distanceToSqr(position.x() + 0.5D, position.y() + 0.5D,
                position.z() + 0.5D) > MAX_SELECTION_DISTANCE_SQUARED) {
            return SelectionResult.failure("MATERIAL_SOURCE_OUT_OF_RANGE");
        }
        SitePreparationCommand.PreparedExecutionContext context =
                SitePreparationCommand.preparedExecutionContext(player).orElse(null);
        if (context == null) return SelectionResult.failure("PREPARED_SITE_NOT_CURRENT");
        if (!withinLogisticsRange(context.selection().authorizedBounds(), position, 16)) {
            return SelectionResult.failure("MATERIAL_SOURCE_OUTSIDE_LOGISTICS_REGION");
        }
        Source scanned = scan(player, position, face, ensured.entry().sources().size());
        if (scanned == null) return SelectionResult.failure("MATERIAL_SOURCE_UNAVAILABLE");
        ArrayList<Source> sources = new ArrayList<>(ensured.entry().sources());
        int existingIndex = -1;
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).position().equals(position)) { existingIndex = index; break; }
        }
        if (existingIndex >= 0) {
            Source prior = sources.get(existingIndex);
            sources.set(existingIndex, withIdentityAndPriority(scanned, prior.sourceId(), prior.priority()));
        } else {
            if (sources.size() >= MAX_SOURCES) return SelectionResult.failure("MATERIAL_SOURCE_LIMIT_REACHED");
            sources.add(scanned);
        }
        long now = Instant.now().toEpochMilli();
        Entry updated = ensured.entry().withSources(sources, now, "MATERIAL_SOURCE_SELECTED");
        PlayerMaterialSavedData.forLevel(player.serverLevel()).put(updated);
        PlayerWorkflowSavedData.ProjectEntry nextProject = project.withStage(
                WorkflowStage.MATERIAL_SOURCE_SELECTION, PlayerWorkflowService.nextNonce(), now,
                "MATERIAL_SOURCE_SELECTED");
        workflow.put(nextProject);
        return success(updated, nextProject);
    }

    public static SelectionResult resetSources(
            ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = workflow.entry(player.getUUID()).orElse(null);
        String invalid = validate(project, projectId, projectNonce, WorkflowStage.MATERIAL_SOURCE_SELECTION);
        if (invalid != null) return SelectionResult.failure(invalid);
        Entry entry = PlayerMaterialSavedData.forLevel(player.serverLevel()).entry(projectId).orElse(null);
        if (entry == null) return SelectionResult.failure("MATERIAL_PLAN_NOT_READY");
        long now = Instant.now().toEpochMilli();
        Entry updated = entry.withSources(List.of(), now, "MATERIAL_SOURCES_RESET");
        PlayerMaterialSavedData.forLevel(player.serverLevel()).put(updated);
        PlayerWorkflowSavedData.ProjectEntry next = project.withStage(WorkflowStage.MATERIAL_SOURCE_SELECTION,
                PlayerWorkflowService.nextNonce(), now, "MATERIAL_SOURCES_RESET");
        workflow.put(next);
        return success(updated, next);
    }

    public static SelectionResult confirm(
            ServerPlayer player, UUID projectId, long projectNonce, boolean allowSalvage) {
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = workflow.entry(player.getUUID()).orElse(null);
        String invalid = validate(project, projectId, projectNonce, WorkflowStage.MATERIAL_SOURCE_SELECTION);
        if (invalid != null) return SelectionResult.failure(invalid);
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(player.serverLevel());
        Entry entry = data.entry(projectId).orElse(null);
        if (entry == null || entry.sources().isEmpty()) return SelectionResult.failure("MATERIAL_SOURCE_REQUIRED");
        ArrayList<Source> rescanned = new ArrayList<>();
        for (Source bound : entry.sources()) {
            Source current = scan(player, bound.position(), direction(bound.accessFace()), bound.priority());
            if (current == null || !sameSnapshot(bound, current)) {
                return SelectionResult.failure("MATERIAL_SOURCE_CHANGED");
            }
            rescanned.add(withIdentityAndPriority(current, bound.sourceId(), bound.priority()));
        }
        if (allowSalvage) {
            if (rescanned.size() >= MAX_SOURCES) {
                return SelectionResult.failure("MATERIAL_SOURCE_LIMIT_REACHED");
            }
            Source salvage = projectSalvageSource(player, projectId, rescanned.size());
            if (salvage != null && rescanned.stream().noneMatch(value ->
                    value.position().equals(salvage.position()))) rescanned.add(salvage);
        }
        SitePreparationCommand.PreparedExecutionContext prepared =
                SitePreparationCommand.preparedExecutionContext(player).orElse(null);
        if (prepared == null) {
            return SelectionResult.failure("PREPARED_SITE_NOT_CURRENT");
        }
        long now = Instant.now().toEpochMilli();
        PlayerWarehouseProjection.Projection warehouse = PlayerWarehouseProjection.reserve(
                entry, rescanned, data.entries().values(),
                prepared.prepared().worldIdentity(), now);
        if (!warehouse.success()) {
            return SelectionResult.failure(warehouse.statusCode());
        }
        ArrayList<Reservation> reservations = allocate(entry.projectId(), entry.dimension(),
                entry.requirements(), rescanned, data.entries().values());
        long reserved = reservations.stream().mapToLong(Reservation::quantity).sum();
        long required = entry.requirements().values().stream().mapToLong(Long::longValue).sum();
        if (reserved != required) return SelectionResult.failure("MATERIALS_INSUFFICIENT");
        Map<String, Long> physicalTotals = new LinkedHashMap<>();
        reservations.forEach(value -> {
            ResourceId endpoint = new ResourceId("warehouse", "endpoint_"
                    + value.sourceId().toString().replace("-", ""));
            physicalTotals.merge(endpoint + "|" + value.identity().itemId()
                            + "|" + value.identity().payloadSha256(),
                    value.quantity(), Math::addExact);
        });
        if (!physicalTotals.equals(warehouse.endpointResourceTotals())) {
            return SelectionResult.failure("WAREHOUSE_RESERVATION_DIVERGED");
        }
        Entry updated = new Entry(entry.projectId(), entry.playerId(), entry.dimension(),
                entry.requirements(), entry.planHash(), entry.runtimeFingerprint(), rescanned,
                reservations, entry.transactions(), entry.journal(), entry.logistics(), allowSalvage,
                entry.createdAt(), now,
                "MATERIALS_RESERVED", entry.report());
        data.put(updated);
        PlayerWorkflowSavedData.ProjectEntry next = project.withStage(
                WorkflowStage.MATERIAL_RESERVED, PlayerWorkflowService.nextNonce(), now,
                "MATERIALS_RESERVED");
        workflow.put(next);
        IndustrialPlayerOrderService.SyncResult order = IndustrialPlayerOrderService.checkpoint(
                player, projectId, dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase.READY,
                "MATERIALS_RESERVED", Set.of(ResourceId.parse("steve_industrial:materials_reserved")));
        if (!order.success()) return SelectionResult.failure(order.code());
        return success(updated, next);
    }

    public static SelectionResult snapshot(ServerPlayer player, UUID projectId) {
        PlayerWorkflowSavedData.ProjectEntry project = PlayerWorkflowSavedData.forLevel(
                player.serverLevel()).entry(player.getUUID()).orElse(null);
        if (project == null || !project.projectId().equals(projectId)) {
            return SelectionResult.failure("PROJECT_NOT_FOUND");
        }
        if (project.stage() != WorkflowStage.MATERIAL_SOURCE_SELECTION
                && project.stage() != WorkflowStage.MATERIAL_RESERVED
                && project.stage() != WorkflowStage.CONSTRUCTION
                && project.stage() != WorkflowStage.PAUSED
                && project.stage() != WorkflowStage.COMPLETED) {
            return SelectionResult.failure("PROJECT_STAGE_MISMATCH");
        }
        Entry entry = PlayerMaterialSavedData.forLevel(player.serverLevel()).entry(projectId).orElse(null);
        if (entry == null && project.stage() == WorkflowStage.MATERIAL_SOURCE_SELECTION) {
            return ensureSelection(player, project);
        }
        return entry == null ? SelectionResult.failure("MATERIAL_PLAN_NOT_READY")
                : success(entry, project);
    }

    public static SelectionResult cancel(ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData workflow = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = workflow.entry(player.getUUID()).orElse(null);
        if (project == null || !project.projectId().equals(projectId)) {
            return SelectionResult.failure("PROJECT_NOT_FOUND");
        }
        if (project.nonce() != projectNonce) return SelectionResult.failure("STALE_PROJECT_REQUEST");
        if (project.stage() != WorkflowStage.MATERIAL_SOURCE_SELECTION
                && project.stage() != WorkflowStage.MATERIAL_RESERVED) {
            return SelectionResult.failure("PROJECT_STAGE_MISMATCH");
        }
        Entry entry = PlayerMaterialSavedData.forLevel(player.serverLevel()).entry(projectId).orElse(null);
        if (entry != null && entry.transactions().stream().anyMatch(value ->
                value.state() != dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.PREPARED
                        && value.state() != dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.RELEASED
                        && value.state() != dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.RETURNED)) {
            PlayerWorkflowSavedData.ProjectEntry paused = project.withStage(
                    WorkflowStage.PAUSED, PlayerWorkflowService.nextNonce(),
                    Instant.now().toEpochMilli(), "MATERIAL_RECONCILIATION_REQUIRED");
            workflow.put(paused);
            PlayerConstructionService.Result recovered = PlayerConstructionService.recoverCancel(
                    player, projectId, paused.nonce());
            if (!recovered.success() || recovered.entry() == null) {
                return SelectionResult.failure(recovered.statusCode());
            }
            return new SelectionResult(true, "OK", recovered.entry(), recovered.project(),
                    summary(recovered.entry()));
        }
        long now = Instant.now().toEpochMilli();
        PlayerWorkflowSavedData.ProjectEntry next = project.withStage(WorkflowStage.CANCELLED,
                PlayerWorkflowService.nextNonce(), now, "CANCELLED_BEFORE_MATERIAL_WITHDRAWAL");
        workflow.put(next);
        PlayerMaterialSavedData.forLevel(player.serverLevel()).remove(projectId);
        SitePreparationCommand.releasePlayerWorkflow(player, projectId);
        IndustrialPlayerOrderService.cancel(player, projectId, true);
        return new SelectionResult(true, "OK", entry, next,
                entry == null ? null : summary(entry));
    }

    private static ArrayList<Reservation> allocate(UUID projectId, ResourceId dimension,
            Map<ResourceId, Long> requirements, List<Source> sources,
            java.util.Collection<Entry> projects) {
        ArrayList<Reservation> result = new ArrayList<>();
        long now = Instant.now().toEpochMilli();
        ProjectMaterialLedger ledger = ledgerFor(projectId, dimension, requirements, sources,
                projects, now);
        List<Source> ordered = sources.stream().sorted(Comparator.comparingInt(Source::priority)
                .thenComparing(value -> value.sourceId().toString())).toList();
        Map<String, Long> committed = new LinkedHashMap<>();
        for (Entry other : projects) {
            if (other.projectId().equals(projectId) || !other.dimension().equals(dimension)) continue;
            Map<UUID, Source> otherSources = other.sources().stream().collect(
                    java.util.stream.Collectors.toMap(Source::sourceId, value -> value));
            for (Reservation reservation : other.reservations()) {
                boolean stillPhysical = reservation.expiresAt() > now
                        && other.transactions().stream()
                                .filter(value -> value.reservationId().equals(reservation.reservationId()))
                                .allMatch(value -> value.state()
                                        == dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.PREPARED);
                if (!stillPhysical) continue;
                Source source = otherSources.get(reservation.sourceId());
                if (source != null) committed.merge(physicalSlotKey(
                        source.position(), reservation.slot(), reservation.identity()),
                        reservation.quantity(), Math::addExact);
            }
        }
        for (Map.Entry<ResourceId, Long> requirement : requirements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString))).toList()) {
            long remaining = requirement.getValue();
            for (Source source : ordered) {
                if (remaining == 0) break;
                long sourceUsed = result.stream().filter(value -> value.sourceId().equals(source.sourceId()))
                        .mapToLong(Reservation::quantity).sum();
                long sourceRemaining = Math.max(0, source.maximumWithdrawal() - sourceUsed);
                if (sourceRemaining == 0) continue;
                for (Slot slot : source.slots()) {
                    if (remaining == 0 || sourceRemaining == 0) break;
                    if (!slot.identity().itemId().equals(requirement.getKey())
                            || !slot.identity().payloadSha256().equals(MaterialIdentity.EMPTY_PAYLOAD_SHA256)) continue;
                    String key = physicalSlotKey(source.position(), slot.slot(), slot.identity());
                    long available = Math.max(0, slot.quantity() - committed.getOrDefault(key, 0L));
                    long take = Math.min(Math.min(available, remaining), sourceRemaining);
                    if (take == 0) continue;
                    UUID reservationId = UUID.randomUUID();
                    ledger.reserve(new MaterialAllocation(resource("allocation", reservationId),
                            resource("project", projectId), requirement(requirement.getKey()),
                            resource("source", source.sourceId()), slot.slot(), slot.identity(), take), now);
                    result.add(new Reservation(reservationId, requirement.getKey(), source.sourceId(),
                            slot.slot(), slot.identity(), take, now + SOURCE_TTL_MILLIS));
                    committed.merge(key, take, Math::addExact);
                    remaining -= take;
                    sourceRemaining -= take;
                }
            }
        }
        return result;
    }

    private static ProjectMaterialLedger ledgerFor(UUID projectId, ResourceId dimension,
            Map<ResourceId, Long> requirements, List<Source> sources,
            java.util.Collection<Entry> projects, long now) {
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        for (Entry entry : projects) {
            if (entry.projectId().equals(projectId) || !entry.dimension().equals(dimension)) continue;
            List<Reservation> physical = entry.reservations().stream().filter(reservation ->
                    reservation.expiresAt() > now && entry.transactions().stream()
                            .filter(value -> value.reservationId().equals(reservation.reservationId()))
                            .allMatch(value -> value.state()
                                    == dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.PREPARED))
                    .toList();
            if (physical.isEmpty()) continue;
            registerPlan(ledger, entry.projectId(), entry.requirements(), entry.planHash(),
                    entry.runtimeFingerprint());
            for (Source source : entry.sources()) bind(ledger, entry, source, now);
            for (Reservation reservation : physical) {
                ledger.reserve(new MaterialAllocation(resource("allocation", reservation.reservationId()),
                        resource("project", entry.projectId()), requirement(reservation.requirement()),
                        resource("source", reservation.sourceId()), reservation.slot(),
                        reservation.identity(), reservation.quantity()), now);
            }
        }
        registerPlan(ledger, projectId, requirements, "0".repeat(64), "player-live-reservation");
        Entry current = new Entry(projectId, projectId, dimension, requirements, "0".repeat(64),
                "player-live-reservation", sources, List.of(), List.of(), List.of(), null, false,
                now, now, "RESERVATION_PLANNING", null);
        for (Source source : sources) bind(ledger, current, source, now);
        return ledger;
    }

    private static void registerPlan(ProjectMaterialLedger ledger, UUID projectId,
            Map<ResourceId, Long> requirements, String planHash, String runtime) {
        List<MaterialRequirement> rows = requirements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .map(value -> new MaterialRequirement(requirement(value.getKey()),
                        new ResourceId("material", "requirement/" + value.getKey().namespace() + "/"
                                + value.getKey().path()), 0, RecipeIngredientKind.EXACT_RESOURCE,
                        "exact:" + value.getKey(), List.of(value.getKey()), value.getValue()))
                .toList();
        ledger.registerPlan(new MaterialRequirementPlan(resource("plan", projectId),
                resource("project", projectId), resource("target", projectId), 1,
                runtime, planHash, rows));
    }

    private static void bind(ProjectMaterialLedger ledger, Entry entry, Source source, long now) {
        List<MaterialSlotSnapshot> slots = source.slots().stream()
                .map(value -> new MaterialSlotSnapshot(value.slot(), value.identity(), value.quantity()))
                .toList();
        ledger.bindSource(new MaterialSourceBinding(resource("source", source.sourceId()),
                resource("project", entry.projectId()), entry.playerId().toString(), entry.dimension(),
                source.position(), source.accessFace(), source.blockEntityType(), source.blockStateHash(),
                source.inventoryHash(), source.priority(), source.maximumWithdrawal(), source.boundAt(),
                source.expiresAt(), slots), now);
    }

    private static ResourceId requirement(ResourceId item) {
        return new ResourceId("material", "requirement/" + item.namespace() + "/" + item.path());
    }

    private static ResourceId resource(String kind, UUID value) {
        return new ResourceId("material", kind + "/" + value.toString().replace("-", ""));
    }

    private static String physicalSlotKey(BlockPos3i position, int slot, MaterialIdentity identity) {
        return position.x() + "," + position.y() + "," + position.z() + "|" + slot + "|"
                + identity.itemId() + "|" + identity.payloadSha256();
    }

    static Source scan(ServerPlayer player, BlockPos3i position, Direction face, int priority) {
        return scan(player, position, face, priority, true);
    }

    /**
     * Re-reads only a source the player already bound to this project.
     *
     * <p>A diagnostic may be run while the player stands at the construction site rather
     * than beside the chest, so it must not turn {@link Container#stillValid} distance
     * into a fake missing-container fault. The exact stored coordinate, face and source
     * identity remain the authority; this method neither discovers nor opens any other
     * inventory and it never loads a chunk.</p>
     */
    static Source rescanBoundSourceForDiagnosis(ServerPlayer player, Source bound) {
        java.util.Objects.requireNonNull(bound, "bound");
        return scan(player, bound.position(), direction(bound.accessFace()), bound.priority(), false);
    }

    private static Source scan(ServerPlayer player, BlockPos3i position, Direction face,
            int priority, boolean requirePlayerReach) {
        BlockPos exact = new BlockPos(position.x(), position.y(), position.z());
        if (!player.serverLevel().hasChunkAt(exact)) return null;
        BlockEntity blockEntity = player.serverLevel().getBlockEntity(exact);
        if (!(blockEntity instanceof Container container)) return null;
        ResourceLocation type = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType());
        if (type == null || !SUPPORTED_CONTAINER_TYPES.contains(type.toString())
                || (requirePlayerReach && !container.stillValid(player))
                || container.getContainerSize() < 1 || container.getContainerSize() > 4_096) return null;
        ArrayList<Slot> slots = new ArrayList<>();
        long total = 0;
        StringBuilder fingerprint = new StringBuilder(type.toString());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            fingerprint.append('\n').append(slot).append('=');
            if (stack.isEmpty()) { fingerprint.append("empty"); continue; }
            ResourceLocation item = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (item == null) return null;
            String payload = canonicalPayload(stack);
            fingerprint.append(item).append('@').append(stack.getCount()).append('#').append(payload);
            slots.add(new Slot(slot, new MaterialIdentity(ResourceId.parse(item.toString()), payload),
                    stack.getCount()));
            total = Math.addExact(total, stack.getCount());
        }
        long now = Instant.now().toEpochMilli();
        return new Source(UUID.randomUUID(), position, face.getName(), type.toString(),
                ForgeSiteSurveyAdapter.fingerprint(player.serverLevel().getBlockState(exact)),
                sha256(fingerprint.toString()), priority, Math.max(1, total), now,
                now + SOURCE_TTL_MILLIS, false, slots);
    }

    /**
     * Whether the container still holds what was reserved from it.
     *
     * <p>This used to compare the inventory hash and then the slot list, and both are
     * hashes of an arrangement: slot 3 holding eight iron is a different fingerprint from
     * slot 7 holding eight iron. So a player who tidied their own chest between reserving
     * and building lost the order to MATERIAL_SOURCE_CHANGED, having done nothing but
     * organise their own storage. Nothing about the reservation actually depended on
     * where in the chest the iron sat.
     *
     * <p>What it compares now is content: for every identity the container held when it
     * was bound, it must still hold at least that much, summed across slots. The
     * guarantee that mattered is unchanged — take anything out and this still refuses,
     * because the sum drops. Swap the NBT and it still refuses, because payload is part
     * of the identity. Move it, or add more of it, and the order survives.
     *
     * <p>Which container it is remains exact. Position, access face, block entity type
     * and block state are identity, not arrangement, and a different chest in the same
     * place is a different chest.</p>
     */
    static boolean sameSnapshot(Source expected, Source actual) {
        boolean sameContainer = expected.position().equals(actual.position())
                && expected.accessFace().equals(actual.accessFace())
                && expected.blockEntityType().equals(actual.blockEntityType())
                && expected.blockStateHash().equals(actual.blockStateHash());
        if (!sameContainer) return false;
        Map<MaterialIdentity, Long> held = new LinkedHashMap<>();
        actual.slots().forEach(slot ->
                held.merge(slot.identity(), slot.quantity(), Math::addExact));
        Map<MaterialIdentity, Long> bound = new LinkedHashMap<>();
        expected.slots().forEach(slot ->
                bound.merge(slot.identity(), slot.quantity(), Math::addExact));
        return bound.entrySet().stream().allMatch(entry ->
                held.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    /**
     * Takes exactly what a reservation claims, from wherever in the container it now is.
     *
     * <p>The reserved slot is tried first, so a container nobody touched behaves exactly
     * as it did before. Beyond that the slot number is not part of the claim: the
     * reservation names an identity and a quantity, and a tidied chest still holds both.
     *
     * <p>All or nothing. The total is counted before anything is removed, and anything
     * already taken goes back if the rest cannot be found, because a half-withdrawn
     * reservation is worse than a refused one — it leaves the player short with no
     * order to show for it.</p>
     */
    static ItemStack withdrawReserved(Container container, Reservation reservation) {
        java.util.Objects.requireNonNull(container, "container");
        java.util.Objects.requireNonNull(reservation, "reservation");
        long needed = reservation.quantity();
        long available = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && identityOf(stack).equals(reservation.identity())) {
                available = Math.addExact(available, stack.getCount());
            }
        }
        if (available < needed) {
            throw new IllegalStateException("reserved material is no longer in the container: "
                    + reservation.identity().itemId() + " needs " + needed + ", holds " + available);
        }
        List<Integer> order = new ArrayList<>();
        if (reservation.slot() < container.getContainerSize()) order.add(reservation.slot());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (slot != reservation.slot()) order.add(slot);
        }
        ItemStack gathered = ItemStack.EMPTY;
        List<ItemStack> taken = new ArrayList<>();
        List<Integer> takenFrom = new ArrayList<>();
        long remaining = needed;
        for (int slot : order) {
            if (remaining < 1) break;
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !identityOf(stack).equals(reservation.identity())) continue;
            int take = (int) Math.min(remaining, stack.getCount());
            ItemStack removed = container.removeItem(slot, take);
            if (removed.getCount() != take) {
                // Put back what was already taken. Only a container lying about its own
                // contents gets here, and leaving the player short would be the worse half
                // of that failure.
                for (int index = 0; index < taken.size(); index++) {
                    container.setItem(takenFrom.get(index), taken.get(index));
                }
                if (!removed.isEmpty()) container.setItem(slot, removed);
                container.setChanged();
                throw new IllegalStateException("partial withdrawal from slot " + slot);
            }
            taken.add(removed.copy());
            takenFrom.add(slot);
            if (gathered.isEmpty()) gathered = removed;
            else gathered.grow(removed.getCount());
            remaining -= take;
        }
        if (remaining > 0 || gathered.getCount() != needed) {
            for (int index = 0; index < taken.size(); index++) {
                container.setItem(takenFrom.get(index), taken.get(index));
            }
            container.setChanged();
            throw new IllegalStateException("reserved material moved while it was being taken");
        }
        container.setChanged();
        return gathered;
    }

    static MaterialIdentity identityOf(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) throw new IllegalArgumentException("unregistered material item");
        return new MaterialIdentity(ResourceId.parse(key.toString()), canonicalPayload(stack));
    }

    private static Source withIdentityAndPriority(Source source, UUID id, int priority) {
        return new Source(id, source.position(), source.accessFace(), source.blockEntityType(),
                source.blockStateHash(), source.inventoryHash(), priority, source.maximumWithdrawal(),
                source.boundAt(), source.expiresAt(), source.projectSalvage(), source.slots());
    }

    private static Source projectSalvageSource(ServerPlayer player, UUID projectId, int priority) {
        PlayerSalvageSavedData.Entry destination = PlayerSalvageSavedData.forLevel(
                player.serverLevel()).entry(projectId).orElse(null);
        SitePreparationCommand.PreparedExecutionContext prepared =
                SitePreparationCommand.preparedExecutionContext(player).orElse(null);
        if (destination == null || prepared == null) return null;
        Map<ResourceId, Long> attributable = new LinkedHashMap<>();
        prepared.prepared().salvageLedger().entries().forEach(value -> attributable.merge(
                value.resourceId(), (long) value.deliveredCount(), Math::addExact));
        long total = attributable.values().stream().mapToLong(Long::longValue).sum();
        if (total < 1) return null;
        Source scanned = scan(player, destination.position(), Direction.UP, priority);
        if (scanned == null) return null;
        ArrayList<Slot> attributableSlots = new ArrayList<>();
        LinkedHashMap<ResourceId, Long> remaining = new LinkedHashMap<>(attributable);
        for (Slot slot : scanned.slots()) {
            long allowed = remaining.getOrDefault(slot.identity().itemId(), 0L);
            long quantity = Math.min(slot.quantity(), allowed);
            if (quantity > 0) {
                attributableSlots.add(new Slot(slot.slot(), slot.identity(), quantity));
                remaining.put(slot.identity().itemId(), allowed - quantity);
            }
        }
        long attributablePresent = attributableSlots.stream().mapToLong(Slot::quantity).sum();
        if (attributablePresent < 1) return null;
        UUID sourceId = UUID.nameUUIDFromBytes(("salvage-transfer:" + projectId).getBytes(
                StandardCharsets.UTF_8));
        return new Source(sourceId, scanned.position(), scanned.accessFace(), scanned.blockEntityType(),
                scanned.blockStateHash(), scanned.inventoryHash(), priority, attributablePresent,
                scanned.boundAt(), scanned.expiresAt(), true, attributableSlots);
    }

    private static boolean withinLogisticsRange(
            dev.stevecreate.agent.core.deployment.DeploymentBoundingBox bounds,
            BlockPos3i position,
            int margin) {
        return position.x() >= bounds.minimum().x() - margin && position.x() <= bounds.maximum().x() + margin
                && position.y() >= bounds.minimum().y() - margin && position.y() <= bounds.maximum().y() + margin
                && position.z() >= bounds.minimum().z() - margin && position.z() <= bounds.maximum().z() + margin;
    }

    private static String validate(PlayerWorkflowSavedData.ProjectEntry project, UUID projectId,
            long nonce, WorkflowStage stage) {
        if (project == null || !project.projectId().equals(projectId)) return "PROJECT_NOT_FOUND";
        if (project.nonce() != nonce) return "STALE_PROJECT_REQUEST";
        if (project.stage() != stage) return "PROJECT_STAGE_MISMATCH";
        return null;
    }

    static Direction direction(String name) {
        for (Direction value : Direction.values()) if (value.getName().equals(name)) return value;
        throw new IllegalArgumentException("invalid material source face");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Damageable items eagerly serialize Damage:0; treat only that canonical zero as empty. */
    static String canonicalPayload(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || (tag.size() == 1
                && tag.contains("Damage", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)
                && tag.getInt("Damage") == 0)) {
            return MaterialIdentity.EMPTY_PAYLOAD_SHA256;
        }
        return sha256(tag.toString());
    }

    private static SelectionResult success(Entry entry) {
        return new SelectionResult(true, "OK", entry, null, summary(entry));
    }

    private static SelectionResult success(Entry entry, PlayerWorkflowSavedData.ProjectEntry project) {
        return new SelectionResult(true, "OK", entry, project, summary(entry));
    }

    private static Summary summary(Entry entry) {
        LinkedHashMap<ResourceId, Long> available = new LinkedHashMap<>();
        for (ResourceId required : entry.requirements().keySet()) {
            long count = entry.sources().stream().flatMap(value -> value.slots().stream())
                    .filter(value -> value.identity().itemId().equals(required)
                            && value.identity().payloadSha256().equals(MaterialIdentity.EMPTY_PAYLOAD_SHA256))
                    .mapToLong(Slot::quantity).sum();
            available.put(required, count);
        }
        return new Summary(entry.sources().size(), entry.requirements(), available,
                entry.reservations().stream().mapToLong(Reservation::quantity).sum(), entry.allowSalvage(),
                entry.statusCode(), entry.report());
    }

    public record Summary(int sourceCount, Map<ResourceId, Long> required,
            Map<ResourceId, Long> available, long reserved, boolean allowSalvage,
            String statusCode, PlayerMaterialSavedData.CompletionReport report) {
        public Summary { required = Map.copyOf(required); available = Map.copyOf(available); }
        public boolean sufficient() {
            return required.entrySet().stream().allMatch(value -> available.getOrDefault(value.getKey(), 0L)
                    >= value.getValue());
        }
    }

    public record SelectionResult(boolean success, String statusCode, Entry entry,
            PlayerWorkflowSavedData.ProjectEntry project, Summary summary, String detail) {
        private static final int MAX_DETAIL_LENGTH = 1_024;

        public SelectionResult {
            if (statusCode == null || statusCode.isBlank() || statusCode.length() > 96) {
                throw new IllegalArgumentException("invalid material selection status");
            }
            detail = normalizeDetail(statusCode, detail);
        }

        /** Compatibility constructor for callers that do not have a diagnostic detail. */
        public SelectionResult(boolean success, String statusCode, Entry entry,
                PlayerWorkflowSavedData.ProjectEntry project, Summary summary) {
            this(success, statusCode, entry, project, summary, statusCode);
        }

        public static SelectionResult failure(String code) {
            return failure(code, code);
        }

        public static SelectionResult failure(String code, String detail) {
            return new SelectionResult(false, code, null, null, null, detail);
        }

        private static String normalizeDetail(String statusCode, String value) {
            String normalized = value == null || value.isBlank() ? statusCode
                    : value.replace('\n', ' ').replace('\r', ' ').trim();
            if (normalized.length() <= MAX_DETAIL_LENGTH) return normalized;
            return normalized.substring(0, MAX_DETAIL_LENGTH - 14) + "…[truncated]";
        }
    }
}
