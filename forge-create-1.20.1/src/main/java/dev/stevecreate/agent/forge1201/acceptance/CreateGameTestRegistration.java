package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
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
    private static final String GOAL_PROPERTY = "steve_industrial.test.goalDrivenExecutionGameTest";

    private CreateGameTestRegistration() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        boolean c03 = Boolean.getBoolean(C03_PROPERTY);
        boolean c04 = Boolean.getBoolean(C04_PROPERTY);
        boolean goal = Boolean.getBoolean(GOAL_PROPERTY);
        if ((c03 ? 1 : 0) + (c04 ? 1 : 0) + (goal ? 1 : 0) > 1) {
            throw new IllegalStateException("C-03, C-04 and goal-driven GameTests require separate isolated worlds");
        }
        if (c03) {
            event.register(CreateProcessingGameTests.class);
        } else if (c04) {
            event.register(CreateBeltPressGameTests.class);
        } else if (goal) {
            event.register(CreateGoalDrivenExecutionGameTests.class);
        }
    }
}
