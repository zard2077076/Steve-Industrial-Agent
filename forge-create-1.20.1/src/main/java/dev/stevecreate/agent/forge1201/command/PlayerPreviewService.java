package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.core.layout.GeometryComponent;
import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import dev.stevecreate.agent.core.siteprep.ObstacleClassifier;
import dev.stevecreate.agent.core.siteprep.ObstacleFinding;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeMachineImplementationCatalog;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606MachineGeometryCatalog;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/** Bounded authoritative preview of exact reviewed geometry. It never mutates a block. */
public final class PlayerPreviewService {
    public static final int MAX_PREVIEW_CELLS = 512;
    public static final int MAX_INTERACTION_DISTANCE_SQUARED = 16 * 16;
    private static final ObstacleClassifier CLASSIFIER = new ObstacleClassifier();

    private PlayerPreviewService() {}

    public static PreviewResult preview(
            ServerPlayer player,
            UUID projectId,
            long projectNonce,
            BlockPos3i anchor,
            QuarterTurn orientation,
            LayoutVariant variant) {
        PlayerWorkflowSavedData.ProjectEntry project = PlayerWorkflowSavedData
                .forLevel(player.serverLevel()).entry(player.getUUID()).orElse(null);
        String validation = validate(player, project, projectId, projectNonce, anchor);
        if (validation != null) return PreviewResult.failure(validation);

        // A searched target may be derived from the live recipe catalog rather than one
        // of the eleven reviewed rows. Resolve it through the same server-authoritative
        // resolver used when the project was created; consulting the reviewed list here
        // would make a visible derived target fail at the first placement preview.
        GoalCatalogEntry goal = SingleMachineGoalResolver.resolve(
                player.serverLevel(), project.target()).orElse(null);
        if (goal == null) return PreviewResult.failure("TARGET_NOT_SUPPORTED");
        RuntimeRecipeCatalogResult catalog = ForgeCreateRuntimeRecipeCatalogs
                .forLevel(player.serverLevel()).snapshot();
        if (!(catalog instanceof RuntimeRecipeCatalogResult.Success runtime)) {
            return PreviewResult.failure("RUNTIME_CATALOG_UNAVAILABLE");
        }
        MachineGeometryDescriptor geometry = CreateV606MachineGeometryCatalog
                .create(runtime.snapshot().runtimeFingerprint())
                .find(implementation(goal.capability())).orElse(null);
        if (geometry == null || !geometry.supportedOrientations().contains(orientation)) {
            return PreviewResult.failure("ORIENTATION_OR_GEOMETRY_UNAVAILABLE");
        }
        int spacing = spacing(variant, geometry);
        ArrayList<ExpectedCell> expected = new ArrayList<>();
        LinkedHashSet<BlockPos3i> clearancePositions = new LinkedHashSet<>();
        for (int module = 0; module < goal.physicalModuleCount(); module++) {
            BlockPos3i moduleOffset = new BlockPos3i(module * spacing, 0, 0).rotateY(orientation);
            BlockPos3i moduleAnchor = anchor.translate(moduleOffset.x(), 0, moduleOffset.z());
            for (GeometryComponent component : geometry.components()) {
                BlockPos3i rotated = component.relativePosition().rotateY(orientation);
                expected.add(new ExpectedCell(
                        moduleAnchor.translate(rotated.x(), rotated.y(), rotated.z()),
                        component.blockId(), component.roleId().toString()));
            }
            for (BlockPos3i clearance : geometry.clearance().cells()) {
                BlockPos3i rotated = clearance.rotateY(orientation);
                clearancePositions.add(moduleAnchor.translate(rotated.x(), rotated.y(), rotated.z()));
            }
        }
        if (variant.reservesExpansionBay()) {
            BlockPos3i moduleOffset = new BlockPos3i(
                    goal.physicalModuleCount() * spacing, 0, 0).rotateY(orientation);
            BlockPos3i futureAnchor = anchor.translate(moduleOffset.x(), 0, moduleOffset.z());
            for (BlockPos3i clearance : geometry.clearance().cells()) {
                BlockPos3i rotated = clearance.rotateY(orientation);
                clearancePositions.add(futureAnchor.translate(rotated.x(), rotated.y(), rotated.z()));
            }
        }
        if (expected.isEmpty() || expected.size() > MAX_PREVIEW_CELLS
                || expected.stream().map(ExpectedCell::position).distinct().count() != expected.size()) {
            return PreviewResult.failure("PREVIEW_GEOMETRY_INVALID");
        }
        Set<BlockPos3i> componentPositions = expected.stream()
                .map(ExpectedCell::position).collect(java.util.stream.Collectors.toSet());
        clearancePositions.removeAll(componentPositions);
        if (clearancePositions.size() + expected.size() > 4_096) {
            return PreviewResult.failure("PREVIEW_SURVEY_BUDGET_EXCEEDED");
        }
        expected.sort(Comparator.comparing((ExpectedCell value) -> value.position().x())
                .thenComparing(value -> value.position().y())
                .thenComparing(value -> value.position().z())
                .thenComparing(ExpectedCell::role));
        List<BlockPos3i> orderedClearance = clearancePositions.stream()
                .sorted(Comparator.comparing(BlockPos3i::x)
                        .thenComparing(BlockPos3i::y).thenComparing(BlockPos3i::z))
                .toList();

        ArrayList<PreviewCell> cells = new ArrayList<>();
        int place = 0;
        int reuse = 0;
        int clear = 0;
        int protectedCount = 0;
        int containerCount = 0;
        int unknown = 0;
        int hazard = 0;
        StringBuilder currentFingerprint = new StringBuilder();
        for (ExpectedCell target : expected) {
            BlockPos position = new BlockPos(
                    target.position().x(), target.position().y(), target.position().z());
            if (!player.serverLevel().hasChunkAt(position)) {
                return PreviewResult.failure("PREVIEW_CHUNK_NOT_LOADED");
            }
            BlockState state = player.serverLevel().getBlockState(position);
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(position);
            ResourceLocation currentId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            String current = currentId == null ? "minecraft:air" : currentId.toString();
            PreviewCategory category;
            String reason;
            if (current.equals(target.blockId().toString()) && blockEntity == null) {
                category = target.role().contains("power") || target.role().contains("drive")
                        ? PreviewCategory.SHARED_INFRASTRUCTURE : PreviewCategory.REUSE;
                reason = "EXPECTED_BLOCK_PRESENT";
                reuse++;
            } else if (state.isAir() || state.canBeReplaced()) {
                category = PreviewCategory.PLACE;
                reason = "REPLACEABLE";
                place++;
            } else {
                ObstacleFinding finding = CLASSIFIER.classify(
                        ForgeSiteSurveyAdapter.observe(player.serverLevel(), position));
                if (finding.container() || finding.hasInventory() || finding.blockEntity()) {
                    containerCount++;
                }
                category = switch (finding.classification()) {
                    case SAFE_NATURAL_CLEARABLE, CONFIRM_EACH_OR_GROUP -> PreviewCategory.CLEAR;
                    case PROTECTED_NO_AUTOMATIC_REMOVAL -> PreviewCategory.PROTECTED;
                    case ENVIRONMENTAL_HAZARD -> PreviewCategory.HAZARD;
                    case UNKNOWN -> PreviewCategory.UNKNOWN;
                };
                reason = finding.classification().name();
                switch (category) {
                    case CLEAR -> clear++;
                    case PROTECTED -> protectedCount++;
                    case UNKNOWN -> unknown++;
                    case HAZARD -> hazard++;
                    default -> throw new IllegalStateException("unexpected obstacle category");
                }
            }
            String stateHash = ForgeSiteSurveyAdapter.fingerprint(state);
            currentFingerprint.append(target.position()).append('|').append(stateHash).append('\n');
            cells.add(new PreviewCell(target.position(), target.blockId(), target.role(),
                    category, reason, stateHash));
        }
        for (BlockPos3i clearancePosition : orderedClearance) {
            BlockPos position = new BlockPos(
                    clearancePosition.x(), clearancePosition.y(), clearancePosition.z());
            if (!player.serverLevel().hasChunkAt(position)) {
                return PreviewResult.failure("PREVIEW_CHUNK_NOT_LOADED");
            }
            BlockState state = player.serverLevel().getBlockState(position);
            String stateHash = ForgeSiteSurveyAdapter.fingerprint(state);
            currentFingerprint.append(clearancePosition).append('|').append(stateHash).append('\n');
            if (state.isAir() || state.canBeReplaced()) continue;
            ObstacleFinding finding = CLASSIFIER.classify(
                    ForgeSiteSurveyAdapter.observe(player.serverLevel(), position));
            if (finding.container() || finding.hasInventory() || finding.blockEntity()) {
                containerCount++;
            }
            PreviewCategory category = switch (finding.classification()) {
                case SAFE_NATURAL_CLEARABLE, CONFIRM_EACH_OR_GROUP -> PreviewCategory.CLEAR;
                case PROTECTED_NO_AUTOMATIC_REMOVAL -> PreviewCategory.PROTECTED;
                case ENVIRONMENTAL_HAZARD -> PreviewCategory.HAZARD;
                case UNKNOWN -> PreviewCategory.UNKNOWN;
            };
            switch (category) {
                case CLEAR -> clear++;
                case PROTECTED -> protectedCount++;
                case UNKNOWN -> unknown++;
                case HAZARD -> hazard++;
                default -> throw new IllegalStateException("unexpected clearance category");
            }
            cells.add(new PreviewCell(clearancePosition, ResourceId.parse("minecraft:air"),
                    "layout_clearance", category, finding.classification().name(), stateHash));
        }
        if (cells.size() > MAX_PREVIEW_CELLS) {
            return PreviewResult.failure("PREVIEW_RENDER_BUDGET_EXCEEDED");
        }
        String planHash = sha256(project.projectId() + "\n" + project.target() + "\n"
                + project.quantity() + "\n" + anchor + "\n" + orientation + "\n" + variant
                + "\n" + expected + "\nclearance=" + orderedClearance);
        String snapshotHash = sha256(planHash + "\n" + currentFingerprint);
        LinkedHashSet<BlockPos3i> allRequired = new LinkedHashSet<>(componentPositions);
        allRequired.addAll(clearancePositions);
        Bounds bounds = bounds(allRequired);
        boolean safe = protectedCount == 0 && unknown == 0 && hazard == 0;
        return new PreviewResult(true, "OK", project.projectId(), project.nonce(), anchor,
                orientation, variant, List.copyOf(cells), bounds, planHash, snapshotHash,
                place, reuse, clear, protectedCount, containerCount, unknown, hazard, safe);
    }

