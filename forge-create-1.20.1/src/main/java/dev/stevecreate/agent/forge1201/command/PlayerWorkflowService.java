package dev.stevecreate.agent.forge1201.command;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets;
import dev.stevecreate.agent.core.player.GoalSearch;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionIntent;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import dev.stevecreate.agent.forge1201.player.PlayerApprovalSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/** Server authority shared by packets and future command adapters. */
public final class PlayerWorkflowService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private PlayerWorkflowService() {}

    public static TerminalSnapshot terminalSnapshot(ServerPlayer player, long terminalNonce) {
        boolean authorized = PilotWorldMarkerSavedData.forLevel(player.serverLevel())
                .marker().isPresent();
        String refusal = authorized ? "OK" : "WORLD_NOT_AUTHORIZED";
        Optional<PlayerWorkflowSavedData.ProjectEntry> project =
                PlayerWorkflowSavedData.forLevel(player.serverLevel()).entry(player.getUUID());
        return new TerminalSnapshot(terminalNonce, authorized, refusal,
                catalog(player), project.orElse(null));
    }

    public static List<GoalAvailability> catalog(ServerPlayer player) {
        return catalog(player, "");
    }

    /**
     * The goals worth offering for a query.
     *
     * <p>An empty query is the reviewed list, unchanged — which is what every existing
     * caller passes, so nothing that worked before sees anything different. A query
     * reaches the derived catalog, which is where the other couple of hundred orderable
     * targets live and which no client could previously see at all.
     *
     * <p>Capped at {@link PlayerWorkflowPackets#MAX_GOALS} because that is what the wire
     * carries. Sending more would not fail loudly, it would fail at the packet boundary,
     * so the cap belongs here where it can be reasoned about.</p>
     */
    public static List<GoalAvailability> catalog(ServerPlayer player, String query) {
        boolean createLoaded = ModList.get().isLoaded("create");
        List<GoalCatalogEntry> derived = query.isBlank() ? List.of()
                : LiveRecipeCatalog.admittedRecipes(player.serverLevel());
        return GoalSearch.matching(PlayerGoalCatalog.entries(), derived, query,
                        PlayerWorkflowPackets.MAX_GOALS).stream()
                .map(entry -> availability(player, entry, createLoaded))
                .toList();
    }

    public static CreateResult createProject(
            ServerPlayer player,
            String targetText,
            int quantity,
            ProductionMode productionMode,
            PlayerExecutionMode executionMode) {
        if (PilotWorldMarkerSavedData.forLevel(player.serverLevel()).marker().isEmpty()) {
            return new CreateResult(null, "WORLD_NOT_AUTHORIZED");
        }
        if (productionMode != ProductionMode.ONCE) {
            return new CreateResult(null, "CONTINUOUS_MODE_UNAVAILABLE");
        }
        PlayerWorkflowSavedData savedData = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry existing = savedData.entry(player.getUUID()).orElse(null);
        if (existing != null && existing.stage() != WorkflowStage.COMPLETED
                && existing.stage() != WorkflowStage.CANCELLED
                && existing.stage() != WorkflowStage.REFUSED) {
            return new CreateResult(null, "ACTIVE_PROJECT_EXISTS");
        }
        ResourceId target;
        try {
            target = ResourceId.parse(targetText.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return new CreateResult(null, "TARGET_INVALID");
        }
        // Reviewed first, then the live registry. Looking only at the reviewed eleven
        // here would refuse every derivable target at the very first step, before any of
        // the machinery that can actually build them is consulted.
        GoalCatalogEntry entry =
                SingleMachineGoalResolver.resolve(player.serverLevel(), target).orElse(null);
        if (entry == null) return new CreateResult(null, "TARGET_NOT_SUPPORTED");
        if (PilotDeploymentCommand.TargetSpec.supported(
                player.serverLevel(), target, quantity) == null) {
            return new CreateResult(null, "TARGET_QUANTITY_NOT_EXECUTION_VERIFIED");
        }
        // Do not ask the empty-query picker whether this already-resolved target exists.
        // Empty query deliberately returns only the eleven reviewed rows; using it as an
        // authority here made every searched derived target visible and then refused it
        // as TARGET_NOT_SUPPORTED at the first click. Availability is a property of the
        // resolved entry, not of which subset the current UI query happens to display.
        GoalAvailability availability = availability(
                player, entry, ModList.get().isLoaded("create"));
        if (!availability.available()) {
            return new CreateResult(null, availability.statusCode());
        }
        try {
            entry.estimate(quantity);
            new ProductionIntent(target, quantity, productionMode, executionMode);
        } catch (IllegalArgumentException budget) {
            return new CreateResult(null, "PROJECT_BUDGET_EXCEEDED");
        }
        long now = Instant.now().toEpochMilli();
        PlayerWorkflowSavedData.ProjectEntry project = new PlayerWorkflowSavedData.ProjectEntry(
                UUID.randomUUID(), player.getUUID(), target, quantity,
                ResourceId.parse(player.serverLevel().dimension().location().toString()),
                WorkflowStage.PLACEMENT_PREVIEW, productionMode, executionMode,
                LayoutVariant.STANDARD, QuarterTurn.ZERO, null,
                nextNonce(), now, now, "PLACEMENT_PREVIEW_READY", "", "");
        savedData.put(project);
        return new CreateResult(project, "OK");
    }

    private static GoalAvailability availability(
            ServerPlayer player,
            GoalCatalogEntry entry,
            boolean createLoaded) {
        ResourceLocation recipeId = ResourceLocation.parse(entry.recipe().toString());
        boolean recipePresent = player.serverLevel().getRecipeManager().byKey(recipeId).isPresent();
        String status = !createLoaded ? "CREATE_NOT_LOADED"
                : !recipePresent ? "RECIPE_NOT_PRESENT"
                : !entry.executionVerified() ? "EXECUTION_NOT_VERIFIED" : "READY";
        // EXECUTION_NOT_VERIFIED is shown and no longer refused.
        //
        // It means "nobody has physically run this exact product", which is true of
        // every derived target and always will be — there are hundreds of them and only
        // eleven reviewed ones. Treating it as a refusal made the picker offer hundreds
        // of goals and accept eleven, every other row greyed out under a red label, which
        // is not a safety property. It is a statement about test coverage presented to
        // the player as a prohibition.
        //
        // What actually guards this is downstream and unchanged: the layout probe, the
        // planner, the material plan and the physical contract each refuse with a typed
        // code. A goal that cannot be built is still refused — just by something that
        // actually looked.
        return new GoalAvailability(entry, createLoaded && recipePresent, status);
    }

    public static CreateResult finalizePlacement(
            ServerPlayer player,
            dev.stevecreate.agent.forge1201.command.PlayerPreviewService.PreviewResult preview) {
        if (preview == null || !preview.success()) {
            return new CreateResult(null, "PREVIEW_NOT_READY");
        }
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry current = data.entry(player.getUUID()).orElse(null);
        if (current == null || !current.projectId().equals(preview.projectId())
                || current.nonce() != preview.projectNonce()
                || current.stage() != WorkflowStage.PLACEMENT_PREVIEW) {
            return new CreateResult(null, "STALE_PROJECT_REQUEST");
        }
        long now = Instant.now().toEpochMilli();
        PlayerWorkflowSavedData.ProjectEntry updated = current.withPlacement(
                preview.variant(), preview.orientation(), preview.anchor(), WorkflowStage.SITE_SURVEY,
                nextNonce(), now, "SITE_SURVEY_READY",
                preview.planHash(), preview.snapshotHash());
        data.put(updated);
        return new CreateResult(updated, "OK");
    }

    /**
     * Gives up on a project, from whatever stage it is in.
     *
     * <p>There were four ways to cancel and each covered one stretch of the workflow:
     * the preview, the material selection, an active clearing session, an active
     * construction. Between them sat SITE_SURVEY, AWAITING_APPROVAL, and a project paused
     * for any reason the material path did not recognise — and from those there was no
     * way out at all. A player whose post-clearance rescan failed had a terminal with one
     * greyed button and a project that could not go forward, and the only remedy was
     * deleting saved data by hand.
     *
     * <p>Delegates wherever a specialised path exists, because those paths return
     * material and stop bots and this must not reimplement any of that. What is left over
     * is the stages that hold nothing: the world may already have been cleared, but
     * clearing is not owed back — the salvage is in the player's chest — and no material
     * has been reserved yet.
     *
     * <p>Cancelling an already-finished project reports success. It is what the player
     * asked for and it is already true.</p>
     */
    public static CreateResult abandon(ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry current = data.entry(player.getUUID()).orElse(null);
        if (current == null || !current.projectId().equals(projectId)) {
            return new CreateResult(null, "PROJECT_NOT_FOUND");
        }
        if (current.nonce() != projectNonce) return new CreateResult(null, "STALE_PROJECT_REQUEST");
        switch (current.stage()) {
            case CANCELLED, COMPLETED, REFUSED -> {
                return new CreateResult(current, "OK");
            }
            case MATERIAL_SOURCE_SELECTION, MATERIAL_RESERVED -> {
                PlayerMaterialService.SelectionResult released =
                        PlayerMaterialService.cancel(player, projectId, projectNonce);
                LOGGER.info("Player abandon material result project={} stage={} code={} success={}",
                        projectId, current.stage(), released.statusCode(), released.success());
                if (!released.success()
                        && "CANCEL_REQUIRES_MATERIAL_RETURN".equals(released.statusCode())) {
                    // Compatibility recovery for projects written by the old transition
                    // order, which incorrectly changed a transactional material pause
                    // back into MATERIAL_SOURCE_SELECTION. Re-establish PAUSED with a
                    // fresh nonce, then use the same exact-return path as new projects.
                    PlayerWorkflowSavedData.ProjectEntry paused = current.withStage(
                            WorkflowStage.PAUSED, nextNonce(), Instant.now().toEpochMilli(),
                            "MATERIAL_RECONCILIATION_REQUIRED");
                    data.put(paused);
                    PlayerConstructionService.Result recovered =
                            PlayerConstructionService.recoverCancel(
                                    player, projectId, paused.nonce());
                    LOGGER.info("Player abandon compatibility recovery project={} code={} success={}",
                            projectId, recovered.statusCode(), recovered.success());
                    return recovered.success()
                            ? new CreateResult(recovered.project(), "OK")
                            : new CreateResult(recovered.project(), recovered.statusCode());
                }
                return new CreateResult(released.project(), released.statusCode());
            }
            case CONSTRUCTION -> {
                PlayerConstructionService.Result stopped =
                        PlayerConstructionService.cancel(player, projectId, projectNonce);
                return new CreateResult(stopped.project(), stopped.statusCode());
            }
            default -> { }
        }
        PlayerWorkflowSavedData.ProjectEntry cancelled = current.withStage(
                WorkflowStage.CANCELLED, nextNonce(), Instant.now().toEpochMilli(),
                "CANCELLED_BY_PLAYER");
        data.put(cancelled);
        // Nothing was withdrawn in these stages, so there is nothing to give back — but a
        // stale reservation entry left behind would make the next project's ledger start
        // from someone else's arithmetic.
        PlayerMaterialSavedData.forLevel(player.serverLevel()).remove(projectId);
        SitePreparationCommand.releasePlayerWorkflow(player, projectId);
        return new CreateResult(cancelled, "OK");
    }

    public static CreateResult cancelPreview(
            ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry current = data.entry(player.getUUID()).orElse(null);
        if (current == null || !current.projectId().equals(projectId)) {
            return new CreateResult(null, "PROJECT_NOT_FOUND");
        }
        if (current.nonce() != projectNonce) {
            return new CreateResult(null, "STALE_PROJECT_REQUEST");
        }
        if (current.stage() != WorkflowStage.PLACEMENT_PREVIEW) {
            return new CreateResult(null, "PROJECT_STAGE_MISMATCH");
        }
        PlayerWorkflowSavedData.ProjectEntry updated = current.withStage(
                WorkflowStage.CANCELLED, nextNonce(), Instant.now().toEpochMilli(),
                "CANCELLED_BEFORE_SURVEY");
        data.put(updated);
        return new CreateResult(updated, "OK");
    }

    public static CreateResult reselectPlacement(
            ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry current = data.entry(player.getUUID()).orElse(null);
        if (current == null || !current.projectId().equals(projectId)) {
            return new CreateResult(null, "PROJECT_NOT_FOUND");
        }
        if (current.nonce() != projectNonce) {
            return new CreateResult(null, "STALE_PROJECT_REQUEST");
        }
        if (current.stage() != WorkflowStage.SITE_SURVEY
                && current.stage() != WorkflowStage.AWAITING_APPROVAL) {
            return new CreateResult(null, "PROJECT_STAGE_MISMATCH");
        }
        PlayerRelocationService.clearPlayer(player.getUUID());
        PlayerApprovalSavedData.forLevel(player.serverLevel()).remove(projectId);
        PlayerWorkflowSavedData.ProjectEntry updated = current.resetForPlacement(
                nextNonce(), Instant.now().toEpochMilli());
        data.put(updated);
        return new CreateResult(updated, "OK");
    }

    public static long nextNonce() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    public record TerminalSnapshot(
            long terminalNonce,
            boolean constructionAuthorized,
            String refusalCode,
            List<GoalAvailability> goals,
            PlayerWorkflowSavedData.ProjectEntry currentProject) {
        public TerminalSnapshot {
            goals = List.copyOf(goals);
        }
    }

    public record GoalAvailability(GoalCatalogEntry entry, boolean available, String statusCode) {}

    public record CreateResult(PlayerWorkflowSavedData.ProjectEntry project, String statusCode) {
        public boolean success() {
            return project != null && "OK".equals(statusCode);
        }
    }
}
