package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeImplementationBindingService;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgePlanningService;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimePlanningResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.core.binding.BindingConstraints;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.deployment.DeploymentBlockObservation;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.deployment.DeploymentBudgetContext;
import dev.stevecreate.agent.core.deployment.DeploymentBudgetService;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunReport;
import dev.stevecreate.agent.core.deployment.DeploymentFailure;
import dev.stevecreate.agent.core.deployment.DeploymentFailureCode;
import dev.stevecreate.agent.core.deployment.DeploymentFailureStage;
import dev.stevecreate.agent.core.deployment.DeploymentPermissionRisk;
import dev.stevecreate.agent.core.deployment.DeploymentPolicy;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewContext;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewService;
import dev.stevecreate.agent.core.deployment.DeploymentRiskAssessor;
import dev.stevecreate.agent.core.deployment.DeploymentRiskContext;
import dev.stevecreate.agent.core.deployment.ResourceSourcePolicy;
import dev.stevecreate.agent.core.deployment.RollbackClassification;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentDescriptor;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.layout.LayoutConstraints;
import dev.stevecreate.agent.core.layout.PhysicalizationResult;
import dev.stevecreate.agent.core.layout.PhysicalizationService;
import dev.stevecreate.agent.core.layout.PhysicalizationSuccess;
import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.command.PublicAlphaRuntime;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;

/** Server-thread read-only pipeline that stops at an auditable PW-12 report. */
public final class CreateV606DeploymentDryRunService {
    private static final ResourceId ADAPTER_ID = ResourceId.parse(
            "steve_industrial:create_runtime_1_20_1_6_0_6");
    private static final ResourceId ROUTE_MATERIAL = ResourceId.parse("create:andesite_casing");
    public static final String EXPECTED_GAME_DIR =
            "steve_industrial.deployment.expectedGameDir";
    public static final String FORBIDDEN_ROOT =
            "steve_industrial.deployment.forbiddenRoot";
    public static final String MARKER = ".steve-industrial-deployment-dry-run";
    public static final String MARKER_VALUE = "steve-industrial:deployment-dry-run/v1";

    private CreateV606DeploymentDryRunService() {}

