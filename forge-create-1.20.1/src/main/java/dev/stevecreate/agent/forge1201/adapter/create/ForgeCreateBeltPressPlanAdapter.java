package dev.stevecreate.agent.forge1201.adapter.create;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionSession;
import dev.stevecreate.agent.adapter.api.CreateBeltPressPlanAdapter;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.placement.PlacementFeasibilityReport;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.Create606BeltPressExecutor;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.Create606RecoverySnapshotNormalizer;
import dev.stevecreate.agent.forge1201.recovery.ForgeRecoveryWorldScanner;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

/** Optional-mod-safe gate dedicated to the exact Forge 1.20.1/Create 6.0.6 C-04 executor. */
public final class ForgeCreateBeltPressPlanAdapter implements CreateBeltPressPlanAdapter {
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_VERSION_PREFIX = "47.4.";
    private static final String CREATE_VERSION = "6.0.6";

    private final ServerLevel level;
    private final RuntimeFingerprint runtime;
    private final Set<BlockPos3i> protectedPositions;

    public ForgeCreateBeltPressPlanAdapter(ServerLevel level) {
        this(level, Set.of());
    }

    public ForgeCreateBeltPressPlanAdapter(ServerLevel level, Set<BlockPos3i> protectedPositions) {
        this.level = Objects.requireNonNull(level, "level");
        this.runtime = new ForgeCreateKineticAdapter(level).runtime();
        this.protectedPositions = ForgeBasicPlacementFeasibility.copyProtectedPositions(protectedPositions);
    }

    @Override
    public ResourceId adapterId() {
        return new ResourceId("steve_industrial", "forge_1_20_1_create_6_0_6_belt_press_plan");
    }

    @Override
    public RuntimeFingerprint runtime() {
        return runtime;
    }

