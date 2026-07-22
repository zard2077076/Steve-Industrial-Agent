package dev.stevecreate.agent.forge1201.adapter;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.IndustrialModAdapter;
import dev.stevecreate.agent.adapter.api.ObservedComponent;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.adapter.api.WorldSnapshot;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Version-tolerant Phase 1 adapter that uses only Forge registry and block-state APIs.
 * Mod-internal Create/Mekanism access will live in sibling internal packages.
 */
public final class ForgeRegistryIndustrialAdapter implements IndustrialModAdapter {
    private static final int SCHEMA_VERSION = 1;

    private final ServerLevel level;
    private final String targetModId;
    private final RuntimeFingerprint runtime;

    public ForgeRegistryIndustrialAdapter(ServerLevel level, String targetModId) {
        this.level = java.util.Objects.requireNonNull(level, "level");
        this.targetModId = requireModId(targetModId);
        Map<String, String> versions = new LinkedHashMap<>();
        for (String id : List.of("create", "mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions")) {
            ModList.get().getModContainerById(id).ifPresent(container ->
                    versions.put(id, container.getModInfo().getVersion().toString()));
        }
        this.runtime = new RuntimeFingerprint(
                net.minecraft.SharedConstants.getCurrentVersion().getName(),
                "forge",
                FMLLoader.versionInfo().forgeVersion(),
                versions,
                adapterId().toString(),
                SCHEMA_VERSION);
    }

    @Override
    public ResourceId adapterId() {
        return new ResourceId("steve_industrial", "forge_1_20_1_registry_" + targetModId);
    }

    @Override
    public String targetModId() {
        return targetModId;
    }

    @Override
    public RuntimeFingerprint runtime() {
        return runtime;
    }

    @Override
    public AdapterResult<WorldSnapshot> capture(ScanRequest request) {
        if (!level.getServer().isSameThread()) {
            return new AdapterResult.Failure<>(AdapterFailureCode.WRONG_THREAD,
                    "World capture must run on the authoritative server thread");
        }
        if (!ModList.get().isLoaded(targetModId)) {
            return new AdapterResult.Failure<>(AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Optional mod is not loaded: " + targetModId);
        }

        BlockPos3i center = request.center();
        int minChunkX = (center.x() - request.radius()) >> 4;
        int maxChunkX = (center.x() + request.radius()) >> 4;
        int minChunkZ = (center.z() - request.radius()) >> 4;
        int maxChunkZ = (center.z() + request.radius()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return new AdapterResult.Failure<>(AdapterFailureCode.CHUNK_NOT_LOADED,
                            "Refusing to load chunk during read-only scan: " + chunkX + "," + chunkZ);
                }
            }
        }

        List<ObservedComponent> components = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = center.x() - request.radius(); x <= center.x() + request.radius(); x++) {
            for (int y = center.y() - request.radius(); y <= center.y() + request.radius(); y++) {
                for (int z = center.z() - request.radius(); z <= center.z() + request.radius(); z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (key != null && key.getNamespace().equals(targetModId)) {
                        components.add(new ObservedComponent(
                                new ResourceId(key.getNamespace(), key.getPath()),
                                new BlockPos3i(x, y, z),
                                readState(state)));
                    }
                }
            }
        }
        return new AdapterResult.Success<>(new WorldSnapshot(
                UUID.randomUUID(), level.getGameTime(), runtime, center, request.radius(), components));
    }

    private static Map<String, String> readState(BlockState state) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            values.put(entry.getKey().getName(), valueName(entry.getKey(), entry.getValue()));
        }
        return values;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(Property property, Comparable value) {
        return property.getName(value);
    }

    private static String requireModId(String modId) {
        if (modId == null || !modId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid mod id: " + modId);
        }
        return modId;
    }
}
