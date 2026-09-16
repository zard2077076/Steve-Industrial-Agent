package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlacementCandidateFacts;
import dev.stevecreate.agent.core.player.PlacementCandidateScore;
import dev.stevecreate.agent.core.player.SmartRelocationAdvisor;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Tick-sliced 9 x 4 x 3 x 2 smart relocation search. */
public final class PlayerRelocationService {
    public static final int MAX_CANDIDATES_PER_TICK = 6;
    public static final int EXACT_CANDIDATE_COUNT = 216;
    private static final long RESULT_TTL_MILLIS = 120_000L;
    private static final Map<UUID, SearchJob> JOBS = new HashMap<>();
    private static final SmartRelocationAdvisor ADVISOR = new SmartRelocationAdvisor();

    private PlayerRelocationService() {}

    public static StartResult start(ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData.ProjectEntry project = PlayerWorkflowSavedData
                .forLevel(player.serverLevel()).entry(player.getUUID()).orElse(null);
        if (project == null || !project.projectId().equals(projectId)) {
            return new StartResult(false, "PROJECT_NOT_FOUND", 0);
        }
        if (project.nonce() != projectNonce) {
            return new StartResult(false, "STALE_PROJECT_REQUEST", 0);
        }
        if (project.stage() != WorkflowStage.SITE_SURVEY || project.anchor() == null
                || project.planHash().isEmpty() || project.siteSnapshotHash().isEmpty()) {
            return new StartResult(false, "SITE_SURVEY_NOT_READY", 0);
        }
        SearchJob existing = JOBS.get(player.getUUID());
        if (existing != null && !existing.expired() && !existing.complete) {
            return new StartResult(false, "RELOCATION_SEARCH_ALREADY_ACTIVE", existing.specs.size());
        }
        JOBS.remove(player.getUUID());
        SearchJob job = new SearchJob(player.getUUID(), project, specs(
                project.anchor(), project.orientation(), project.layoutVariant()),
                System.currentTimeMillis() + RESULT_TTL_MILLIS);
        if (job.specs.size() != EXACT_CANDIDATE_COUNT) {
            throw new IllegalStateException("smart relocation candidate budget drifted");
        }
        JOBS.put(player.getUUID(), job);
        return new StartResult(true, "OK", job.specs.size());
    }

    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        ArrayList<UUID> remove = new ArrayList<>();
        for (SearchJob job : List.copyOf(JOBS.values())) {
            if (job.expired()) {
                remove.add(job.playerId);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
            if (player == null) continue;
            if (job.complete) continue;
            PlayerWorkflowSavedData.ProjectEntry current = PlayerWorkflowSavedData
                    .forLevel(player.serverLevel()).entry(player.getUUID()).orElse(null);
            if (current == null || !current.projectId().equals(job.project.projectId())
                    || current.nonce() != job.project.nonce()
                    || current.stage() != WorkflowStage.SITE_SURVEY) {
                PlayerWorkflowNetwork.sendRelocationFailure(player, "STALE_PROJECT_REQUEST");
                remove.add(job.playerId);
                continue;
            }
            int processed = 0;
            while (processed++ < MAX_CANDIDATES_PER_TICK && job.index < job.specs.size()) {
                CandidateSpec spec = job.specs.get(job.index++);
                PlayerPreviewService.PreviewResult preview = PlayerPreviewService.preview(
                        player, job.project.projectId(), job.project.nonce(), spec.anchor,
                        spec.orientation, spec.variant);
                if (preview.success()) {
                    PlacementCandidateFacts facts = facts(
                            player.serverLevel(), job.project, spec, preview, job.index - 1);
                    job.candidates.put(facts.candidateId(), new Candidate(facts, preview));
                }
            }
            if (job.index % 24 == 0 || job.index == job.specs.size()) {
                PlayerWorkflowNetwork.sendRelocationProgress(
                        player, job.project.projectId(), job.index, job.specs.size());
            }
            if (job.index >= job.specs.size()) complete(player, job);
        }
        remove.forEach(JOBS::remove);
    }

