package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deriving a Composite order from a goal instead of writing it by hand.
 *
 * <p>The decisive test is whether expansion rediscovers Composite-01 — the graph a
 * human wrote and that has real three-mode acceptance evidence. If the hand-written
 * knowledge is derivable, the same machinery can reach products nobody enumerated. If
 * it is not, automatic expansion would be producing graphs no one has reason to trust.</p>
 */
class CompositeGraphExpanderTest {
    private static final ResourceId OAK_LOG = id("minecraft:oak_log");
    private static final ResourceId STRIPPED = id("minecraft:stripped_oak_log");
    private static final ResourceId PLANKS = id("minecraft:oak_planks");
    private static final ResourceId COGWHEEL = id("create:cogwheel");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId PULP = id("create:pulp");
    private static final ResourceId CARDBOARD = id("create:cardboard");
    private static final ResourceId WATER = id("minecraft:water");
    private static final ResourceId CUTTING = id("create:cutting");
    private static final ResourceId DEPLOYING = id("create:deploying");
    private static final ResourceId MIXING = id("create:mixing");
    private static final ResourceId PRESSING = id("create:pressing");

    /** The three recipes Composite-01 is built from, stated only as recipes. */
    private static final List<GoalCatalogEntry> CREATE_WOOD_CHAIN = List.of(
            recipe(STRIPPED, "create:cutting/oak_log", CUTTING, Map.of(OAK_LOG, 1L), 1),
            recipe(PLANKS, "create:cutting/stripped_oak_log", CUTTING, Map.of(STRIPPED, 1L), 6),
            recipe(COGWHEEL, "create:deploying/cogwheel", DEPLOYING,
                    Map.of(PLANKS, 1L, SHAFT, 1L), 1));