    private static String validate(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            UUID projectId,
            long projectNonce,
            BlockPos3i anchor) {
        if (PilotWorldMarkerSavedData.forLevel(player.serverLevel()).marker().isEmpty()) {
            return "WORLD_NOT_AUTHORIZED";
        }
        if (project == null || !project.projectId().equals(projectId)) return "PROJECT_NOT_FOUND";
        if (project.nonce() != projectNonce) return "STALE_PROJECT_REQUEST";
        if (!project.dimension().toString().equals(
                player.serverLevel().dimension().location().toString())) return "DIMENSION_CHANGED";
        if (project.stage() != dev.stevecreate.agent.core.player.WorkflowStage.PLACEMENT_PREVIEW
                && project.stage() != dev.stevecreate.agent.core.player.WorkflowStage.SITE_SURVEY
                && project.stage() != dev.stevecreate.agent.core.player.WorkflowStage.AWAITING_APPROVAL) {
            return "PROJECT_STAGE_MISMATCH";
        }
        double distance = player.distanceToSqr(anchor.x() + 0.5D, anchor.y() + 0.5D, anchor.z() + 0.5D);
        if (distance > MAX_INTERACTION_DISTANCE_SQUARED) return "ANCHOR_OUT_OF_RANGE";
        if (anchor.y() < player.serverLevel().getMinBuildHeight()
                || anchor.y() >= player.serverLevel().getMaxBuildHeight()) return "ANCHOR_OUT_OF_WORLD";
        return null;
    }

