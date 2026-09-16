package dev.stevecreate.agent.forge1201;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.IndustrialModAdapter;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.adapter.api.WorldSnapshot;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.forge1201.acceptance.CreateScanAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.CreateKineticsAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.CreateRuntimeRecipeCatalogAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.C03RecoveryReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.C04RecoveryReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.ImmersiveEngineeringV1020AdapterAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.CompositePlayerOrderAcceptanceRunner;
import dev.stevecreate.agent.forge1201.acceptance.CompositeReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.CompositeResumeAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.WarehouseRestartAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.WarehouseUnattendedAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.DerivableProductSurveyFixture;
import dev.stevecreate.agent.forge1201.acceptance.ImmersiveEngineeringV1020PhysicalAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.MetalPressRecoveryReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.RecoveryReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.adapter.ForgeRegistryIndustrialAdapter;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.DeceasedCraftExecutionPilotFixture;
import dev.stevecreate.agent.forge1201.command.CreateRuntimePlanningCommand;
import dev.stevecreate.agent.forge1201.command.CreateRuntimeBindingCommand;
import dev.stevecreate.agent.forge1201.command.CreateDeploymentDryRunCommand;
import dev.stevecreate.agent.forge1201.command.PilotDeploymentCommand;
import dev.stevecreate.agent.forge1201.command.PilotRegionCommand;
import dev.stevecreate.agent.forge1201.command.SitePreparationCommand;
import dev.stevecreate.agent.forge1201.command.SetupCommand;
import dev.stevecreate.agent.forge1201.command.BackupCommand;
import dev.stevecreate.agent.forge1201.command.PublicRuntimeAcceptanceFixture;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowItems;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.command.PlayerRelocationService;
import dev.stevecreate.agent.forge1201.command.PlayerClearingService;
import dev.stevecreate.agent.forge1201.command.PlayerConstructionService;
import dev.stevecreate.agent.forge1201.command.MetalPressProductionService;
import dev.stevecreate.agent.forge1201.command.MetalPressResourceRecoveryReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.command.AlloySmelterProductionService;
import dev.stevecreate.agent.forge1201.command.AlloySmelterRecoveryReloadAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.AlloySmelterWarehouseRestartAcceptanceFixture;
import dev.stevecreate.agent.forge1201.acceptance.IndustrialResourceRestartAcceptanceFixture;
import dev.stevecreate.agent.forge1201.command.PlayerCompositeOrderService;
import dev.stevecreate.agent.forge1201.player.net.MetalPressOrderNetwork;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderNetwork;
import dev.stevecreate.agent.forge1201.runtime.PublicAlphaConfig;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;
import dev.stevecreate.agent.forge1201.profile.DeceasedCraftPackProfileProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/** Forge entry point. World access is read-only in Phase 1 and always server authoritative. */
@Mod(SteveIndustrialAgentMod.MOD_ID)
@Mod.EventBusSubscriber(modid = SteveIndustrialAgentMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SteveIndustrialAgentMod {
    public static final String MOD_ID = "steve_create_agent";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MEKANISM_ABSENT_SMOKE_PROPERTY =
            "steve_industrial.test.mekanismAbsent";
    private static final String MEKANISM_ABSENT_DETAIL =
            "Optional mod is not loaded: mekanism";
    private static final String RUNTIME_PROFILE_PROPERTY =
            "steve_industrial.test.runtimeProfile";
    private static final String CREATE_SCAN_ACCEPTANCE_PROPERTY =
            "steve_industrial.test.createScanAcceptance";
    private static final String CREATE_KINETICS_ACCEPTANCE_PROPERTY =
            "steve_industrial.test.createKineticsAcceptance";
    private static final String CREATE_RUNTIME_RECIPE_CATALOG_ACCEPTANCE_PROPERTY =
            "steve_industrial.test.createRuntimeRecipeCatalogAcceptance";
    private static final String RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY =
            "steve_industrial.test.recoveryReloadAcceptancePhase";
    private static final String C03_RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY =
            "steve_industrial.test.c03RecoveryReloadAcceptancePhase";
    private static final String C04_RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY =
            "steve_industrial.test.c04RecoveryReloadAcceptancePhase";
    private static final Map<String, Map<String, ExpectedAdapterOutcome>> RUNTIME_PROFILES = Map.of(
            "neither-server", Map.of(
                    "create", ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME,
                    "mekanism", ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME),
            "create-only-server", Map.of(
                    "create", ExpectedAdapterOutcome.SUCCESS,
                    "mekanism", ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME));

    public SteveIndustrialAgentMod(FMLJavaModLoadingContext loadingContext) {
        ConstructionBotEntities.register(loadingContext.getModEventBus());
        PlayerWorkflowItems.register(loadingContext.getModEventBus());
        PlayerWorkflowNetwork.register();
        MetalPressOrderNetwork.register();
        CompositeOrderNetwork.register();
        loadingContext.registerConfig(ModConfig.Type.COMMON, PublicAlphaConfig.SPEC,
                "steve-industrial-agent-common.toml");
    }

    /**
     * Development-only launch probe enabled by the isolated Mekanism-absent run profile.
     * It exercises the real Forge adapter on the authoritative server thread and then
     * shuts the dedicated server down cleanly. Normal launches never enter this path.
     */
    @SubscribeEvent
    public static void verifyMekanismAbsentProfile(ServerStartedEvent event) {
        if (!Boolean.getBoolean(MEKANISM_ABSENT_SMOKE_PROPERTY)) {
            return;
        }

        ServerLevel level = event.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        AdapterResult<WorldSnapshot> result = new ForgeRegistryIndustrialAdapter(level, "mekanism")
                .capture(new ScanRequest(new BlockPos3i(spawn.getX(), spawn.getY(), spawn.getZ()), 1));

        if (!(result instanceof AdapterResult.Failure<?> failure)
                || failure.code() != AdapterFailureCode.UNSUPPORTED_RUNTIME
                || !MEKANISM_ABSENT_DETAIL.equals(failure.detail())) {
            event.getServer().halt(false);
            throw new IllegalStateException("Mekanism-absent smoke profile expected "
                    + AdapterFailureCode.UNSUPPORTED_RUNTIME + " with detail '"
                    + MEKANISM_ABSENT_DETAIL + "' but received " + result);
        }

        LOGGER.info("MEKANISM_ABSENT_SMOKE PASS code={} detail=\"{}\"",
                failure.code(), failure.detail());
        event.getServer().halt(false);
    }

    /**
     * Executes the bounded T-02 server profile probe on the authoritative server
     * thread. Each optional-mod result is logged with an explicit typed outcome;
     * the stable PASS summary is emitted only after every result matches the
     * selected profile. The profile launcher owns the isolated world directory.
     */
    @SubscribeEvent
    public static void verifyRuntimeProfile(ServerStartedEvent event) {
        String profile = System.getProperty(RUNTIME_PROFILE_PROPERTY);
        if (profile == null || profile.isBlank()) {
            return;
        }

        Map<String, ExpectedAdapterOutcome> expectations = RUNTIME_PROFILES.get(profile);
        if (expectations == null) {
            failRuntimeProfile(event, "Unknown runtime profile: " + profile);
            return;
        }

        ServerLevel level = event.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        ScanRequest request = new ScanRequest(
                new BlockPos3i(spawn.getX(), spawn.getY(), spawn.getZ()), 1);

        for (String modId : List.of("create", "mekanism")) {
            ExpectedAdapterOutcome expected = expectations.get(modId);
            boolean loaded = ModList.get().isLoaded(modId);
            if (loaded != (expected == ExpectedAdapterOutcome.SUCCESS)) {
                failRuntimeProfile(event, "Profile " + profile + " expected " + modId
                        + " outcome " + expected + " but ModList loaded=" + loaded);
                return;
            }

            AdapterResult<WorldSnapshot> result = new ForgeRegistryIndustrialAdapter(level, modId)
                    .capture(request);
            if (result instanceof AdapterResult.Success<WorldSnapshot> success) {
                if (expected != ExpectedAdapterOutcome.SUCCESS) {
                    failRuntimeProfile(event, "Profile " + profile + " expected " + modId
                            + " outcome " + expected + " but received SUCCESS");
                    return;
                }
                LOGGER.info("RUNTIME_PROFILE_RESULT profile={} mod={} outcome=SUCCESS components={}",
                        profile, modId, success.value().components().size());
            } else if (result instanceof AdapterResult.Failure<WorldSnapshot> failure) {
                if (expected != ExpectedAdapterOutcome.UNSUPPORTED_RUNTIME
                        || failure.code() != AdapterFailureCode.UNSUPPORTED_RUNTIME
                        || !("Optional mod is not loaded: " + modId).equals(failure.detail())) {
                    failRuntimeProfile(event, "Profile " + profile + " expected " + modId
                            + " outcome " + expected + " but received " + result);
                    return;
                }
                LOGGER.info("RUNTIME_PROFILE_RESULT profile={} mod={} outcome=FAILURE code={} detail=\"{}\"",
                        profile, modId, failure.code(), failure.detail());
            }
        }

        if ("neither-server".equals(profile)) {
            RecordingCommandSource commandSource = new RecordingCommandSource();
            CommandSourceStack stack = event.getServer().createCommandSourceStack()
                    .withSource(commandSource)
                    .withLevel(level)
                    .withPermission(4);
            int commandReturn = event.getServer().getCommands().performPrefixedCommand(
                    stack, "/industrialagent plan create minecraft:gravel 1");
            if (commandReturn != 0 || commandSource.messages().stream().noneMatch(message ->
                    message.contains("code=REQUIRED_MOD_UNAVAILABLE")
                            && message.contains("target=minecraft:gravel"))) {
                failRuntimeProfile(event, "Create-absent planning command did not return its typed failure: "
                        + commandSource.messages());
                return;
            }
            LOGGER.info(
                    "CREATE_RUNTIME_PLAN_ABSENT PASS profile={} return=0 code=REQUIRED_MOD_UNAVAILABLE worldMutation=false sessionCreated=false",
                    profile);
        }

        LOGGER.info("RUNTIME_PROFILE_SMOKE PASS profile={} create={} mekanism={}",
                profile, expectations.get("create").marker(), expectations.get("mekanism").marker());
        event.getServer().halt(false);
    }

    /** Runs only in the isolated C-01 acceptance server configured by the repository test script. */
    @SubscribeEvent
    public static void verifyCreateScanAcceptance(ServerStartedEvent event) {
        if (Boolean.getBoolean(CREATE_SCAN_ACCEPTANCE_PROPERTY)) {
            CreateScanAcceptanceFixture.run(event.getServer(), LOGGER);
        }
    }

    /** Starts only the isolated C-02 fixture; the tick hook lets Create settle without blocking. */
    @SubscribeEvent
    public static void verifyCreateKineticsAcceptance(ServerStartedEvent event) {
        if (Boolean.getBoolean(CREATE_KINETICS_ACCEPTANCE_PROPERTY)) {
            CreateKineticsAcceptanceFixture.start(event.getServer(), LOGGER);
        }
    }

    /** Runs only the isolated read-only R-01 RecipeManager/catalog-boundary fixture. */
    @SubscribeEvent
    public static void verifyCreateRuntimeRecipeCatalogAcceptance(ServerStartedEvent event) {
        if (Boolean.getBoolean(CREATE_RUNTIME_RECIPE_CATALOG_ACCEPTANCE_PROPERTY)) {
            CreateRuntimeRecipeCatalogAcceptanceFixture.run(event.getServer(), LOGGER);
        }
    }

    /** Runs only the optional read-only IE 10.2.0 adapter acceptance profile. */
    @SubscribeEvent
    public static void verifyImmersiveEngineeringAdapterAcceptance(ServerStartedEvent event) {
        if (Boolean.getBoolean(ImmersiveEngineeringV1020AdapterAcceptanceFixture.ENABLE_PROPERTY)) {
            ImmersiveEngineeringV1020AdapterAcceptanceFixture.run(event.getServer(), LOGGER);
        }
    }

    /** Starts only the disposable IE 10.2.0 real Metal Press lifecycle fixture. */
    @SubscribeEvent
    public static void verifyImmersiveEngineeringPhysicalAcceptance(ServerStartedEvent event) {
        if (Boolean.getBoolean(
                ImmersiveEngineeringV1020PhysicalAcceptanceFixture.ENABLE_PROPERTY)) {
            ImmersiveEngineeringV1020PhysicalAcceptanceFixture.start(event.getServer(), LOGGER);
        }
    }

    /** Starts only the disposable IPO-02 Composite player-order gate. */
    @SubscribeEvent
    public static void verifyCompositePlayerOrderAcceptance(ServerStartedEvent event) {
        if (Boolean.getBoolean(CompositePlayerOrderAcceptanceRunner.ENABLE_PROPERTY)) {
            CompositePlayerOrderAcceptanceRunner.start(event.getServer(), LOGGER);
        }
    }

    /** Starts only the clean production JAR inside the path- and marker-validated R-09 profile. */
    @SubscribeEvent
    public static void verifyDeceasedCraftPackProfile(ServerStartedEvent event) {
        if (Boolean.getBoolean(DeceasedCraftPackProfileProbe.ENABLE_PROPERTY)) {
            DeceasedCraftPackProfileProbe.run(event.getServer(), LOGGER);
        }
    }

    /** Runs one half of the isolated two-process G-11 save/reload acceptance. */
    @SubscribeEvent
    public static void verifyRecoveryReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            RecoveryReloadAcceptanceFixture.run(event.getServer(), phase, LOGGER);
        }
    }

    /** Runs one half of the isolated two-JVM IE Metal Press interruption matrix. */
    @SubscribeEvent
    public static void verifyMetalPressRecoveryReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(MetalPressRecoveryReloadAcceptanceFixture.PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            MetalPressRecoveryReloadAcceptanceFixture.run(event.getServer(), phase, LOGGER);
        }
    }

    /** Runs one half of a real resource-bearing IE Alloy Smelter restart window. */
    @SubscribeEvent
    public static void verifyAlloySmelterRecoveryReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(
                AlloySmelterRecoveryReloadAcceptanceFixture.PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            AlloySmelterRecoveryReloadAcceptanceFixture.start(
                    event.getServer(), phase, LOGGER);
        }
    }

    /** Runs one half of a real resource-bound production Metal Press restart. */
    @SubscribeEvent
    public static void verifyMetalPressResourceRecoveryReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(
                MetalPressResourceRecoveryReloadAcceptanceFixture.PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            MetalPressResourceRecoveryReloadAcceptanceFixture.start(
                    event.getServer(), phase, LOGGER);
        }
    }

    /** Starts one half of the isolated two-process real C-03 save/reload acceptance. */
    @SubscribeEvent
    public static void verifyC03RecoveryReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(C03_RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            C03RecoveryReloadAcceptanceFixture.start(event.getServer(), phase, LOGGER);
        }
    }

    /** Starts one half of the isolated two-process real C-04 save/reload acceptance. */
    @SubscribeEvent
    public static void verifyC04RecoveryReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(C04_RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            C04RecoveryReloadAcceptanceFixture.start(event.getServer(), phase, LOGGER);
        }
    }

    /** Runs one half of the repository-external production setup/backup acceptance. */
    @SubscribeEvent
    public static void verifyPublicRuntimeAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(PublicRuntimeAcceptanceFixture.PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            PublicRuntimeAcceptanceFixture.run(event.getServer(), phase, LOGGER);
        }
    }

    /** Two-process Composite reload gate; write and read run in separate JVMs. */
    @SubscribeEvent
    public static void verifyCompositeReloadAcceptance(ServerStartedEvent event) {
        String phase = System.getProperty(CompositeReloadAcceptanceFixture.PHASE_PROPERTY);
        if (phase != null && !phase.isBlank()) {
            CompositeReloadAcceptanceFixture.start(event.getServer(), phase, LOGGER);
        }
        String resumePhase = System.getProperty(CompositeResumeAcceptanceFixture.PHASE_PROPERTY);
        if (resumePhase != null && !resumePhase.isBlank()) {
            CompositeResumeAcceptanceFixture.start(event.getServer(), resumePhase, LOGGER);
        }
        if (Boolean.getBoolean(WarehouseUnattendedAcceptanceFixture.ENABLE_PROPERTY)) {
            WarehouseUnattendedAcceptanceFixture.start(event.getServer(), LOGGER);
        }
        // Standing production instructions outlive the process that received them.
        // Without this a restarted server reads its orders, finds no runtime and skips
        // every one of them forever, which looks from the outside like a factory that
        // simply stopped.
        WarehouseOrderService.restoreRegisteredRuntimes(event.getServer());
        String alloyWarehousePhase = System.getProperty(
                AlloySmelterWarehouseRestartAcceptanceFixture.PHASE_PROPERTY);
        if (alloyWarehousePhase != null && !alloyWarehousePhase.isBlank()) {
            AlloySmelterWarehouseRestartAcceptanceFixture.start(
                    event.getServer(), alloyWarehousePhase, LOGGER);
        }
        String restartPhase = System.getProperty(WarehouseRestartAcceptanceFixture.PHASE_PROPERTY);
        if (restartPhase != null && !restartPhase.isBlank()) {
            WarehouseRestartAcceptanceFixture.start(event.getServer(), restartPhase, LOGGER);
        }
        String resourceRestartPhase = System.getProperty(
                IndustrialResourceRestartAcceptanceFixture.PHASE_PROPERTY);
        if (resourceRestartPhase != null && !resourceRestartPhase.isBlank()) {
            IndustrialResourceRestartAcceptanceFixture.start(
                    event.getServer(), resourceRestartPhase, LOGGER);
        }
    }

    /** Read-only survey of how much of the live registry can be derived and built. */
    @SubscribeEvent
    public static void surveyDerivableProducts(ServerStartedEvent event) {
        if (Boolean.getBoolean(DerivableProductSurveyFixture.ENABLE_PROPERTY)) {
            DerivableProductSurveyFixture.run(event.getServer(), LOGGER);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void recoverIndustrialPlayerOrders(ServerStartedEvent event) {
        IndustrialPlayerOrderService.recoverServer(event.getServer());
    }

    @SubscribeEvent
    public static void tickCreateKineticsAcceptance(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            PilotRegionCommand.tick(event.getServer());
            PilotDeploymentCommand.tick(event.getServer());
            SitePreparationCommand.tick(event.getServer());
            PlayerRelocationService.tick(event.getServer());
            PlayerClearingService.tick(event.getServer());
            PlayerConstructionService.tick(event.getServer());
            MetalPressProductionService.tick(event.getServer());
            AlloySmelterProductionService.tick(event.getServer());
            PlayerCompositeOrderService.tick(event.getServer());
            WarehouseOrderService.tick(event.getServer());
            if (Boolean.getBoolean(CREATE_KINETICS_ACCEPTANCE_PROPERTY)) {
                CreateKineticsAcceptanceFixture.tick(event.getServer());
            }
            if (Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)) {
                DeceasedCraftExecutionPilotFixture.tick(event.getServer());
            }
            if (Boolean.getBoolean(
                    ImmersiveEngineeringV1020PhysicalAcceptanceFixture.ENABLE_PROPERTY)) {
                ImmersiveEngineeringV1020PhysicalAcceptanceFixture.tick(event.getServer());
            }
            if (Boolean.getBoolean(CompositePlayerOrderAcceptanceRunner.ENABLE_PROPERTY)) {
                CompositePlayerOrderAcceptanceRunner.tick(event.getServer());
            }
            CompositeReloadAcceptanceFixture.tick(event.getServer());
            CompositeResumeAcceptanceFixture.tick(event.getServer());
            WarehouseUnattendedAcceptanceFixture.tick(event.getServer());
            WarehouseRestartAcceptanceFixture.tick(event.getServer());
            AlloySmelterRecoveryReloadAcceptanceFixture.tick(event.getServer());
            MetalPressResourceRecoveryReloadAcceptanceFixture.tick(event.getServer());
            AlloySmelterWarehouseRestartAcceptanceFixture.tick(event.getServer());
            IndustrialResourceRestartAcceptanceFixture.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void tickC03RecoveryReloadAcceptance(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END
                && System.getProperty(C03_RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY) != null) {
            C03RecoveryReloadAcceptanceFixture.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void tickC04RecoveryReloadAcceptance(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END
                && System.getProperty(C04_RECOVERY_RELOAD_ACCEPTANCE_PHASE_PROPERTY) != null) {
            C04RecoveryReloadAcceptanceFixture.tick(event.getServer());
        }
    }

    /** Invalidates only server/world-scoped loader-neutral recipe snapshots across datapack reload. */
    @SubscribeEvent
    public static void registerRuntimeCatalogReloadListener(AddReloadListenerEvent event) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        event.addListener(new SimplePreparableReloadListener<Void>() {
            private volatile MinecraftServer server;

            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    ForgeCreateRuntimeRecipeCatalogs.beginReload(server);
                }
                return null;
            }

            @Override
            protected void apply(
                    Void prepared,
                    ResourceManager resourceManager,
                    ProfilerFiller profiler) {
                if (server != null) {
                    ForgeCreateRuntimeRecipeCatalogs.completeReload(server);
                }
            }
        });
    }

    @SubscribeEvent
    public static void clearRuntimeCatalogs(ServerStoppedEvent event) {
        ForgeCreateRuntimeRecipeCatalogs.clear(event.getServer());
        PilotRegionCommand.clearServerState();
        SitePreparationCommand.clearServerState();
        PlayerWorkflowNetwork.clearServerState();
        PlayerRelocationService.clearServerState();
        PlayerClearingService.clearServerState();
        PlayerConstructionService.clearServerState();
        MetalPressProductionService.clearServerState();
        AlloySmelterProductionService.clearServerState();
        PlayerCompositeOrderService.clearServerState();
        WarehouseOrderService.clearServerState();
    }

    @SubscribeEvent
    public static void clearPlayerWorkflowSession(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerWorkflowNetwork.clearPlayer(event.getEntity().getUUID());
        PlayerRelocationService.clearPlayer(event.getEntity().getUUID());
        PlayerClearingService.clearPlayer(event.getEntity().getUUID());
        PlayerConstructionService.clearPlayer(event.getEntity().getUUID());
    }

    private static void failRuntimeProfile(ServerStartedEvent event, String detail) {
        LOGGER.error("RUNTIME_PROFILE_SMOKE FAIL detail=\"{}\"", detail);
        event.getServer().halt(false);
        throw new IllegalStateException(detail);
    }

    private enum ExpectedAdapterOutcome {
        SUCCESS,
        UNSUPPORTED_RUNTIME;

        private String marker() {
            return this == SUCCESS ? "SUCCESS" : "FAILURE:UNSUPPORTED_RUNTIME";
        }
    }

    private static final class RecordingCommandSource implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private List<String> messages() {
            return List.copyOf(messages);
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("industrialagent")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("scan")
                        .then(Commands.argument("mod", StringArgumentType.word())
                                .executes(context -> scan(context.getSource().getLevel(),
                                        context.getSource().getPosition().x,
                                        context.getSource().getPosition().y,
                                        context.getSource().getPosition().z,
                                        StringArgumentType.getString(context, "mod"),
                                        8,
                                        context.getSource()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, ScanRequest.MAX_RADIUS))
                                        .executes(context -> scan(context.getSource().getLevel(),
                                                context.getSource().getPosition().x,
                                                context.getSource().getPosition().y,
                                                context.getSource().getPosition().z,
                                                StringArgumentType.getString(context, "mod"),
                                                IntegerArgumentType.getInteger(context, "radius"),
                                                context.getSource())))))
                .then(CreateRuntimePlanningCommand.command())
                .then(CreateRuntimeBindingCommand.command())
                .then(CreateDeploymentDryRunCommand.command())
                .then(SetupCommand.command())
                .then(BackupCommand.command())
                .then(PilotRegionCommand.command())
                .then(SitePreparationCommand.command());
        dev.stevecreate.agent.forge1201.command.ReleaseInfoCommand.attach(root);
        dev.stevecreate.agent.forge1201.command.BuildModeCommand.attach(root);
        dev.stevecreate.agent.forge1201.command.MetalPressProductionCommand.attach(root);
        dev.stevecreate.agent.forge1201.command.CompositeProductionCommand.attach(root);
        // The fixture only prepares a bounded disposable arena and bill. It is kept out
        // of production command registration so a normal server never exposes a material
        // seeding helper; the subsequent /steveagent composite create still remains the
        // real player order path.
        if (!net.minecraftforge.fml.loading.FMLLoader.isProduction()) {
            root.then(dev.stevecreate.agent.forge1201.command.CompositeClientAcceptanceCommand
                    .command());
            root.then(dev.stevecreate.agent.forge1201.command.PlayerCreateClientAcceptanceCommand
                    .command());
        }
        dev.stevecreate.agent.forge1201.command.WarehouseProductionCommand.attach(root);
        dev.stevecreate.agent.forge1201.command.WarehouseDiscoveryCommand.attach(root);
        dev.stevecreate.agent.forge1201.command.FactoryDiagnosticCommand.attach(root);
        root.then(Commands.literal("order").then(Commands.literal("status")
                .executes(context -> {
                    if (!(context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
                        context.getSource().sendFailure(Component.literal("player-only order status"));
                        return 0;
                    }
                    IndustrialPlayerOrderService.sendStatus(player);
                    return 1;
                })));
        event.getDispatcher().register(root);
        event.getDispatcher().register(
                dev.stevecreate.agent.forge1201.command.MetalPressProductionCommand.playerCommand());
        event.getDispatcher().register(
                dev.stevecreate.agent.forge1201.command.CompositeProductionCommand.playerCommand());
    }

    private static int scan(
            ServerLevel level,
            double x,
            double y,
            double z,
            String target,
            int radius,
            net.minecraft.commands.CommandSourceStack source) {
        BlockPos center = BlockPos.containing(x, y, z);
        List<String> modIds = switch (target.toLowerCase(java.util.Locale.ROOT)) {
            case "create" -> List.of("create");
            case "mekanism" -> List.of("mekanism");
            case "all" -> List.of("create", "mekanism");
            default -> List.of();
        };
        if (modIds.isEmpty()) {
            source.sendFailure(Component.literal("Expected mod: create, mekanism, or all"));
            return 0;
        }

        int observed = 0;
        for (String modId : modIds) {
            IndustrialModAdapter adapter = new ForgeRegistryIndustrialAdapter(level, modId);
            AdapterResult<WorldSnapshot> result = adapter.capture(new ScanRequest(
                    new BlockPos3i(center.getX(), center.getY(), center.getZ()), radius));
            if (result instanceof AdapterResult.Success<?> success) {
                WorldSnapshot snapshot = (WorldSnapshot) success.value();
                int count = snapshot.components().size();
                observed += count;
                source.sendSuccess(() -> Component.literal(
                        modId + ": " + count + " components; runtime=" + snapshot.runtime().industrialModVersions()), false);
            } else if (result instanceof AdapterResult.Failure<?> failure) {
                source.sendFailure(Component.literal(modId + ": " + failure.code() + " - " + failure.detail()));
            }
        }
        return observed;
    }
}
