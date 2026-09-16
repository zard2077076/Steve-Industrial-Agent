package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.model.QuarterTurn;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Crushing, alone in its own world.
 *
 * <p>Create's crushing wheels grind whatever touches them, including the arena floor, and
 * the dirt item entities that fall out drift into whatever is running next door — C07
 * failed on exactly that twice, and disabling this one test restored the suite both
 * times. That is a property of the machine, not a fault in the test, so repositioning
 * cannot fix it and isolation is the only honest answer.
 *
 * <p>It is a separate class rather than a method in the goal-driven suite because
 * registration is per class: annotating it there would put it back in the shared world.
 * Any future machine that reshapes its surroundings — a drill, a saw that fells trees —
 * belongs here for the same reason.
 */
@PrefixGameTestTemplate(false)
public final class CreateCrushingCapabilityGameTests {
    private CreateCrushingCapabilityGameTests() {}

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "capability_crushing", timeoutTicks = 6_000)
    public static void crushingProducesSand(GameTestHelper helper) {
        CreateGoalDrivenExecutionGameTests.runSuccess(
                helper, "minecraft:sand", 1, "minecraft:gravel", 1, QuarterTurn.ZERO);
    }
}
