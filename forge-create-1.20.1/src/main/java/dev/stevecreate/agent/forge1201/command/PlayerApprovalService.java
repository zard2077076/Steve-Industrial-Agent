package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalContext;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalRequest;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalService;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalToken;
import dev.stevecreate.agent.core.siteprep.DemolitionPreview;
import dev.stevecreate.agent.core.siteprep.ObstacleClassifier;
import dev.stevecreate.agent.core.siteprep.ObstacleFinding;
import dev.stevecreate.agent.core.siteprep.SiteSurveySnapshot;
import dev.stevecreate.agent.forge1201.player.PlayerApprovalSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Builds and issues the player token from one fresh authoritative full preview. */
public final class PlayerApprovalService {
    public static final String SAFETY_POLICY = "light-natural-only/v1";
    private static final int APPROVAL_TTL_SECONDS = 120;
    private static final ObstacleClassifier CLASSIFIER = new ObstacleClassifier();
    private static final DemolitionApprovalService APPROVALS = new DemolitionApprovalService();

    private PlayerApprovalService() {}

    public static SummaryResult summarize(
            ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData.ProjectEntry project = PlayerWorkflowSavedData
                .forLevel(player.serverLevel()).entry(player.getUUID()).orElse(null);
        String validation = validate(project, projectId, projectNonce);
        if (validation != null) return SummaryResult.failure(validation);
        PlayerPreviewService.PreviewResult preview = PlayerPreviewService.preview(
                player, projectId, projectNonce, project.anchor(), project.orientation(),
                project.layoutVariant());
        if (!preview.success()) return SummaryResult.failure(preview.statusCode());
        if (!preview.planHash().equals(project.planHash())
                || !preview.snapshotHash().equals(project.siteSnapshotHash())) {
            return SummaryResult.failure("APPROVAL_PREVIEW_STALE");
        }
        // Keep approval on the exact target selected by the player. Derived live-registry
        // goals are valid order identities too; using only the reviewed catalog here
        // would turn a successful search/create into TARGET_NOT_SUPPORTED at approval.
        GoalCatalogEntry goal = SingleMachineGoalResolver.resolve(
                player.serverLevel(), project.target()).orElse(null);
        if (goal == null) return SummaryResult.failure("TARGET_NOT_SUPPORTED");
        GoalCatalogEntry.MaterialEstimate estimate;
        try {
            estimate = goal.estimate(project.quantity());
        } catch (IllegalArgumentException invalid) {
            return SummaryResult.failure("PROJECT_BUDGET_EXCEEDED");
        }
        String inputs = estimate.requiredInputs().entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .sorted().collect(Collectors.joining(", "));
        int bots = preview.clearCount() > 12 ? 3 : 2;
        int work = preview.placeCount() + preview.clearCount();
        String phaseEstimate = work <= 12 ? "SHORT" : work <= 36 ? "MEDIUM" : "LONG";
        boolean approvable = preview.safeForConfirmation() && preview.containerCount() == 0;
        String risk = !approvable ? "BLOCKED"
                : preview.clearCount() == 0 ? "LOW" : "MEDIUM";
        return new SummaryResult(true, "OK", new ConfirmationSummary(
                project.projectId(), project.nonce(), project.target(), project.quantity(),
                project.anchor(), project.orientation(), project.layoutVariant(),
                project.executionMode(), preview.planHash(), preview.snapshotHash(),
                preview.bounds(), preview.placeCount(), preview.reuseCount(), preview.clearCount(),
                preview.protectedCount(), preview.containerCount(), preview.unknownCount(),
                preview.hazardCount(), inputs, bots, phaseEstimate, risk,
                approvable, SAFETY_POLICY));
    }

