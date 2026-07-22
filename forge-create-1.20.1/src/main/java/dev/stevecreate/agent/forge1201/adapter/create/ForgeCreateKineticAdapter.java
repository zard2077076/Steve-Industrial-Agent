package dev.stevecreate.agent.forge1201.adapter.create;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CreateVersionAdapter;
import dev.stevecreate.agent.adapter.api.KineticCaptureRequest;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.adapter.api.WorldSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.adapter.ForgeRegistryIndustrialAdapter;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.Create606KineticReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

/** Optional-mod-safe facade. Direct Create references remain in the exact v606 implementation package. */
public final class ForgeCreateKineticAdapter implements CreateVersionAdapter {
    private static final int SCHEMA_VERSION = 2;
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_VERSION_PREFIX = "47.4.";
    private static final String CREATE_VERSION = "6.0.6";

    private final ServerLevel level;
    private final RuntimeFingerprint runtime;

    public ForgeCreateKineticAdapter(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        Map<String, String> versions = new LinkedHashMap<>();
        for (String id : List.of("create", "mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions")) {
            ModList.get().getModContainerById(id).ifPresent(container ->
                    versions.put(id, container.getModInfo().getVersion().toString()));
        }
        this.runtime = new RuntimeFingerprint(
                SharedConstants.getCurrentVersion().getName(),
                "forge",
                FMLLoader.versionInfo().forgeVersion(),
                versions,
                adapterId().toString(),
                SCHEMA_VERSION);
    }

    @Override
    public ResourceId adapterId() {
        return new ResourceId("steve_industrial", "forge_1_20_1_create_6_0_6");
    }

    @Override
    public RuntimeFingerprint runtime() {
        return runtime;
    }

    @Override
    public AdapterResult<WorldSnapshot> capture(ScanRequest request) {
        AdapterResult<WorldSnapshot> result = new ForgeRegistryIndustrialAdapter(level, "create").capture(request);
        if (result instanceof AdapterResult.Success<WorldSnapshot> success) {
            WorldSnapshot snapshot = success.value();
            return new AdapterResult.Success<>(new WorldSnapshot(
                    snapshot.snapshotId(),
                    snapshot.gameTick(),
                    runtime,
                    snapshot.center(),
                    snapshot.radius(),
                    snapshot.components()));
        }
        return result;
    }

    @Override
    public AdapterResult<KineticSnapshot> captureKinetics(KineticCaptureRequest request) {
        Objects.requireNonNull(request, "request");
        if (!level.getServer().isSameThread()) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.WRONG_THREAD,
                    "Kinetic capture must run on the authoritative server thread");
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
                    "Create kinetic adapter requires Minecraft 1.20.1, Forge 47.4.x and Create 6.0.6; found minecraft="
                            + runtime.minecraftVersion() + " forge=" + runtime.loaderVersion()
                            + " create=" + runtimeCreateVersion);
        }
        int chunkX = request.position().x() >> 4;
        int chunkZ = request.position().z() >> 4;
        if (!level.hasChunk(chunkX, chunkZ)) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "Refusing to load chunk during kinetic point capture: " + chunkX + "," + chunkZ);
        }
        return Create606KineticReader.capture(level, request.position(), runtime);
    }

    private static boolean supportsCreateVersion(String version) {
        return CREATE_VERSION.equals(version) || (version != null && version.startsWith(CREATE_VERSION + "-"));
    }
}