    static ResourceId implementation(ResourceId capability) {
        return switch (capability.toString()) {
            case "create:milling" -> CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID;
            case "create:pressing" -> CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID;
            case "create:crushing" -> CreateRuntimeMachineImplementationCatalog.CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID;
            case "create:cutting" -> CreateRuntimeMachineImplementationCatalog.SAW_IMPLEMENTATION_ID;
            case "create:splashing" -> CreateRuntimeMachineImplementationCatalog.FAN_WASHING_IMPLEMENTATION_ID;
            case "minecraft:smoking" -> CreateRuntimeMachineImplementationCatalog.FAN_SMOKING_IMPLEMENTATION_ID;
            case "create:haunting" -> CreateRuntimeMachineImplementationCatalog.FAN_HAUNTING_IMPLEMENTATION_ID;
            case "minecraft:blasting" -> CreateRuntimeMachineImplementationCatalog.FAN_BLASTING_IMPLEMENTATION_ID;
            case "create:compacting" -> CreateRuntimeMachineImplementationCatalog.BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID;
            case "create:mixing" -> CreateRuntimeMachineImplementationCatalog.BASIN_MIXER_IMPLEMENTATION_ID;
            case "create:deploying" -> CreateRuntimeMachineImplementationCatalog.DEPLOYER_IMPLEMENTATION_ID;
            default -> ResourceId.parse("steve_industrial:unsupported");
        };
    }