    public static SelectionResult select(
            ServerPlayer player,
            UUID projectId,
            long projectNonce,
            String candidateId) {
        SearchJob job = JOBS.get(player.getUUID());
        if (job == null || job.expired() || !job.complete
                || !job.project.projectId().equals(projectId)
                || job.project.nonce() != projectNonce) {
            return SelectionResult.failure("RELOCATION_RESULT_STALE");
        }
        Candidate candidate = job.candidates.get(candidateId);
        if (candidate == null) return SelectionResult.failure("CANDIDATE_NOT_FOUND");
        PlayerPreviewService.PreviewResult rescanned = PlayerPreviewService.preview(
                player, projectId, projectNonce, candidate.preview.anchor(),
                candidate.preview.orientation(), candidate.preview.variant());
        if (!rescanned.success()) return SelectionResult.failure(rescanned.statusCode());
        if (!rescanned.planHash().equals(candidate.preview.planHash())
                || !rescanned.snapshotHash().equals(candidate.preview.snapshotHash())) {
            return SelectionResult.failure("CANDIDATE_WORLD_CHANGED");
        }
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry current = data.entry(player.getUUID()).orElse(null);
        if (current == null || current.nonce() != projectNonce) {
            return SelectionResult.failure("STALE_PROJECT_REQUEST");
        }
        PlayerWorkflowSavedData.ProjectEntry updated = current.withPlacement(
                rescanned.variant(), rescanned.orientation(), rescanned.anchor(),
                WorkflowStage.SITE_SURVEY, PlayerWorkflowService.nextNonce(),
                Instant.now().toEpochMilli(), "RELOCATION_SELECTED",
                rescanned.planHash(), rescanned.snapshotHash());
        data.put(updated);
        JOBS.remove(player.getUUID());
        return new SelectionResult(true, "OK", rescanned, updated);
    }

    public static void clearPlayer(UUID playerId) {
        JOBS.remove(playerId);
    }

    public static void clearServerState() {
        JOBS.clear();
    }

    private static void complete(ServerPlayer player, SearchJob job) {
        if (job.candidates.isEmpty()) {
            job.complete = true;
            PlayerWorkflowNetwork.sendRelocationFailure(player, "NO_LOADED_CANDIDATE");
            return;
        }
        List<PlacementCandidateScore> ranked = ADVISOR.rank(job.candidates.values().stream()
                .map(Candidate::facts).toList());
        job.ranked = ranked;
        job.complete = true;
        PlacementCandidateScore recommended = ranked.stream()
                .filter(PlacementCandidateScore::recommendedSafe).findFirst().orElse(null);
        PlacementCandidateScore current = ranked.stream()
                .filter(value -> value.candidate().currentSelection()).findFirst().orElse(null);
        ArrayList<CandidateView> views = new ArrayList<>();
        if (recommended != null) views.add(view(recommended));
        if (current != null && (recommended == null || !current.candidate().candidateId()
                .equals(recommended.candidate().candidateId()))) views.add(view(current));
        if (views.size() < 2) {
            ranked.stream().filter(value -> views.stream().noneMatch(view ->
                            view.candidateId().equals(value.candidate().candidateId())))
                    .findFirst().ifPresent(value -> views.add(view(value)));
        }
        PlayerWorkflowNetwork.sendRelocationResult(player,
                recommended == null ? "NO_SAFE_CANDIDATE" : "OK",
                job.project.projectId(), job.project.nonce(),
                recommended == null ? "" : recommended.candidate().candidateId(), views);
    }

    private static CandidateView view(PlacementCandidateScore score) {
        PlacementCandidateFacts value = score.candidate();
        return new CandidateView(value.candidateId(), value.anchor(), value.orientation(),
                value.layoutVariant(), score.recommendedSafe(), value.currentSelection(),
                score.score(), value.protectedConflicts(), value.unknownConflicts(),
                value.containerConflicts(), value.hazardConflicts(), value.demolitionCount(),
                value.materialCost(), value.botWorkBlockedCells(), value.futureExpansionPenalty(),
                score.reasons());
    }

    private static PlacementCandidateFacts facts(
            ServerLevel level,
            PlayerWorkflowSavedData.ProjectEntry project,
            CandidateSpec spec,
            PlayerPreviewService.PreviewResult preview,
            int index) {
        int blocked = blockedWorkPositions(level, preview.bounds());
        int distance = Math.abs(spec.anchor.x() - project.anchor().x())
                + Math.abs(spec.anchor.z() - project.anchor().z());
        int expansion = switch (spec.variant) {
            case COMPACT -> 20;
            case STANDARD -> 8;
            case EXPANDABLE -> 0;
        };
        int risk = Math.min(100, preview.protectedCount() * 100
                + preview.containerCount() * 100 + preview.unknownCount() * 80
                + preview.hazardCount() * 100 + preview.clearCount() * 2 + blocked * 3);
        boolean current = spec.anchor.equals(project.anchor())
                && spec.orientation == project.orientation()
                && spec.variant == project.layoutVariant();
        String id = String.format(java.util.Locale.ROOT, "candidate-%03d-%s",
                index, preview.planHash().substring(0, 12));
        return new PlacementCandidateFacts(id, spec.anchor, spec.orientation, spec.variant,
                preview.protectedCount(), preview.unknownCount(), preview.containerCount(),
                preview.hazardCount(), preview.clearCount(), preview.clearCount(),
                preview.placeCount(), blocked, blocked, blocked, blocked, expansion,
                0, risk, distance, Math.max(0, spec.anchor.y() - project.anchor().y()), current);
    }