    @Test
    void rediscoversTheHandWrittenCompositeOneChain() {
        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                COGWHEEL, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG, SHAFT));

        assertThat(result.success()).as(result.code()).isTrue();
        CompositePlayerOrderSpecV1 derived = result.spec().orElseThrow();
        CompositePlayerOrderSpecV1 handWritten = CompositePlayerOrderCatalogV1.COMPOSITE_01;

        assertThat(derived.target()).isEqualTo(handWritten.target());
        assertThat(derived.graph().shape()).isEqualTo(handWritten.graph().shape());
        assertThat(derived.stages()).extracting(
                        CompositePlayerOrderSpecV1.StageSpec::target,
                        CompositePlayerOrderSpecV1.StageSpec::targetQuantity)
                .containsExactlyElementsOf(handWritten.stages().stream()
                        .map(stage -> org.assertj.core.groups.Tuple.tuple(
                                stage.target(), stage.targetQuantity()))
                        .toList());
    }

    /** The derived order must charge the same material as the reviewed one. */
    @Test
    void chargesTheSameExternalMaterialAsTheReviewedOrder() {
        CompositePlayerOrderSpecV1 derived = CompositeGraphExpander.expand(
                COGWHEEL, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG, SHAFT)).spec().orElseThrow();

        assertThat(derived.externalProcessInputs()).containsExactlyInAnyOrderEntriesOf(
                CompositePlayerOrderCatalogV1.COMPOSITE_01.externalProcessInputs());
        assertThat(derived.intermediateSalvage()).containsExactlyInAnyOrderEntriesOf(
                CompositePlayerOrderCatalogV1.COMPOSITE_01.intermediateSalvage());
    }

    /** Derived infrastructure must match what the layout installs, or the spec refuses. */
    @Test
    void declaresTheSameInfrastructureTheReviewedOrderDoes() {
        CompositePlayerOrderSpecV1 derived = CompositeGraphExpander.expand(
                COGWHEEL, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG, SHAFT)).spec().orElseThrow();

        assertThat(derived.infrastructureMaterials()).containsExactlyInAnyOrderEntriesOf(
                CompositePlayerOrderCatalogV1.COMPOSITE_01.infrastructureMaterials());
    }

    @Test
    void reportsRawMaterialTheChainNeedsButThePlayerDidNotOffer() {
        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                COGWHEEL, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG));

        assertThat(result.success()).isTrue();
        assertThat(result.unsuppliedRawMaterials()).containsExactly(SHAFT);
    }

    /**
     * One recipe start to finish is one machine, not a composite. Calling it a refusal
     * counted a whole category of reachable product as a missing capability — 144 of
     * them in the live registry.
     */
    @Test
    void classifiesAOneRecipeGoalAsASingleMachineOrder() {
        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                STRIPPED, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG));

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("SINGLE_MACHINE_ORDER");
        assertThat(result.spec()).isEmpty();
        CompositeGraphExpander.SingleMachineOrder order = result.singleMachine().orElseThrow();
        assertThat(order.recipe().target()).isEqualTo(STRIPPED);
        assertThat(order.batches()).isEqualTo(1);
        assertThat(order.inputs()).containsExactlyInAnyOrderEntriesOf(Map.of(OAK_LOG, 1L));
        assertThat(order.surplus()).isZero();
    }

    /**
     * The same goal as a one-stage chain, for the machinery that only speaks composite.
     *
     * <p>Classification and capability are different questions. The goal is still one
     * machine — that is what {@link CompositeGraphExpander#expand} reports and it is
     * correct — but everything a production order needs around a machine is built on the
     * composite path, so refusing to express it as a chain kept eighty-five products out
     * of unattended production entirely.</p>
     */
    @Test
    void expressesASingleMachineGoalAsAOneStageChain() {
        CompositeGraphExpander.SingleMachineOrder order = CompositeGraphExpander.expand(
                STRIPPED, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG)).singleMachine().orElseThrow();

        CompositeGraphExpander.Result chain =
                CompositeGraphExpander.expandSingleMachineAsChain(order, 1, Set.of(OAK_LOG));
        assertThat(chain.success()).as(chain.code()).isTrue();
        CompositePlayerOrderSpecV1 spec = chain.spec().orElseThrow();

        assertThat(spec.target()).isEqualTo(STRIPPED);
        assertThat(spec.stages()).hasSize(1);
        assertThat(spec.stages().get(0).processInputs())
                .containsExactlyInAnyOrderEntriesOf(Map.of(OAK_LOG, 1L));
        // No edges means no hoppers and no salvage: two chests, source and delivery.
        assertThat(spec.routes()).isEmpty();
        assertThat(spec.graph().edges()).isEmpty();
        assertThat(spec.infrastructureMaterials())
                .containsExactlyInAnyOrderEntriesOf(Map.of(ResourceId.parse("minecraft:chest"), 2L));
    }

    /** Scaling a one-stage chain still promises what the batches really yield. */
    @Test
    void promisesTheRealYieldOfAScaledOneStageChain() {
        CompositeGraphExpander.SingleMachineOrder order = CompositeGraphExpander.expand(
                PLANKS, 8, CREATE_WOOD_CHAIN, Set.of(STRIPPED)).singleMachine().orElseThrow();

        CompositeGraphExpander.Result chain =
                CompositeGraphExpander.expandSingleMachineAsChain(order, 8, Set.of(STRIPPED));
        assertThat(chain.success()).as(chain.code()).isTrue();
        CompositePlayerOrderSpecV1 spec = chain.spec().orElseThrow();

        // Two batches of six for a request of eight. Promising eight would settle a run
        // that produced twelve as short, and promising twelve would be a claim nobody made.
        assertThat(spec.targetQuantity()).isEqualTo(order.producedTotal());
        assertThat(spec.stages().get(0).targetQuantity()).isEqualTo(order.producedTotal());
    }

    @Test
    void fluidInputGoalStaysSingleMachineUntilCompositeHasAFluidLedger() {
        GoalCatalogEntry pulp = fluidRecipe(
                PULP, "create:mixing/cardboard_pulp", MIXING,
                Map.of(id("minecraft:sugar_cane"), 1L), WATER, 250, 1);
        CompositeGraphExpander.Result classified = CompositeGraphExpander.expand(
                PULP, 1, List.of(pulp), Set.of(id("minecraft:sugar_cane")));

        assertThat(classified.success()).as(classified.code()).isTrue();
        assertThat(classified.code()).isEqualTo("SINGLE_MACHINE_ORDER");
        CompositeGraphExpander.Result chain = CompositeGraphExpander
                .expandSingleMachineAsChain(
                        classified.singleMachine().orElseThrow(), 1,
                        Set.of(id("minecraft:sugar_cane")));
        assertThat(chain.success()).isFalse();
        assertThat(chain.code()).isEqualTo("EXPANSION_FLUID_INPUT_UNSUPPORTED:create:pulp");
    }

    @Test
    void upstreamFluidStepFallsBackToTheFinalSingleMachine() {
        GoalCatalogEntry pulp = fluidRecipe(
                PULP, "create:mixing/cardboard_pulp", MIXING,
                Map.of(id("minecraft:sugar_cane"), 1L), WATER, 250, 1);
        GoalCatalogEntry cardboard = recipe(
                CARDBOARD, "create:pressing/cardboard", PRESSING, Map.of(PULP, 1L), 1);

        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                CARDBOARD, 1, List.of(pulp, cardboard), Set.of(id("minecraft:sugar_cane")));

        assertThat(result.success()).as(result.code()).isTrue();
        assertThat(result.code()).isEqualTo("SINGLE_MACHINE_ORDER");
        assertThat(result.singleMachine().orElseThrow().recipe().target()).isEqualTo(CARDBOARD);
        assertThat(result.singleMachine().orElseThrow().inputs()).containsOnlyKeys(PULP);
    }

    @Test
    void nonFluidAlternativeKeepsAnExistingCompositeChainBuildable() {
        ResourceId dryInput = id("minecraft:paper");
        GoalCatalogEntry fluidPulp = fluidRecipe(
                PULP, "test:a_fluid_pulp", MIXING,
                Map.of(id("minecraft:sugar_cane"), 1L), WATER, 250, 1);
        GoalCatalogEntry dryPulp = recipe(
                PULP, "test:z_dry_pulp", CUTTING, Map.of(dryInput, 2L), 1);
        GoalCatalogEntry cardboard = recipe(
                CARDBOARD, "create:pressing/cardboard", PRESSING, Map.of(PULP, 1L), 1);

        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                CARDBOARD, 1, List.of(fluidPulp, dryPulp, cardboard), Set.of(dryInput));

        assertThat(result.success()).as(result.code()).isTrue();
        assertThat(result.singleMachine()).isEmpty();
        assertThat(result.spec().orElseThrow().stages()).hasSize(2);
        assertThat(result.spec().orElseThrow().stages().get(0).processInputs())
                .containsExactlyInAnyOrderEntriesOf(Map.of(dryInput, 2L));
    }

    /** A single machine scales by batches too, and its surplus is still the player's. */
    @Test
    void scalesASingleMachineOrderAndReportsItsSurplus() {
        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                PLANKS, 8, CREATE_WOOD_CHAIN, Set.of(STRIPPED));

        CompositeGraphExpander.SingleMachineOrder order = result.singleMachine().orElseThrow();
        // Six per batch, eight wanted, so two batches yielding twelve.
        assertThat(order.batches()).isEqualTo(2);
        assertThat(order.producedTotal()).isEqualTo(12);
        assertThat(order.surplus()).isEqualTo(4);
        assertThat(order.inputs()).containsExactlyInAnyOrderEntriesOf(Map.of(STRIPPED, 2L));
    }

    /** A two-step chain is still unsupported, and must not be mistaken for one machine. */
    @Test
    void derivesATwoStepChainThatRunsOneMachineTypeTwice() {
        // Both wood steps are cutting, and this was refused twice over: once for being
        // shorter than three stages, then for using one machine type. The survey showed
        // the second rule refusing every derivable product in the catalog, and its
        // premise was wrong — these are two recipes making two different items.
        CompositeGraphExpander.Result result = CompositeGraphExpander.expand(
                PLANKS, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG));

        assertThat(result.success()).as(result.code()).isTrue();
        CompositePlayerOrderSpecV1 spec = result.spec().orElseThrow();
        assertThat(spec.stages()).extracting(CompositePlayerOrderSpecV1.StageSpec::target)
                .containsExactly(STRIPPED, PLANKS);
        assertThat(result.singleMachine()).isEmpty();

        // One plank was asked for; cutting a stripped log yields six and there is no
        // smaller batch. The order promises six, because settlement checks the delivery
        // chest against this number exactly — a spec promising one would be refused as
        // unverified by the six planks that actually arrive. This failed a physical gate
        // before it was a test.
        assertThat(spec.targetQuantity()).isEqualTo(6L);
        assertThat(spec.stages().get(1).targetQuantity()).isEqualTo(6L);
        // The first stage is unchanged: one log in, one stripped log out, and it is the
        // downstream demand that sets the batch count.
        assertThat(spec.stages().get(0).targetQuantity()).isEqualTo(1L);
    }

    /**
     * The change C3b exists for. Before it, this chain was refused for its length and no
     * derived product could be built at all.
     */
    @Test
    void derivesATwoStageChainAcrossTwoMachines() {
        List<GoalCatalogEntry> chain = List.of(
                recipe(STRIPPED, "create:cutting/oak_log", CUTTING, Map.of(OAK_LOG, 1L), 1),
                recipe(COGWHEEL, "create:deploying/stripped", DEPLOYING,
                        Map.of(STRIPPED, 1L, SHAFT, 1L), 1));

        CompositeGraphExpander.Result result =
                CompositeGraphExpander.expand(COGWHEEL, 1, chain, Set.of(OAK_LOG, SHAFT));

        assertThat(result.success()).as(result.code()).isTrue();
        CompositePlayerOrderSpecV1 spec = result.spec().orElseThrow();
        assertThat(spec.stages()).hasSize(2);
        assertThat(spec.routes()).hasSize(1);
        assertThat(spec.graph().shape())
                .isEqualTo(CompositeProductionGraph.Shape.LINEAR_CHAIN);
        // Infrastructure is per stage, not the old fixed three: two source chests, one
        // buffer, one delivery. Over-ordering here would reserve a chest the player
        // never gets back.
        assertThat(spec.infrastructureMaterials().get(ResourceId.parse("minecraft:chest")))
                .isEqualTo(4L);
        assertThat(spec.infrastructureMaterials().get(ResourceId.parse("minecraft:hopper")))
                .isEqualTo(1L);
    }

    /** A goal is one thing or the other; never both. */
    @Test
    void neverClassifiesAGoalAsBothCompositeAndSingleMachine() {
        for (CompositeGraphExpander.Result result : List.of(
                CompositeGraphExpander.expand(COGWHEEL, 1, CREATE_WOOD_CHAIN,
                        Set.of(OAK_LOG, SHAFT)),
                CompositeGraphExpander.expand(STRIPPED, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG)))) {
            assertThat(result.spec().isPresent() && result.singleMachine().isPresent()).isFalse();
            assertThat(result.spec().isPresent() || result.singleMachine().isPresent()).isTrue();
        }
    }

    @Test
    void refusesAProductNothingCanMake() {
        assertThat(CompositeGraphExpander.expand(
                id("minecraft:netherite_ingot"), 1, CREATE_WOOD_CHAIN, Set.of()).code())
                .startsWith("EXPANSION_NO_RECIPE_FOR");
    }

    /** Two produced inputs is a branch, which this deliberately does not emit. */
    @Test
    void refusesAChainThatIsNotLinear() {
        List<GoalCatalogEntry> branching = List.of(
                recipe(STRIPPED, "create:cutting/oak_log", CUTTING, Map.of(OAK_LOG, 1L), 1),
                recipe(PLANKS, "create:cutting/stripped_oak_log", CUTTING, Map.of(STRIPPED, 1L), 6),
                recipe(SHAFT, "create:cutting/shaft", CUTTING, Map.of(PLANKS, 1L), 1),
                recipe(COGWHEEL, "create:deploying/cogwheel", DEPLOYING,
                        Map.of(PLANKS, 1L, SHAFT, 1L), 1));

        CompositeGraphExpander.Result result =
                CompositeGraphExpander.expand(COGWHEEL, 1, branching, Set.of(OAK_LOG));

        // No linear chain exists here, so the goal falls back to its last machine and the
        // player supplies the planks and shaft. Refusing instead was affordable while few
        // products had an upstream at all; once tag ingredients resolved, 40 products a
        // warehouse had been stocking acquired one, became chains, and stopped being
        // producible — the agent got worse at its job by learning more recipes.
        assertThat(result.code()).isEqualTo("SINGLE_MACHINE_ORDER");
        CompositeGraphExpander.SingleMachineOrder order = result.singleMachine().orElseThrow();
        assertThat(order.recipe().target()).isEqualTo(COGWHEEL);
        assertThat(order.inputs()).containsOnlyKeys(PLANKS, SHAFT);
    }

    /** A recipe loop must terminate rather than expand forever. */
    @Test
    void refusesARecipeCycle() {
        List<GoalCatalogEntry> cyclic = List.of(
                recipe(PLANKS, "test:a", CUTTING, Map.of(STRIPPED, 1L), 1),
                recipe(STRIPPED, "test:b", CUTTING, Map.of(PLANKS, 1L), 1));

        CompositeGraphExpander.Result result =
                CompositeGraphExpander.expand(PLANKS, 1, cyclic, Set.of());

        // Terminates, and still produces something orderable: one machine making planks
        // out of stripped logs the player brings. Looping forever is the failure this
        // guards against, not the loop existing.
        assertThat(result.code()).isEqualTo("SINGLE_MACHINE_ORDER");
        assertThat(result.singleMachine().orElseThrow().recipe().target()).isEqualTo(PLANKS);
    }

    /** A goal with no recipe at all is still refused; the fallback needs one to fall back to. */
    @Test
    void stillRefusesAGoalNothingProduces() {
        assertThat(CompositeGraphExpander.expand(COGWHEEL, 1, List.of(), Set.of()).code())
                .startsWith("EXPANSION_NO_RECIPE_FOR");
    }

    /** Same goal, same graph — a quote a player accepts must be reproducible. */
    @Test
    void expandsDeterministically() {
        String first = CompositeGraphExpander.expand(COGWHEEL, 1, CREATE_WOOD_CHAIN,
                Set.of(OAK_LOG, SHAFT)).spec().orElseThrow().graph().fingerprint();
        String second = CompositeGraphExpander.expand(COGWHEEL, 1,
                List.of(CREATE_WOOD_CHAIN.get(2), CREATE_WOOD_CHAIN.get(0),
                        CREATE_WOOD_CHAIN.get(1)),
                Set.of(OAK_LOG, SHAFT)).spec().orElseThrow().graph().fingerprint();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void neverEmitsAShapeTheLayoutCannotBuild() {
        CompositePlayerOrderSpecV1 derived = CompositeGraphExpander.expand(
                COGWHEEL, 1, CREATE_WOOD_CHAIN, Set.of(OAK_LOG, SHAFT)).spec().orElseThrow();

        assertThat(derived.graph().shape())
                .isEqualTo(CompositeProductionGraph.Shape.LINEAR_CHAIN);
        assertThat(derived.rootSplit()).isEmpty();
    }

    /**
     * A downstream stage needing more than one upstream batch. Treating per-batch
     * figures as totals emitted an edge claiming more than its producer makes — a graph
     * whose material cannot balance, which the live-registry survey caught once.
     */
    @Test
    void scalesUpstreamStagesToTheBatchesDownstreamNeeds() {
        // Middle stage yields 2 per batch; the sink consumes 5 of them per batch.
        List<GoalCatalogEntry> chain = List.of(
                recipe(STRIPPED, "test:strip", CUTTING, Map.of(OAK_LOG, 1L), 1),
                recipe(PLANKS, "test:plank", CUTTING, Map.of(STRIPPED, 1L), 2),
                recipe(COGWHEEL, "test:cog", DEPLOYING, Map.of(PLANKS, 5L, SHAFT, 1L), 1));

        CompositeGraphExpander.Result result =
                CompositeGraphExpander.expand(COGWHEEL, 1, chain, Set.of(OAK_LOG, SHAFT));

        assertThat(result.success()).as(result.code()).isTrue();
        CompositePlayerOrderSpecV1 spec = result.spec().orElseThrow();
        // Five planks needed, two per batch, so three batches yielding six.
        assertThat(spec.stages().get(1).targetQuantity()).isEqualTo(6);
        // Three plank batches consume three stripped logs, so the first stage runs three.
        assertThat(spec.stages().get(0).targetQuantity()).isEqualTo(3);
        assertThat(spec.stages().get(0).processInputs()).containsEntry(OAK_LOG, 3L);
        assertThat(spec.stages().get(1).processInputs()).containsEntry(STRIPPED, 3L);
        // The sixth plank is unclaimed and must be settled as salvage, not lost.
        assertThat(spec.intermediateSalvage()).containsEntry(PLANKS, 1L);
        assertThat(spec.externalProcessInputs())
                .containsEntry(OAK_LOG, 3L)
                .containsEntry(SHAFT, 1L);
    }

    /** No edge may ever claim more than the stage feeding it actually produces. */
    @Test
    void neverEmitsAnEdgeClaimingMoreThanItsProducerMakes() {
        List<GoalCatalogEntry> chain = List.of(
                recipe(STRIPPED, "test:strip", CUTTING, Map.of(OAK_LOG, 1L), 1),
                recipe(PLANKS, "test:plank", CUTTING, Map.of(STRIPPED, 3L), 4),
                recipe(COGWHEEL, "test:cog", DEPLOYING, Map.of(PLANKS, 7L, SHAFT, 1L), 1));

        CompositePlayerOrderSpecV1 spec = CompositeGraphExpander.expand(
                COGWHEEL, 1, chain, Set.of(OAK_LOG, SHAFT)).spec().orElseThrow();

        spec.graph().edges().forEach(edge -> assertThat(edge.quantity())
                .as("edge %s", edge.edgeId())
                .isLessThanOrEqualTo(spec.stage(edge.producerNodeId()).targetQuantity()));
    }

    private static GoalCatalogEntry recipe(
            ResourceId target, String recipeId, ResourceId capability,
            Map<ResourceId, Long> inputs, long outputPerBatch) {
        return new GoalCatalogEntry(target, id(recipeId), capability, inputs,
                outputPerBatch, 1, true, true);
    }

    private static GoalCatalogEntry fluidRecipe(
            ResourceId target,
            String recipeId,
            ResourceId capability,
            Map<ResourceId, Long> inputs,
            ResourceId fluid,
            long millibuckets,
            long outputPerBatch) {
        return new GoalCatalogEntry(
                target, id(recipeId), capability, inputs, outputPerBatch,
                1, true, true, java.util.OptionalLong.of(100), Map.of(),
                Map.of(fluid, millibuckets));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
