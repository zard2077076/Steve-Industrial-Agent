package dev.stevecreate.agent.forge1201.adapter;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Side-neutral Forge runtime adapter for optional-mod availability.
 *
 * <p>This adapter performs no world access. It returns the same typed absence
 * used by world adapters, which lets a real client lifecycle verify optional
 * runtime safety before any server-authoritative world exists.</p>
 */
public final class ForgeRuntimeAvailabilityAdapter {
    private static final int SCHEMA_VERSION = 1;
    private static final List<String> INDUSTRIAL_MOD_IDS = List.of(
            "create", "mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions");

    private final String targetModId;
    private final RuntimeFingerprint runtime;

    public ForgeRuntimeAvailabilityAdapter(String targetModId) {
        this.targetModId = requireModId(targetModId);
        this.runtime = new RuntimeFingerprint(
                SharedConstants.getCurrentVersion().getName(),
                "forge",
                FMLLoader.versionInfo().forgeVersion(),
                loadedIndustrialModVersions(),
                adapterId().toString(),
                SCHEMA_VERSION);
    }

    public ResourceId adapterId() {
        return new ResourceId("steve_industrial", "forge_1_20_1_runtime_" + targetModId);
    }

    public String targetModId() {
        return targetModId;
    }

    public RuntimeFingerprint runtime() {
        return runtime;
    }

    public AdapterResult<RuntimeFingerprint> probe() {
        if (!ModList.get().isLoaded(targetModId)) {
            return new AdapterResult.Failure<>(AdapterFailureCode.UNSUPPORTED_RUNTIME,
                    "Optional mod is not loaded: " + targetModId);
        }
        return new AdapterResult.Success<>(runtime);
    }

    private static Map<String, String> loadedIndustrialModVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        for (String modId : INDUSTRIAL_MOD_IDS) {
            ModList.get().getModContainerById(modId).ifPresent(container ->
                    versions.put(modId, container.getModInfo().getVersion().toString()));
        }
        return versions;
    }

    private static String requireModId(String modId) {
        if (modId == null || !modId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid mod id: " + modId);
        }
        return modId;
    }
}