    private static int blockedWorkPositions(ServerLevel level, PlayerPreviewService.Bounds bounds) {
        List<BlockPos> positions = List.of(
                new BlockPos(bounds.minX() - 1, bounds.minY(), bounds.minZ()),
                new BlockPos(bounds.minX() - 1, bounds.minY(), bounds.maxZ()),
                new BlockPos(bounds.maxX() + 1, bounds.minY(), bounds.minZ()),
                new BlockPos(bounds.maxX() + 1, bounds.minY(), bounds.maxZ()),
                new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ() - 1),
                new BlockPos(bounds.maxX(), bounds.minY(), bounds.minZ() - 1),
                new BlockPos(bounds.minX(), bounds.minY(), bounds.maxZ() + 1),
                new BlockPos(bounds.maxX(), bounds.minY(), bounds.maxZ() + 1));
        int blocked = 0;
        for (BlockPos position : positions) {
            if (!level.hasChunkAt(position)) {
                blocked++;
                continue;
            }
            BlockState feet = level.getBlockState(position);
            BlockState head = level.getBlockState(position.above());
            if ((!feet.isAir() && !feet.canBeReplaced())
                    || (!head.isAir() && !head.canBeReplaced())) blocked++;
        }
        return blocked;
    }

    private static List<CandidateSpec> specs(
            BlockPos3i anchor,
            QuarterTurn selectedOrientation,
            LayoutVariant selectedVariant) {
        List<int[]> offsets = List.of(
                new int[] {0, 0},
                new int[] {1, 0}, new int[] {0, 1}, new int[] {-1, 0}, new int[] {0, -1},
                new int[] {2, 0}, new int[] {0, 2}, new int[] {-2, 0}, new int[] {0, -2});
        List<QuarterTurn> turns = preferred(selectedOrientation, QuarterTurn.values());
        List<LayoutVariant> variants = preferred(selectedVariant, LayoutVariant.values());
        ArrayList<CandidateSpec> specs = new ArrayList<>(EXACT_CANDIDATE_COUNT);
        for (int[] offset : offsets) {
            for (int elevation = 0; elevation <= 1; elevation++) {
                for (QuarterTurn turn : turns) {
                    for (LayoutVariant variant : variants) {
                        specs.add(new CandidateSpec(anchor.translate(
                                offset[0], elevation, offset[1]), turn, variant));
                    }
                }
            }
        }
        return List.copyOf(specs);
    }

    static List<String> candidateKeysForTest(
            BlockPos3i anchor,
            QuarterTurn selectedOrientation,
            LayoutVariant selectedVariant) {
        return specs(anchor, selectedOrientation, selectedVariant).stream()
                .map(value -> value.anchor + "|" + value.orientation + "|" + value.variant)
                .toList();
    }

    private static <T> List<T> preferred(T selected, T[] values) {
        ArrayList<T> ordered = new ArrayList<>();
        ordered.add(selected);
        for (T value : values) if (!value.equals(selected)) ordered.add(value);
        return List.copyOf(ordered);
    }

    public record StartResult(boolean success, String statusCode, int totalCandidates) {}

    public record CandidateView(
            String candidateId,
            BlockPos3i anchor,
            QuarterTurn orientation,
            LayoutVariant variant,
            boolean safe,
            boolean current,
            long score,
            int protectedCount,
            int unknownCount,
            int containerCount,
            int hazardCount,
            int demolitionCount,
            int materialCost,
            int blockedBotPositions,
            int expansionPenalty,
            List<String> reasons) {
        public CandidateView {
            reasons = List.copyOf(reasons);
        }
    }

    public record SelectionResult(
            boolean success,
            String statusCode,
            PlayerPreviewService.PreviewResult preview,
            PlayerWorkflowSavedData.ProjectEntry project) {
        static SelectionResult failure(String code) {
            return new SelectionResult(false, code, null, null);
        }
    }

    private record CandidateSpec(BlockPos3i anchor, QuarterTurn orientation, LayoutVariant variant) {}

    private record Candidate(PlacementCandidateFacts facts, PlayerPreviewService.PreviewResult preview) {}

    private static final class SearchJob {
        private final UUID playerId;
        private final PlayerWorkflowSavedData.ProjectEntry project;
        private final List<CandidateSpec> specs;
        private final long expiresAt;
        private final Map<String, Candidate> candidates = new LinkedHashMap<>();
        private int index;
        private boolean complete;
        private List<PlacementCandidateScore> ranked = List.of();

        private SearchJob(
                UUID playerId,
                PlayerWorkflowSavedData.ProjectEntry project,
                List<CandidateSpec> specs,
                long expiresAt) {
            this.playerId = playerId;
            this.project = project;
            this.specs = specs;
            this.expiresAt = expiresAt;
        }

        private boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