    @Override
    public AdapterResult<BeltPressExecutionSession> begin(BeltPressPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!level.getServer().isSameThread()) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.WRONG_THREAD,
                    "Create belt/press execution must begin on the authoritative server thread");
        }
        if (!ModList.get().isLoaded("create")) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Optional mod is not loaded: create");
        }
        String runtimeCreateVersion = runtime.industrialModVersions().get("create");
        if (!MINECRAFT_VERSION.equals(runtime.minecraftVersion())
                || !"forge".equals(runtime.loader())
                || !runtime.loaderVersion().startsWith(FORGE_VERSION_PREFIX)
                || !supportsCreateVersion(runtimeCreateVersion)) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Create belt/press adapter requires Minecraft 1.20.1, Forge 47.4.x and Create 6.0.6; found minecraft="
                            + runtime.minecraftVersion() + " forge=" + runtime.loaderVersion()
                            + " create=" + runtimeCreateVersion);
        }

        PlacementFeasibilityReport feasibility = ForgeBasicPlacementFeasibility.inspect(
                level, plan.placementTargets(), protectedPositions);
        if (!feasibility.feasible()) {
            return new AdapterResult.Failure<>(
                    ForgeBasicPlacementFeasibility.failureCode(feasibility),
                    ForgeBasicPlacementFeasibility.detail("C-04", feasibility));
        }

        for (BlockPos3i position : plan.preflightPositions()) {
            if (position.y() < level.getMinBuildHeight() || position.y() >= level.getMaxBuildHeight()) {
                return new AdapterResult.Failure<>(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Belt/press preflight position is outside the build height: " + position);
            }
            int chunkX = position.x() >> 4;
            int chunkZ = position.z() >> 4;
            if (!level.hasChunk(chunkX, chunkZ)) {
                return new AdapterResult.Failure<>(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "Refusing to load chunk during bounded belt/press preflight: " + chunkX + "," + chunkZ);
            }
        }
        return Create606BeltPressExecutor.begin(level, plan, runtime);
    }

    /** Resumes only a reconciled C-04 session; it never repeats feasibility or BUILD. */
    public AdapterResult<BeltPressExecutionSession> resume(
            BeltPressPlan plan,
            Resumable resumable) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(resumable, "resumable");
        if (!level.getServer().isSameThread()) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.WRONG_THREAD,
                    "Create belt/press recovery must run on the authoritative server thread");
        }
        if (!ModList.get().isLoaded("create")) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Optional mod is not loaded: create");
        }
        String runtimeCreateVersion = runtime.industrialModVersions().get("create");
        if (!MINECRAFT_VERSION.equals(runtime.minecraftVersion())
                || !"forge".equals(runtime.loader())
                || !runtime.loaderVersion().startsWith(FORGE_VERSION_PREFIX)
                || !supportsCreateVersion(runtimeCreateVersion)) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Create belt/press adapter requires Minecraft 1.20.1, Forge 47.4.x and Create 6.0.6; found minecraft="
                            + runtime.minecraftVersion() + " forge=" + runtime.loaderVersion()
                            + " create=" + runtimeCreateVersion);
        }
        for (BlockPos3i position : plan.preflightPositions()) {
            if (position.y() < level.getMinBuildHeight() || position.y() >= level.getMaxBuildHeight()) {
                return new AdapterResult.Failure<>(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Belt/press recovery position is outside the build height: " + position);
            }
            int chunkX = position.x() >> 4;
            int chunkZ = position.z() >> 4;
            if (!level.hasChunk(chunkX, chunkZ)) {
                return new AdapterResult.Failure<>(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "Refusing to load chunk during bounded belt/press recovery preflight: "
                                + chunkX + "," + chunkZ);
            }
        }
        return Create606BeltPressExecutor.resume(level, plan, runtime, resumable);
    }

    /**
     * Performs the bounded authoritative C-04 recovery scan and removes only Create 6.0.6's
     * transient kinetic {@code NeedsSpeedUpdate} byte before core exact reconciliation.
     */
    public AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> scanRecoveryState(
            BeltPressPlan plan,
            List<BlockPos3i> positions) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(positions, "positions");
        if (!level.getServer().isSameThread()) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.WRONG_THREAD,
                    "Create belt/press recovery scan must run on the authoritative server thread");
        }
        if (!ModList.get().isLoaded("create")) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Optional mod is not loaded: create");
        }
        String runtimeCreateVersion = runtime.industrialModVersions().get("create");
        if (!MINECRAFT_VERSION.equals(runtime.minecraftVersion())
                || !"forge".equals(runtime.loader())
                || !runtime.loaderVersion().startsWith(FORGE_VERSION_PREFIX)
                || !supportsCreateVersion(runtimeCreateVersion)) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Create belt/press recovery scan requires Minecraft 1.20.1, Forge 47.4.x and Create 6.0.6; found minecraft="
                            + runtime.minecraftVersion() + " forge=" + runtime.loaderVersion()
                            + " create=" + runtimeCreateVersion);
        }
        // Every position the plan owns, not only its placements: the pilot flow cells
        // below each water source are journalled by the build handler, so a checkpoint
        // taken on a survival-power topology always carries them. Accepting only the
        // placements refused every such checkpoint, which meant C-04 could not resume
        // from a restart at all once it stopped running on a creative motor.
        List<BlockPos3i> expectedPositions = plan.ownedPositions();
        if (positions.size() != expectedPositions.size()
                || new HashSet<>(positions).size() != positions.size()
                || !new HashSet<>(positions).equals(new HashSet<>(expectedPositions))) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-04 recovery scan positions do not match the trusted physical plan");
        }
        AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> scanned =
                ForgeRecoveryWorldScanner.scan(level, positions);
        if (scanned instanceof AdapterResult.Failure<Map<BlockPos3i, WorldBlockSnapshot>> failure) {
            return failure;
        }
        Map<BlockPos3i, WorldBlockSnapshot> snapshots =
                ((AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>>) scanned).value();
        return new AdapterResult.Success<>(
                Create606RecoverySnapshotNormalizer.normalizeKineticSnapshots(level, snapshots));
    }

    private static boolean supportsCreateVersion(String version) {
        return CREATE_VERSION.equals(version) || (version != null && version.startsWith(CREATE_VERSION + "-"));
    }
}
