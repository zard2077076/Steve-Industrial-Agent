package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The single-machine order path's accepted goals, now derived rather than listed.
 *
 * <p>The eleven hard-coded (target, quantity) pairs duplicated data the goal catalog
 * already held, which is how two copies drift. Deriving them is only safe if the derived
 * answer is identical, so these pin every field against what the hard-coded list
 * produced — including the one thing the catalog cannot express.</p>
 */
class SingleMachineTargetSpecTest {

    // A null level throughout, and deliberately: these tests are about the eleven
    // reviewed entries, and a null level is exactly the case where the resolver consults
    // only those. Handing them a live registry would test something else.

    /**
     * The reviewed catalog wins over the registry. Both can produce gravel, and only the
     * reviewed entry carries its two machines and its Phase III flow — resolving to the
     * derived one would silently halve the site and change the deployment path.
     */
    @Test
    void aReviewedTargetIsNotReplacedByADerivedOne() {
        for (GoalCatalogEntry entry : PlayerGoalCatalog.entries()) {
            assertThat(SingleMachineGoalResolver.reviewed(entry.target()))
                    .as(entry.target().toString()).isTrue();
            assertThat(SingleMachineGoalResolver.resolve(null, entry.target()))
                    .as(entry.target().toString()).contains(entry);
        }
    }

    /** Without a registry to read, an unreviewed target simply has no entry. */
    @Test
    void anUnreviewedTargetResolvesToNothingWithoutARegistry() {
        ResourceId unreviewed = ResourceId.parse("minecraft:diamond_block");
        assertThat(SingleMachineGoalResolver.reviewed(unreviewed)).isFalse();
        assertThat(SingleMachineGoalResolver.resolve(null, unreviewed)).isEmpty();
    }

    @Test
    void everyCatalogGoalIsAcceptedAtItsVerifiedQuantity() {
        for (GoalCatalogEntry entry : PlayerGoalCatalog.entries()) {
            long quantity = PlayerGoalCatalog.verifiedQuantity(entry);

            assertThat(PilotDeploymentCommand.acceptsSingleMachineGoal(null, entry.target(), quantity))
                    .as("%s x%s", entry.target(), quantity)
                    .isTrue();
        }
        assertThat(PlayerGoalCatalog.entries()).hasSize(11);
    }

    /** The quantities the hard-coded list accepted, unchanged. */
    @Test
    void acceptsExactlyTheQuantitiesTheHardCodedListDid() {
        assertThat(accepts("minecraft:gravel", 3)).isTrue();
        assertThat(accepts("create:iron_sheet", 2)).isTrue();
        assertThat(accepts("minecraft:sand", 1)).isTrue();
        assertThat(accepts("create:cogwheel", 1)).isTrue();

        // The limit is now the recipe's duration against the executor's one-step
        // deadline, not whichever quantity somebody happened to verify. Six batches of a
        // 200-tick recipe fit inside 2400 ticks and were physically run; seven do not
        // fit the same budget, so gravel x7 stays refused exactly as this test has
        // asserted since the hard-coded list was first derived.
        assertThat(accepts("minecraft:gravel", 6)).isTrue();
        assertThat(accepts("minecraft:gravel", 7)).isFalse();
        // Below a verified quantity was refused too, which never had a reason behind it:
        // less material finishes strictly sooner than more.
        assertThat(accepts("minecraft:gravel", 1)).isTrue();
        assertThat(accepts("create:iron_sheet", 1)).isTrue();
        // Zero and negatives are not quantities.
        assertThat(accepts("minecraft:gravel", 0)).isFalse();
        assertThat(accepts("minecraft:gravel", -1)).isFalse();
    }

    @Test
    void refusesAGoalThatIsNotInTheCatalog() {
        assertThat(accepts("minecraft:netherite_ingot", 1)).isFalse();
        assertThat(accepts("minecraft:dirt", 1)).isFalse();
    }

    /** Inputs scale by batch, which is how gravel x3 needed three andesite. */
    @Test
    void derivesTheSameInputsTheHardCodedListStated() {
        assertThat(inputsFor("minecraft:gravel", 3))
                .containsExactlyInAnyOrderEntriesOf(Map.of(id("minecraft:andesite"), 3L));
        assertThat(inputsFor("create:iron_sheet", 2))
                .containsExactlyInAnyOrderEntriesOf(Map.of(id("minecraft:iron_ingot"), 2L));
        assertThat(inputsFor("create:cogwheel", 1)).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("create:shaft"), 1L, id("minecraft:oak_planks"), 1L));
        assertThat(inputsFor("create:shaft", 6)).containsExactlyInAnyOrderEntriesOf(
                Map.of(id("create:andesite_alloy"), 1L));
        assertThat(inputsFor("create:blaze_cake_base", 1)).containsExactlyInAnyOrderEntriesOf(
                Map.of(id("minecraft:egg"), 1L, id("minecraft:sugar"), 1L,
                        id("create:cinder_flour"), 1L));
    }

    @Test
    void derivesTheSameModuleCountAndSiteRequirement() {
        assertThat(specFor("minecraft:gravel", 3).physicalModuleCount()).isEqualTo(2);
        assertThat(specFor("minecraft:gravel", 3).preparedSiteRequired()).isFalse();
        assertThat(specFor("create:iron_sheet", 2).physicalModuleCount()).isEqualTo(1);
        assertThat(specFor("create:iron_sheet", 2).preparedSiteRequired()).isFalse();
        assertThat(specFor("create:cogwheel", 1).preparedSiteRequired()).isTrue();
    }

    /**
     * The goal catalog carries no material constraints, so a purely derived spec would
     * have dropped this one and let a crushing order consume sandstone instead of
     * gravel, with every other field still looking correct.
     */
    @Test
    void keepsTheConstraintTheGoalCatalogCannotExpress() {
        assertThat(specFor("minecraft:sand", 1).materialConstraints().forbiddenResources())
                .contains(id("minecraft:sandstone"));
        assertThat(specFor("minecraft:gravel", 3).materialConstraints().forbiddenResources())
                .isEmpty();
    }

    private static boolean accepts(String target, long quantity) {
        return PilotDeploymentCommand.acceptsSingleMachineGoal(null, id(target), quantity);
    }

    private static Map<ResourceId, Long> inputsFor(String target, long quantity) {
        return specFor(target, quantity).inputs();
    }

    private static PilotDeploymentCommand.TargetSpec specFor(String target, long quantity) {
        return PilotDeploymentCommand.TargetSpec.supported(null, id(target), quantity);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
