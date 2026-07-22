package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.adapter.ForgeRuntimeAvailabilityAdapter;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/** Development-only, auto-closing client acceptance enabled by an isolated run profile. */
@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientRuntimeProfileAcceptance {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RUNTIME_PROFILE_PROPERTY =
            "steve_industrial.test.runtimeProfile";
    private static final int MAX_CLIENT_TICKS = 2_400;
    private static final Map<String, Map<String, ExpectedAdapterOutcome>> CLIENT_PROFILES = Map.of(
            "neither-client", Map.of(
                    "create", ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME,
                    "mekanism", ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME),
            "create-only-client", Map.of(
                    "create", ExpectedAdapterOutcome.SUCCESS,
                    "mekanism", ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME));

    private static boolean renderedScreen;
    private static boolean verificationStarted;
    private static int clientTicks;
    private static String screenClass;

    private ClientRuntimeProfileAcceptance() {
    }

    @SubscribeEvent
    public static void afterScreenRender(ScreenEvent.Render.Post event) {
        String profile = activeProfile();
        if (profile == null || renderedScreen) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            failAndStop(profile, "Screen render event did not run on the Minecraft client thread");
            return;
        }

        renderedScreen = true;
        screenClass = event.getScreen().getClass().getName();
        LOGGER.info(
                "CLIENT_RUNTIME_PROFILE_LIFECYCLE READY profile={} point=SCREEN_RENDER_POST screen={} minecraft={} forge={} clientThread=true",
                profile,
                screenClass,
                SharedConstants.getCurrentVersion().getName(),
                FMLLoader.versionInfo().forgeVersion());
    }

    @SubscribeEvent
    public static void afterClientTick(TickEvent.ClientTickEvent event) {
        String profile = activeProfile();
        if (profile == null || event.phase != TickEvent.Phase.END || verificationStarted) {
            return;
        }

        clientTicks++;
        if (!renderedScreen) {
            if (clientTicks >= MAX_CLIENT_TICKS) {
                failAndStop(profile, "No screen completed rendering within " + MAX_CLIENT_TICKS + " client ticks");
            }
            return;
        }

        verificationStarted = true;
        verifyAndStop(profile);
    }

    private static void verifyAndStop(String profile) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            failAndStop(profile, "Runtime probes did not run on the Minecraft client thread");
            return;
        }

        Map<String, ExpectedAdapterOutcome> expectations = CLIENT_PROFILES.get(profile);
        if (expectations == null) {
            failAndStop(profile, "Unknown client runtime profile: " + profile);
            return;
        }

        String agentVersion = ModList.get()
                .getModContainerById(SteveIndustrialAgentMod.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
        if (agentVersion == null) {
            failAndStop(profile, "Steve Industrial Agent mod container is not loaded");
            return;
        }
        LOGGER.info("CLIENT_RUNTIME_PROFILE_AGENT READY profile={} mod={} version={}",
                profile, SteveIndustrialAgentMod.MOD_ID, agentVersion);

        if ("create-only-client".equals(profile)) {
            verifyCreateOptionalCompatTarget(profile);
        }

        for (String modId : List.of("create", "mekanism")) {
            ExpectedAdapterOutcome expected = expectations.get(modId);
            AdapterResult<RuntimeFingerprint> result =
                    new ForgeRuntimeAvailabilityAdapter(modId).probe();
            if (result instanceof AdapterResult.Success<RuntimeFingerprint> success) {
                if (expected != ExpectedAdapterOutcome.SUCCESS) {
                    failAndStop(profile, "Expected " + modId + " outcome " + expected
                            + " but runtime adapter returned SUCCESS");
                    return;
                }
                String version = success.value().industrialModVersions().get(modId);
                if (version == null || version.isBlank()) {
                    failAndStop(profile, "Runtime adapter returned success without a " + modId + " version");
                    return;
                }
                LOGGER.info(
                        "CLIENT_RUNTIME_PROFILE_RESULT profile={} mod={} outcome=SUCCESS version={} adapter={}",
                        profile, modId, version, success.value().adapterId());
            } else if (result instanceof AdapterResult.Failure<RuntimeFingerprint> failure) {
                String expectedDetail = "Optional mod is not loaded: " + modId;
                if (expected != ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME
                        || failure.code() != AdapterFailureCode.UNSUPPORTED_RUNTIME
                        || !expectedDetail.equals(failure.detail())) {
                    failAndStop(profile, "Expected " + modId + " outcome " + expected
                            + " but runtime adapter returned " + result);
                    return;
                }
                LOGGER.info(
                        "CLIENT_RUNTIME_PROFILE_RESULT profile={} mod={} outcome=FAILURE code={} detail=\"{}\"",
                        profile, modId, failure.code(), failure.detail());
            }
        }

        LOGGER.info("CLIENT_RUNTIME_PROFILE_SMOKE PASS profile={} create={} mekanism={} screen={} ticks={}",
                profile,
                expectations.get("create").marker(),
                expectations.get("mekanism").marker(),
                screenClass,
                clientTicks);
        LOGGER.info("CLIENT_RUNTIME_PROFILE_EXIT REQUESTED profile={} reason=verified", profile);
        minecraft.stop();
    }

    private static void verifyCreateOptionalCompatTarget(String profile) {
        if (ModList.get().isLoaded("journeymap")) {
            failAndStop(profile, "create-only-client must not install the optional JourneyMap mod");
            return;
        }
        LOGGER.info(
                "CLIENT_RUNTIME_PROFILE_OPTIONAL_COMPAT READY profile={} journeymapModLoaded=false targetSource=acceptance_only_unshipped",
                profile);
    }

    private static String activeProfile() {
        String profile = System.getProperty(RUNTIME_PROFILE_PROPERTY);
        return profile == null || profile.isBlank() ? null : profile;
    }

    private static void failAndStop(String profile, String detail) {
        LOGGER.error("CLIENT_RUNTIME_PROFILE_SMOKE FAIL profile={} detail=\"{}\"", profile, detail);
        LOGGER.info("CLIENT_RUNTIME_PROFILE_EXIT REQUESTED profile={} reason=verification_failed", profile);
        Minecraft.getInstance().stop();
        throw new IllegalStateException(detail);
    }

    private enum ExpectedAdapterOutcome {
        SUCCESS,
        UNSUPPORTED_RUNTIME;

        private String marker() {
            return this == SUCCESS ? "SUCCESS" : "FAILURE:UNSUPPORTED_RUNTIME";
        }
    }
}