    public static int spacing(LayoutVariant variant, MachineGeometryDescriptor geometry) {
        if (variant.fixedModuleSpacing() > 0) return variant.fixedModuleSpacing();
        int min = geometry.clearance().cells().stream().mapToInt(BlockPos3i::x).min().orElse(-1);
        int max = geometry.clearance().cells().stream().mapToInt(BlockPos3i::x).max().orElse(6);
        return Math.max(2, Math.min(15, max - min + 2));
    }

    private static Bounds bounds(Set<BlockPos3i> cells) {
        return new Bounds(
                cells.stream().mapToInt(BlockPos3i::x).min().orElseThrow(),
                cells.stream().mapToInt(BlockPos3i::y).min().orElseThrow(),
                cells.stream().mapToInt(BlockPos3i::z).min().orElseThrow(),
                cells.stream().mapToInt(BlockPos3i::x).max().orElseThrow(),
                cells.stream().mapToInt(BlockPos3i::y).max().orElseThrow(),
                cells.stream().mapToInt(BlockPos3i::z).max().orElseThrow());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum PreviewCategory {
        PLACE,
        CLEAR,
        REUSE,
        PROTECTED,
        SHARED_INFRASTRUCTURE,
        OUTSIDE_AUTHORITY,
        UNKNOWN,
        HAZARD
    }

    private record ExpectedCell(BlockPos3i position, ResourceId blockId, String role) {}

    public record PreviewCell(
            BlockPos3i position,
            ResourceId blockId,
            String role,
            PreviewCategory category,
            String reasonCode,
            String stateFingerprint) {
        public PreviewCell {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(stateFingerprint, "stateFingerprint");
        }
    }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    public record PreviewResult(
            boolean success,
            String statusCode,
            UUID projectId,
            long projectNonce,
            BlockPos3i anchor,
            QuarterTurn orientation,
            LayoutVariant variant,
            List<PreviewCell> cells,
            Bounds bounds,
            String planHash,
            String snapshotHash,
            int placeCount,
            int reuseCount,
            int clearCount,
            int protectedCount,
            int containerCount,
            int unknownCount,
            int hazardCount,
            boolean safeForConfirmation) {
        static PreviewResult failure(String code) {
            return new PreviewResult(false, code, null, 0, null, null, null,
                    List.of(), null, "", "", 0, 0, 0, 0, 0, 0, 0, false);
        }
    }
}