    public static ApprovalResult approve(
            ServerPlayer player,
            UUID projectId,
            long projectNonce,
            String expectedPlanHash,
            String expectedSnapshotHash) {
        SummaryResult summarized = summarize(player, projectId, projectNonce);
        if (!summarized.success()) return ApprovalResult.failure(summarized.statusCode());
        ConfirmationSummary summary = summarized.summary();
        if (!summary.planHash().equals(expectedPlanHash)
                || !summary.snapshotHash().equals(expectedSnapshotHash)) {
            return ApprovalResult.failure("APPROVAL_PREVIEW_STALE");
        }
        if (!summary.approvable()) return ApprovalResult.failure("HARD_CONFLICT_PRESENT");
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = data.entry(player.getUUID()).orElseThrow();
        PlayerPreviewService.PreviewResult current = PlayerPreviewService.preview(
                player, projectId, projectNonce, project.anchor(), project.orientation(),
                project.layoutVariant());
        SurveyResult surveyResult = survey(player, project, current);
        if (!surveyResult.success()) return ApprovalResult.failure(surveyResult.statusCode());
        SiteSurveySnapshot survey = surveyResult.survey();
        Instant now = Instant.now();
        DemolitionPreview demolition = DemolitionPreview.create(
                survey, Math.multiplyExact(20, survey.findings().size()),
                survey.findings().size(), now.plusSeconds(APPROVAL_TTL_SECONDS));
        Set<String> obstacleIds = survey.findings().stream()
                .map(ObstacleFinding::obstacleId).collect(Collectors.toUnmodifiableSet());
        DemolitionApprovalContext context = DemolitionApprovalContext.create(
                project.projectId().toString(), project.target(), project.quantity(), project.anchor(),
                project.orientation(), project.layoutVariant(), survey.selectionHash(),
                SAFETY_POLICY, project.executionMode());
        DemolitionApprovalToken token;
        try {
            token = APPROVALS.issue(survey, demolition, new DemolitionApprovalRequest(
                    player.getUUID().toString(), obstacleIds, obstacleIds.size(), now,
                    now.plusSeconds(APPROVAL_TTL_SECONDS), context));
        } catch (IllegalArgumentException failure) {
            return ApprovalResult.failure(failure.getMessage());
        }
        PlayerApprovalSavedData.forLevel(player.serverLevel()).put(project.projectId(), token);
        PlayerWorkflowSavedData.ProjectEntry updated = project.withStage(
                WorkflowStage.AWAITING_APPROVAL, PlayerWorkflowService.nextNonce(),
                now.toEpochMilli(), "APPROVAL_ACTIVE");
        data.put(updated);
        return new ApprovalResult(true, "OK", token, updated);
    }

    private static SurveyResult survey(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            PlayerPreviewService.PreviewResult preview) {
        if (preview == null || !preview.success()) {
            return SurveyResult.failure("APPROVAL_PREVIEW_STALE");
        }
        ArrayList<ObstacleFinding> findings = new ArrayList<>();
        for (PlayerPreviewService.PreviewCell cell : preview.cells()) {
            if (cell.category() != PlayerPreviewService.PreviewCategory.CLEAR) continue;
            BlockPos position = new BlockPos(cell.position().x(), cell.position().y(), cell.position().z());
            ObstacleFinding finding;
            try {
                finding = CLASSIFIER.classify(ForgeSiteSurveyAdapter.observe(
                        player.serverLevel(), position));
            } catch (RuntimeException stale) {
                return SurveyResult.failure("APPROVAL_PREVIEW_STALE");
            }
            if (!finding.classification().approvable()
                    || !finding.blockStateFingerprint().equals(cell.stateFingerprint())) {
                return SurveyResult.failure("APPROVAL_PREVIEW_STALE");
            }
            findings.add(finding);
        }
        DeploymentBoundingBox bounds = authorizedBounds(player, preview);
        String worldIdentity = PilotWorldMarkerSavedData.forLevel(player.serverLevel())
                .marker().map(PilotWorldMarkerSavedData.Marker::worldIdentity).orElse("");
        if (worldIdentity.isEmpty()) return SurveyResult.failure("WORLD_NOT_AUTHORIZED");
        String selectionHash = regionAuthorizationHash(project, bounds);
        SiteSurveySnapshot survey = new SiteSurveySnapshot(worldIdentity, project.dimension(), bounds,
                selectionHash, preview.planHash(), Instant.now(), findings, preview.snapshotHash());
        return new SurveyResult(true, "OK", survey);
    }

