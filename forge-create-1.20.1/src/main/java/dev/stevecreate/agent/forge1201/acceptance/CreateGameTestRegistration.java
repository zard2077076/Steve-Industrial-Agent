package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.command.PlayerWorkflowGameTests;
import dev.stevecreate.agent.forge1201.command.SitePreparationGameTests;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Registers exactly one isolated processing GameTest suite for the selected repository run. */
@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CreateGameTestRegistration {
    private static final String C03_PROPERTY = "steve_industrial.test.createProcessingGameTest";
    private static final String C04_PROPERTY = "steve_industrial.test.createBeltPressGameTest";
    private static final String C04_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c04SurvivalPowerGameTest";
    private static final String C05_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c05SurvivalPowerGameTest";
    private static final String C06_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c06SurvivalPowerGameTest";
    private static final String C07_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c07SurvivalPowerGameTest";
    private static final String C08_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c08SurvivalPowerGameTest";
    private static final String C09_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c09SurvivalPowerGameTest";
    private static final String C10_SURVIVAL_POWER_PROPERTY =
            "steve_industrial.test.c10SurvivalPowerGameTest";
    private static final String GOAL_PROPERTY = "steve_industrial.test.goalDrivenExecutionGameTest";
    /** Crushing reshapes its surroundings, so it never shares a world. */
    private static final String CRUSHING_ONLY_PROPERTY =
            "steve_industrial.test.crushingCapabilityOnly";
    private static final String C05_ONLY_PROPERTY =
            "steve_industrial.test.c05CrushingGameTestOnly";
    private static final String C08_HEATED_ONLY_PROPERTY =
            "steve_industrial.test.c08HeatedGameTestOnly";
    private static final String PHASE_IV_CAPABILITY_ONLY_PROPERTY =
            "steve_industrial.test.phaseIvCapabilityGameTestOnly";
    private static final String PHASE_IV_RECOVERY_ONLY_PROPERTY =
            "steve_industrial.test.phaseIvRecoveryGameTestOnly";
    private static final String PHASE_IV_FAULT_ONLY_PROPERTY =
            "steve_industrial.test.phaseIvFaultGameTestOnly";
    private static final String PHASE_IV_COMPOSITE_ONLY_PROPERTY =
            "steve_industrial.test.phaseIvCompositeGameTestOnly";
    private static final String PHASE_IV_COMPOSITE_FAULT_ONLY_PROPERTY =
            "steve_industrial.test.phaseIvCompositeFaultGameTestOnly";
    private static final String C10_OWNED_WORKPIECE_ONLY_PROPERTY =
            "steve_industrial.test.c10OwnedWorkpieceGameTestOnly";
    private static final String BOT_PROPERTY = "steve_industrial.test.botFleetGameTest";
    private static final String SITE_PREP_PROPERTY =
            "steve_industrial.test.sitePreparationGameTest";
    private static final String FACTORY_DIAGNOSTIC_PROPERTY =
            "steve_industrial.test.factoryDiagnosticGameTest";
    private static final String PHASE_IV_INTEGRATED_VISIBLE_PROPERTY =
            "steve_industrial.test.phaseIvIntegratedVisibleGameTest";
    private static final String COMPOSITE_PLAYER_ORDER_PROPERTY =
            CompositePlayerOrderAcceptanceRunner.ENABLE_PROPERTY;

    private CreateGameTestRegistration() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (Boolean.getBoolean(PHASE_IV_INTEGRATED_VISIBLE_PROPERTY)) {
            registerIntegratedVisible(event);
            return;
        }
        // The Composite player-order gate is not a GameTest suite: it drives one order
        // on a dedicated server. It enables the executor and fleet switches together
        // because the wrapper needs both, which is not the same thing as selecting two
        // GameTest suites that would fight over one world.
        if (Boolean.getBoolean(COMPOSITE_PLAYER_ORDER_PROPERTY)
                || Boolean.getBoolean(DerivableProductSurveyFixture.ENABLE_PROPERTY)
                || System.getProperty(CompositeReloadAcceptanceFixture.PHASE_PROPERTY) != null
                || System.getProperty(CompositeResumeAcceptanceFixture.PHASE_PROPERTY) != null
                || Boolean.getBoolean(WarehouseUnattendedAcceptanceFixture.ENABLE_PROPERTY)
                || System.getProperty(WarehouseRestartAcceptanceFixture.PHASE_PROPERTY) != null
                || System.getProperty(IndustrialResourceRestartAcceptanceFixture.PHASE_PROPERTY)
                        != null) {
            return;
        }
        boolean c03 = Boolean.getBoolean(C03_PROPERTY);
        boolean c04 = Boolean.getBoolean(C04_PROPERTY);
        boolean c04SurvivalPower = Boolean.getBoolean(C04_SURVIVAL_POWER_PROPERTY);
        boolean c05SurvivalPower = Boolean.getBoolean(C05_SURVIVAL_POWER_PROPERTY);
        boolean c06SurvivalPower = Boolean.getBoolean(C06_SURVIVAL_POWER_PROPERTY);
        boolean c07SurvivalPower = Boolean.getBoolean(C07_SURVIVAL_POWER_PROPERTY);
        boolean c08SurvivalPower = Boolean.getBoolean(C08_SURVIVAL_POWER_PROPERTY);
        boolean c09SurvivalPower = Boolean.getBoolean(C09_SURVIVAL_POWER_PROPERTY);
        boolean c10SurvivalPower = Boolean.getBoolean(C10_SURVIVAL_POWER_PROPERTY);
        boolean goal = Boolean.getBoolean(GOAL_PROPERTY);
        boolean bot = Boolean.getBoolean(BOT_PROPERTY);
        boolean sitePrep = Boolean.getBoolean(SITE_PREP_PROPERTY);
        boolean factoryDiagnostic = Boolean.getBoolean(FACTORY_DIAGNOSTIC_PROPERTY);
        if ((c03 ? 1 : 0) + (c04 ? 1 : 0) + (c04SurvivalPower ? 1 : 0)
                + (c05SurvivalPower ? 1 : 0) + (c06SurvivalPower ? 1 : 0)
                + (c07SurvivalPower ? 1 : 0) + (goal ? 1 : 0)
                + (c08SurvivalPower ? 1 : 0)
                + (c09SurvivalPower ? 1 : 0)
                + (c10SurvivalPower ? 1 : 0)
                + (bot ? 1 : 0) + (sitePrep ? 1 : 0) + (factoryDiagnostic ? 1 : 0) > 1) {
            throw new IllegalStateException(
                    "C-03, C-04 and C-04/C-06/C-07/C-08/C-09/C-10 survival-power, goal-driven, Bot, site-prep and diagnostic GameTests require separate worlds");
        }
        if (c03) {
            event.register(CreateProcessingGameTests.class);
        } else if (c04) {
            event.register(CreateBeltPressGameTests.class);
        } else if (c04SurvivalPower) {
            event.register(CreateC04SurvivalPowerGameTests.class);
        } else if (c05SurvivalPower) {
            event.register(CreateC05SurvivalPowerGameTests.class);
        } else if (c06SurvivalPower) {
            event.register(CreateC06SurvivalPowerGameTests.class);
        } else if (c07SurvivalPower) {
            event.register(CreateC07SurvivalPowerGameTests.class);
        } else if (c08SurvivalPower) {
            event.register(CreateC08SurvivalPowerGameTests.class);
        } else if (c09SurvivalPower) {
            event.register(CreateC09SurvivalPowerGameTests.class);
        } else if (c10SurvivalPower) {
            event.register(CreateC10SurvivalPowerGameTests.class);
        } else if (goal) {
            if (Boolean.getBoolean(CRUSHING_ONLY_PROPERTY)) {
                // Its own class and its own world: crushing wheels grind the arena floor
                // and the debris ends up in a neighbour's item lane.
                event.register(CreateCrushingCapabilityGameTests.class);
            } else if (Boolean.getBoolean(C05_ONLY_PROPERTY)) {
                try {
                    event.register(CreateThreeModeEquivalenceGameTests.class.getDeclaredMethod(
                            "c05CrushingIsEquivalentAcrossAllModes",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                    event.register(CreateGoalDrivenExecutionGameTests.class.getDeclaredMethod(
                            "c05MidBuildReloadReconcilesWithoutDuplicatePlacement",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                    event.register(CreateGoalDrivenExecutionGameTests.class.getDeclaredMethod(
                            "c05PlanningAndCancellationFailuresAreTyped",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                    event.register(CreateGoalDrivenExecutionGameTests.class.getDeclaredMethod(
                            "c05RuntimeFaultsAreTypedAndContained",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(
                            "C-05 GameTest method registration failed", exception);
                }
            } else if (Boolean.getBoolean(C08_HEATED_ONLY_PROPERTY)) {
                try {
                    event.register(CreateThreeModeEquivalenceGameTests.class.getDeclaredMethod(
                            "c08HeatedBrassIsEquivalentAcrossAllModes",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                    event.register(CreateGoalDrivenExecutionGameTests.class.getDeclaredMethod(
                            "c08HeatedExactRecoveryReleasesAndReacquiresFuel",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                    event.register(CreateGoalDrivenExecutionGameTests.class.getDeclaredMethod(
                            "c08HeatedFuelReservationIsExclusiveAndCancelReleases",
                            net.minecraft.gametest.framework.GameTestHelper.class));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(
                            "C-08 HEATED GameTest method registration failed", exception);
                }
            } else if (Boolean.getBoolean(C10_OWNED_WORKPIECE_ONLY_PROPERTY)) {
                event.register(
                        dev.stevecreate.agent.forge1201.adapter.create.internal.v606
                                .CreateOwnedWorkpieceApplicationGameTests.class);
            } else if (Boolean.getBoolean(PHASE_IV_CAPABILITY_ONLY_PROPERTY)
                    || Boolean.getBoolean(PHASE_IV_RECOVERY_ONLY_PROPERTY)
                    || Boolean.getBoolean(PHASE_IV_FAULT_ONLY_PROPERTY)
                    || Boolean.getBoolean(PHASE_IV_COMPOSITE_ONLY_PROPERTY)
                    || Boolean.getBoolean(PHASE_IV_COMPOSITE_FAULT_ONLY_PROPERTY)) {
                try {
                    if (Boolean.getBoolean(PHASE_IV_COMPOSITE_ONLY_PROPERTY)
                            || Boolean.getBoolean(PHASE_IV_COMPOSITE_FAULT_ONLY_PROPERTY)) {
                        String[] compositeMethods = Boolean.getBoolean(
                                PHASE_IV_COMPOSITE_FAULT_ONLY_PROPERTY)
                                ? new String[] {
                                        "composite03IsolatesPhysicalBranchFailure",
                                        "composite03RefusesPhysicalRouteContamination",
                                        "composite03RefusesPhysicalMergeBackpressure"
                                }
                                : new String[] {
                                        "composite01DirectRunsThreeRealStagesAndTwoPhysicalRoutes",
                                        "composite01BotsRunsThreeRealStagesAndTwoPhysicalRoutes",
                                        "composite01HybridRunsThreeRealStagesAndTwoPhysicalRoutes",
                                        "composite02DirectCancelIsolation",
                                        "composite02BotsCancelIsolation",
                                        "composite02HybridCancelIsolation",
                                        "composite03DirectRunsPhysicalBranchAndMerge",
                                        "composite03BotsRunPhysicalBranchAndMerge",
                                        "composite03HybridRunsPhysicalBranchAndMerge"
                                };
                        for (String method : compositeMethods) {
                            event.register(CreateCompositeGameTests.class.getDeclaredMethod(
                                    method,
                                    net.minecraft.gametest.framework.GameTestHelper.class));
                        }
                        return;
                    }
                    boolean recovery =
                            Boolean.getBoolean(PHASE_IV_RECOVERY_ONLY_PROPERTY);
                    boolean fault =
                            Boolean.getBoolean(PHASE_IV_FAULT_ONLY_PROPERTY);
                    String[] methods = fault
                            ? new String[] {
                                    "c07FaultMatrixFailsClosed",
                                    "c06FaultMatrixFailsClosed",
                                    "c09FaultMatrixFailsClosed",
                                    "c08FaultMatrixFailsClosed",
                                    "c10FaultMatrixFailsClosed"
                            }
                            : recovery
                            ? new String[] {
                                    "c07ExactRecoveryResumesOnlyUnfinishedBuild",
                                    "c06ExactRecoveryResumesOnlyUnfinishedBuild",
                                    "c09ExactRecoveryResumesOnlyUnfinishedBuild",
                                    "c08ExactRecoveryResumesOnlyUnfinishedBuild",
                                    "c10ExactRecoveryResumesOnlyUnfinishedBuild",
                                    "c07CancellationRestoresAllModes",
                                    "c06CancellationRestoresAllModes",
                                    "c09CancellationRestoresAllModes",
                                    "c08CancellationRestoresAllModes",
                                    "c10CancellationRestoresAllModes"
                            }
                            : new String[] {
                                    "c07CuttingIsEquivalentAcrossAllModes",
                                    "c06SafeWashingIsEquivalentAcrossAllModes",
                                    "c09CompactingIsEquivalentAcrossAllModes",
                                    "c08MixingIsEquivalentAcrossAllModes",
                                    "c10DeployingIsEquivalentAcrossAllModes"
                            };
                    for (String method : methods) {
                        Class<?> suite = method.contains("Cancellation")
                                || method.contains("FaultMatrix")
                                ? CreateThreeModeEquivalenceGameTests.class
                                : recovery
                                        ? CreateGoalDrivenExecutionGameTests.class
                                        : CreateThreeModeEquivalenceGameTests.class;
                        event.register(suite
                                .getDeclaredMethod(
                                        method,
                                        net.minecraft.gametest.framework.GameTestHelper.class));
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(
                            "Phase IV capability GameTest method registration failed", exception);
                }
            } else {
                event.register(CreateGoalDrivenExecutionGameTests.class);
                event.register(CreateThreeModeEquivalenceGameTests.class);
                event.register(PlayerWorkflowGameTests.class);
            }
        } else if (bot) {
            event.register(BotFleetGameTests.class);
        } else if (sitePrep) {
            event.register(SitePreparationGameTests.class);
        } else if (factoryDiagnostic) {
            event.register(dev.stevecreate.agent.forge1201.command.FactoryDiagnosticGameTests.class);
        }
    }

    private static void registerIntegratedVisible(RegisterGameTestsEvent event) {
        try {
            register(event, SitePreparationGameTests.class,
                    "preparedSiteAuthorizesExistingConstructionAndRealC07Production");
            register(event, BotFleetGameTests.class,
                    "threeExistingConstructionBotsRunParallelPhysicalTasks",
                    "fiveExistingConstructionBotsRunParallelPhysicalTasks");
            register(event, CreateThreeModeEquivalenceGameTests.class,
                    "c05CrushingIsEquivalentAcrossAllModes",
                    "c07CuttingIsEquivalentAcrossAllModes",
                    "c06SafeWashingIsEquivalentAcrossAllModes",
                    "c09CompactingIsEquivalentAcrossAllModes",
                    "c08MixingIsEquivalentAcrossAllModes",
                    "c10DeployingIsEquivalentAcrossAllModes");
            register(event, CreateCompositeGameTests.class,
                    "composite01DirectRunsThreeRealStagesAndTwoPhysicalRoutes",
                    "composite01BotsRunsThreeRealStagesAndTwoPhysicalRoutes",
                    "composite01HybridRunsThreeRealStagesAndTwoPhysicalRoutes",
                    "composite02DirectCancelIsolation",
                    "composite02BotsCancelIsolation",
                    "composite02HybridCancelIsolation",
                    "composite03DirectRunsPhysicalBranchAndMerge",
                    "composite03BotsRunPhysicalBranchAndMerge",
                    "composite03HybridRunsPhysicalBranchAndMerge");
            register(event,
                    dev.stevecreate.agent.forge1201.adapter.create.internal.v606
                            .CreateOwnedWorkpieceApplicationGameTests.class,
                    "directExecutorRunsExactThreeTaskGraph",
                    "existingSteveAlexFleetRunsExactThreeTaskGraph",
                    "hybridRoutesLogisticsToSteveAndSensitiveWorkToDirect",
                    "reviewedAndesiteCasingUsesRealDeployerCycle");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Phase IV integrated visible GameTest registration failed", exception);
        }
    }

    private static void register(
            RegisterGameTestsEvent event, Class<?> suite, String... methods)
            throws ReflectiveOperationException {
        for (String method : methods) {
            event.register(suite.getDeclaredMethod(
                    method, net.minecraft.gametest.framework.GameTestHelper.class));
        }
    }
}