    public static Result preview(
            ServerLevel level,
            ResourceId target,
            long quantity,
            BlockPos3i anchor,
            QuarterTurn orientation) {
        WorldEnvironmentDescriptor environment = environment(level, "runtime:unavailable");
        if (!level.getServer().isSameThread()) {
            return failure(environment, target, "runtime:unavailable",
                    DeploymentFailureCode.WRONG_THREAD,
                    "Deployment dry-run must run on the authoritative server thread");
        }
        try {
            RuntimeRecipeCatalogResult recipeResult =
                    ForgeCreateRuntimeRecipeCatalogs.forLevel(level).snapshot();
            if (!(recipeResult instanceof RuntimeRecipeCatalogResult.Success recipes)) {
                return failure(environment, target, "runtime:unavailable",
                        DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                        "Runtime recipe snapshot failed: " + recipeResult);
            }
            String runtimeFingerprint = recipes.snapshot().runtimeFingerprint();
            environment = environment(level, runtimeFingerprint);
            RuntimeMachineCapabilityCatalogResult capabilityResult =
                    new CreateRuntimeMachineCapabilityCatalog().snapshot(recipes.snapshot());
            if (!(capabilityResult instanceof RuntimeMachineCapabilityCatalogResult.Success capabilities)) {
                return failure(environment, target, runtimeFingerprint,
                        DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                        "Runtime capability snapshot failed: " + capabilityResult);
            }
            RuntimeMachineImplementationCatalogResult implementationResult =
                    new CreateRuntimeMachineImplementationCatalog().snapshot(
                            recipes.snapshot(), capabilities.snapshot(), false);
            if (!(implementationResult
                    instanceof RuntimeMachineImplementationCatalogResult.Success implementations)) {
                return failure(environment, target, runtimeFingerprint,
                        DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                        "Runtime implementation snapshot failed: " + implementationResult);
            }
            ProductionGoal goal = new ProductionGoal(
                    target, GenericResourceType.ITEM, quantity, Set.of(), Set.of(), Optional.of(8),
                    MaterialConstraints.none(), List.of(
                            PlanningStrategyPreference.MINIMIZE_STEPS,
                            PlanningStrategyPreference.PREFER_OWNED_RESOURCES), Map.of());
            RuntimePlanningResult planning = new RuntimeKnowledgePlanningService().plan(
                    recipes.snapshot(), capabilities.snapshot(), goal);
            if (!(planning instanceof RuntimePlanningResult.Success planned)) {
                return failure(environment, target, runtimeFingerprint,
                        DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                        "Runtime planning failed: " + planning);
            }
            BindingResult binding = new RuntimeImplementationBindingService().bind(
                    planned.result(), implementations.snapshot(), BindingConstraints.forRuntime(
                            ADAPTER_ID, recipes.snapshot().runtime().industrialModVersions(),
                            runtimeFingerprint));
            if (!(binding instanceof BindingResult.Success bound)) {
                return failure(environment, target, runtimeFingerprint,
                        DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                        "Implementation binding failed: " + binding);
            }
            PlacementSnapshot snapshot = Boolean.getBoolean(
                    "steve_industrial.r09.packProfile")
                    ? ForgeReadOnlyPlacementSnapshots.captureWithBoundedChunkReads(
                            level, runtimeFingerprint, anchor, 40, 2, 10,
                            recipes.snapshot().reloadGeneration())
                    : ForgeReadOnlyPlacementSnapshots.capture(
                            level, runtimeFingerprint, anchor, 40, 2, 10,
                            recipes.snapshot().reloadGeneration());
            PhysicalizationResult physicalization = new PhysicalizationService().physicalize(
                    bound.plan(), CreateV606MachineGeometryCatalog.create(runtimeFingerprint),
                    new LayoutConstraints(anchor, List.of(orientation), 1, 64, 128, 200_000,
                            64, 64, 128, snapshot));
            if (!(physicalization instanceof PhysicalizationSuccess physical)) {
                return failure(environment, target, runtimeFingerprint,
                        DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                        "Physicalization failed: " + physicalization);
            }
            String forbiddenRoot = configuredForbiddenRoot(environment.gameDirectoryIdentity());
            DeploymentPolicy policy = environment.environmentType()
                    == WorldEnvironmentType.ISOLATED_TEST_WORLD
                    ? DeploymentPolicy.isolatedTestDefault(
                            environment.gameDirectoryIdentity(), forbiddenRoot)
                    : DeploymentPolicy.formalWorldDryRunOnly(forbiddenRoot);
            Map<BlockPos3i, DeploymentBlockObservation> observations = observations(
                    level, physical.plan());
            long routeCells = physical.plan().routes().stream()
                    .flatMap(route -> route.positions().stream()).distinct().count();
            var preview = new DeploymentPreviewService().preview(
                    physical.plan(), new DeploymentPreviewContext(
                            environment, policy, snapshotFingerprint(snapshot), observations,
                            Map.of(ROUTE_MATERIAL, Math.max(1, routeCells)),
                            RollbackClassification.FULLY_REVERSIBLE,
                            List.of("read-only modeled rotational source; live capacity not reserved")));
            List<BlockPos3i> fluids = new ArrayList<>();
            List<BlockPos3i> fireOrLava = new ArrayList<>();
            for (BlockPos3i position : observations.keySet()) {
                BlockPos minecraftPosition = new BlockPos(position.x(), position.y(), position.z());
                var state = level.getBlockState(minecraftPosition);
                if (!state.getFluidState().isEmpty()) fluids.add(position);
                if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                        || state.is(Blocks.LAVA)) fireOrLava.add(position);
            }
            var risks = new DeploymentRiskAssessor().assess(preview, new DeploymentRiskContext(
                    ResourceId.parse(level.dimension().location().toString()),
                    policy.allowedDimensionIds().isEmpty() || policy.allowedDimensionIds().contains(
                            ResourceId.parse(level.dimension().location().toString())),
                    !preview.plannedReplacements().isEmpty(),
                    !preview.protectedBlocksEncountered().isEmpty(), fluids, fireOrLava,
                    false, 0, preview.stressDemand(), false, Map.of(), true, false,
                    DeploymentPermissionRisk.UNKNOWN, false, true, runtimeFingerprint));
            Map<ResourceId, Long> power = matching(
                    preview.materialBillOfMaterials(), "water_wheel", "shaft", "gearbox", "motor");
            Map<ResourceId, Long> logistics = matching(
                    preview.materialBillOfMaterials(), "belt", "funnel", "chest", "casing");
            ResourceSourcePolicy sourcePolicy = environment.environmentType()
                    == WorldEnvironmentType.ISOLATED_TEST_WORLD
                    ? ResourceSourcePolicy.TEST_FIXTURE_PROVIDED
                    : ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN;
            var budget = new DeploymentBudgetService().calculate(preview, policy,
                    new DeploymentBudgetContext(Map.of(), power, logistics,
                            preview.stressDemand(), Math.max(1, preview.journalEstimate() * 20),
                            preview.journalEstimate() * 4_096, 256, sourcePolicy, true));
            List<String> missingAuthorization = List.of(
                    "REGION_AUTHORIZATION", "CLAIM_PERMISSION", "HUMAN_APPROVAL");
            List<String> missingBackup = List.of(
                    "BACKUP_PLAN", "BACKUP_MANIFEST", "RESTORE_VERIFICATION");
            List<String> blockers = new ArrayList<>();
            blockers.add("AUTHORIZATION_MISSING");
            blockers.add("BACKUP_MISSING");
            if (risks.approvalBlocked()) blockers.add("RISK_APPROVAL_BLOCKED");
            if (!budget.withinPolicy()) blockers.add("BUDGET_POLICY_VIOLATION");
            if (environment.environmentType() != WorldEnvironmentType.ISOLATED_TEST_WORLD) {
                blockers.add("EXECUTION_ENVIRONMENT_FORBIDDEN");
            }
            return new Success(new DeploymentDryRunReport(
                    preview, risks, budget, orientation, missingAuthorization, missingBackup,
                    blockers, true, false, false, false, false, false, false, false));
        } catch (RuntimeException exception) {
            return failure(environment, target, environment.runtimeFingerprint(),
                    DeploymentFailureCode.DEPLOYMENT_NOT_READY,
                    "Deployment dry-run failed closed: " + exception.getMessage());
        }
    }

    private static WorldEnvironmentDescriptor environment(
            ServerLevel level,
            String runtimeFingerprint) {
        Path gameDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize();
        boolean isolatedProof = isolatedDryRunProof(gameDirectory, worldRoot);
        String configuredForbidden = System.getProperty(FORBIDDEN_ROOT);
        WorldEnvironmentType type = isolatedProof
                ? WorldEnvironmentType.ISOLATED_TEST_WORLD
                : configuredForbidden != null && !configuredForbidden.isBlank()
                        && pathKey(gameDirectory).startsWith(pathKey(Path.of(configuredForbidden)))
                        ? WorldEnvironmentType.FORMAL_PLAYER_WORLD
                        : WorldEnvironmentType.UNKNOWN_WORLD;
        boolean isolated = type == WorldEnvironmentType.ISOLATED_TEST_WORLD;
        return new WorldEnvironmentDescriptor(
                "deployment-command:" + type.name().toLowerCase(), type,
                level.dimension().location() + "@" + pathKey(worldRoot), pathKey(worldRoot),
                pathKey(gameDirectory), "1.20.1", "forge:create:v606", runtimeFingerprint,
                "read-only-placement:" + runtimeFingerprint,
                "dedicated-server", isolated, false, true, true, false,
                "PW-12 authoritative read-only command classification",
                List.of(isolated
                        ? "exact dry-run gameDir, forbidden root and isolated marker verified"
                        : "isolated execution proof absent; classification fails closed"),
                isolated ? List.of() : List.of("formal or unknown execution remains forbidden"));
    }

    private static boolean isolatedDryRunProof(Path gameDirectory, Path worldRoot) {
        var level = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (level != null) {
            PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(level.overworld(), true);
            if (result instanceof PublicAlphaRuntime.Success success
                    && success.authorization().gameDir().equals(gameDirectory.toAbsolutePath().normalize())
                    && worldRoot.toAbsolutePath().normalize().equals(
                            success.authorization().worldRoot())) return true;
        }
        try {
            String expectedValue = System.getProperty(EXPECTED_GAME_DIR);
            String forbiddenValue = System.getProperty(FORBIDDEN_ROOT);
            if (expectedValue == null || expectedValue.isBlank()
                    || forbiddenValue == null || forbiddenValue.isBlank()) return false;
            Path actual = gameDirectory.toRealPath();
            Path expected = Path.of(expectedValue).toRealPath();
            Path forbidden = Path.of(forbiddenValue).toRealPath();
            Path marker = actual.resolve(MARKER);
            return actual.equals(expected) && !actual.startsWith(forbidden)
                    && worldRoot.toRealPath().startsWith(actual)
                    && Files.isRegularFile(marker)
                    && MARKER_VALUE.equals(Files.readString(
                            marker, StandardCharsets.US_ASCII).trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String configuredForbiddenRoot(String failClosedIdentity) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(server.overworld(), true);
            if (result instanceof PublicAlphaRuntime.Success success) {
                return success.authorization().importantRoots().get(0).toString();
            }
        }
        String value = System.getProperty(FORBIDDEN_ROOT);
        return value == null || value.isBlank() ? failClosedIdentity : value;
    }

    private static Map<BlockPos3i, DeploymentBlockObservation> observations(
            ServerLevel level,
            VerifiedPhysicalPlan physical) {
        Set<BlockPos3i> positions = new LinkedHashSet<>();
        physical.placements().forEach(placement -> {
            placement.components().forEach(component -> positions.add(component.position()));
            positions.addAll(placement.rotationalPowerRoute());
        });
        physical.routes().forEach(route -> positions.addAll(route.positions()));
        Map<BlockPos3i, DeploymentBlockObservation> result = new TreeMap<>(Comparator
                .comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y)
                .thenComparingInt(BlockPos3i::z));
        for (BlockPos3i position : positions) {
            BlockPos minecraftPosition = new BlockPos(position.x(), position.y(), position.z());
            if (!level.hasChunkAt(minecraftPosition)) continue;
            var state = level.getBlockState(minecraftPosition);
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            boolean blockEntity = level.getBlockEntity(minecraftPosition) != null;
            result.put(position, new DeploymentBlockObservation(
                    position, ResourceId.parse(id == null ? "minecraft:air" : id.toString()),
                    !state.canBeReplaced(), blockEntity, false));
        }
        return result;
    }

    private static Map<ResourceId, Long> matching(
            Map<ResourceId, Long> materials,
            String... fragments) {
        Map<ResourceId, Long> result = new LinkedHashMap<>();
        materials.forEach((id, quantity) -> {
            for (String fragment : fragments) {
                if (id.path().contains(fragment)) {
                    result.put(id, quantity);
                    break;
                }
            }
        });
        return result;
    }

    private static String snapshotFingerprint(PlacementSnapshot snapshot) {
        StringBuilder canonical = new StringBuilder(snapshot.runtimeFingerprint())
                .append('|').append(snapshot.snapshotGeneration());
        snapshot.cells().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator
                        .comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z)))
                .forEach(entry -> canonical.append('|').append(entry.getKey())
                        .append('=').append(entry.getValue()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Failure failure(
            WorldEnvironmentDescriptor environment,
            ResourceId target,
            String runtimeFingerprint,
            DeploymentFailureCode code,
            String reason) {
        return new Failure(new DeploymentFailure(
                code, DeploymentFailureStage.PLAN, environment.worldIdentity(),
                environment.environmentType(), environment.gameDirectoryIdentity(), target,
                "0".repeat(64), new DeploymentBoundingBox(
                        new BlockPos3i(0, 0, 0), new BlockPos3i(0, 0, 0)),
                "dry-run-only", "UNKNOWN", "MISSING", "MISSING",
                runtimeFingerprint == null || runtimeFingerprint.isBlank()
                        ? "runtime:unavailable" : runtimeFingerprint,
                List.of("PW-12", "server-authoritative", "read-only"), reason, false,
                "Refresh the isolated runtime evidence and retry the typed dry-run command"));
    }

    private static String pathKey(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase();
    }

    public sealed interface Result permits Success, Failure {}
    public record Success(DeploymentDryRunReport report) implements Result {}
    public record Failure(DeploymentFailure failure) implements Result {}
}