    /**
     * Whether this project can be surveyed for approval, now or again.
     *
     * <p>Approval is re-entrant on purpose. Asking for it advances the project to
     * {@link WorkflowStage#AWAITING_APPROVAL}, and this used to demand the stage still be
     * {@link WorkflowStage#SITE_SURVEY} — so the second ask always failed, and the
     * terminal offers exactly one button at that point. One survey and the project was
     * stuck: it could not go forward and there was nothing to go back to. Re-entering
     * simply surveys again and issues a fresh token, which is what a player who reopened
     * their terminal is asking for.
     *
     * <p>The three conditions that follow are the real question — whether the survey has
     * anything to work from — and they are unchanged.</p>
     */
    static String validate(
            PlayerWorkflowSavedData.ProjectEntry project,
            UUID projectId,
            long projectNonce) {
        if (project == null || !project.projectId().equals(projectId)) return "PROJECT_NOT_FOUND";
        if (project.nonce() != projectNonce) return "STALE_PROJECT_REQUEST";
        if (project.stage() != WorkflowStage.SITE_SURVEY
                && project.stage() != WorkflowStage.AWAITING_APPROVAL) {
            return "SITE_SURVEY_NOT_READY";
        }
        if (project.anchor() == null || project.planHash().isEmpty()
                || project.siteSnapshotHash().isEmpty()) {
            return "SITE_SURVEY_NOT_READY";
        }
        return null;
    }

    /** Exact bounded work envelope shared by approval, salvage selection and Bot pathing. */
    static DeploymentBoundingBox authorizedBounds(
            ServerPlayer player, PlayerPreviewService.PreviewResult preview) {
        PlayerPreviewService.Bounds value = preview.bounds();
        int minimumY = Math.max(player.serverLevel().getMinBuildHeight(), value.minY() - 2);
        int maximumY = Math.min(player.serverLevel().getMaxBuildHeight() - 1, value.maxY() + 2);
        return new DeploymentBoundingBox(
                new BlockPos3i(value.minX() - 4, minimumY, value.minZ() - 4),
                new BlockPos3i(value.maxX() + 4, maximumY, value.maxZ() + 4));
    }

    static String regionAuthorizationHash(
            PlayerWorkflowSavedData.ProjectEntry project, DeploymentBoundingBox bounds) {
        return sha256(project.projectId() + "\n" + project.playerId() + "\n"
                + project.dimension() + "\n" + bounds + "\n" + project.anchor() + "\n"
                + project.orientation() + "\n" + project.layoutVariant());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record ConfirmationSummary(
            UUID projectId,
            long projectNonce,
            ResourceId target,
            long quantity,
            BlockPos3i anchor,
            dev.stevecreate.agent.core.model.QuarterTurn orientation,
            dev.stevecreate.agent.core.player.LayoutVariant layoutVariant,
            dev.stevecreate.agent.core.player.PlayerExecutionMode executionMode,
            String planHash,
            String snapshotHash,
            PlayerPreviewService.Bounds bounds,
            int placeCount,
            int reuseCount,
            int clearCount,
            int protectedCount,
            int containerCount,
            int unknownCount,
            int hazardCount,
            String requiredInputs,
            int recommendedBots,
            String phaseEstimate,
            String riskLevel,
            boolean approvable,
            String safetyPolicy) {}

    public record SummaryResult(boolean success, String statusCode, ConfirmationSummary summary) {
        static SummaryResult failure(String code) {
            return new SummaryResult(false, code, null);
        }
    }

    public record ApprovalResult(
            boolean success,
            String statusCode,
            DemolitionApprovalToken token,
            PlayerWorkflowSavedData.ProjectEntry project) {
        static ApprovalResult failure(String code) {
            return new ApprovalResult(false, code, null, null);
        }
    }

    private record SurveyResult(boolean success, String statusCode, SiteSurveySnapshot survey) {
        static SurveyResult failure(String code) {
            return new SurveyResult(false, code, null);
        }
    }
}
