package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Derives a Composite order from a production goal instead of a hand-written catalog.
 *
 * <p>Every orderable graph so far was written by hand: nodes, edges, per-stage inputs,
 * fleet sizes and infrastructure counts, all as constants. That does not scale to the
 * project's actual goal, where a player names a product and the agent works out the
 * chain. This is the first step of that — bounded deliberately.</p>
 *
 * <p>It only produces {@code LINEAR_CHAIN}, because that is a shape
 * {@code CompositeSiteLayout} can already build and {@code CompositeLayoutContract} can
 * already judge without a server. Expanding into shapes nothing can physically place
 * would produce graphs that look right and cannot run, which is the failure mode this
 * project keeps paying for. Widening the output is a later step that belongs with a
 * layout able to receive it.</p>
 *
 * <p>Recipe selection is deterministic: among candidates the lowest input-type count
 * wins, then the recipe id. A planner that silently picks differently between runs
 * would make a reported bill unreproducible.</p>
 */
public final class CompositeGraphExpander {
    /** The linear layout is exactly three handlers, so a longer or shorter chain is refused. */
    /**
     * The layout installs one stage per chain step, so the bounds are the layout's.
     * The lower bound was three, which refused every chain the live catalog actually
     * contains — the survey found 12 two-step chains and no three-step chain at all.
     */
    public static final int MIN_LINEAR_STAGES = 2;
    /**
     * Three, and measured rather than assumed.
     *
     * <p>Raised to five as a probe on 2026-08-09 and it unlocked nothing: same 311
     * products, same 121 chains, same 189 maintainable, and no planner failures either —
     * so the layout and executor would have carried a longer chain, there simply were not
     * any. The 50 goals that once exceeded this limit still fall back to a single machine
     * at five stages, which means they need more depth than that or meet a branch or a
     * cycle further up.
     *
     * <p>Left at three because a longer chain is a bigger site, a longer run and more
     * ways to fail, and none of that buys a single product today.</p>
     */
    public static final int MAX_LINEAR_STAGES = 3;

    private CompositeGraphExpander() {}

    /**
     * @param target what the player asked for
     * @param recipes every reviewed recipe that may be used
     * @param rawMaterials what the player is expected to supply themselves
     */
    public static Result expand(
            ResourceId target,
            long targetQuantity,
            List<GoalCatalogEntry> recipes,
            Set<ResourceId> rawMaterials) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(rawMaterials, "rawMaterials");
        if (targetQuantity < 1 || targetQuantity > 64) {
            return Result.refused("EXPANSION_TARGET_QUANTITY_OUT_OF_BOUNDS");
        }
        Map<ResourceId, List<GoalCatalogEntry>> byOutput = index(recipes);

        // Walk back from the product, taking one producing recipe per step. Each step
        // must have exactly one input that is itself produced, or the chain is not linear.
        List<GoalCatalogEntry> reversed = new ArrayList<>();
        Set<ResourceId> visited = new LinkedHashSet<>();
        ResourceId current = target;
        while (reversed.size() <= MAX_LINEAR_STAGES) {
            if (!visited.add(current)) {
                return fallbackToSingleMachine(reversed, targetQuantity, rawMaterials,
                        "EXPANSION_RECIPE_CYCLE:" + current);
            }
            GoalCatalogEntry step = select(byOutput.get(current));
            if (step == null) {
                return reversed.size() >= MIN_LINEAR_STAGES
                        ? build(target, targetQuantity, reversed, rawMaterials)
                        : Result.refused("EXPANSION_NO_RECIPE_FOR:" + current);
            }
            reversed.add(step);
            // Fluid material is carried by the verified single-machine sidecar and paid
            // for as an exact bucket. CompositePlayerOrderSpecV1 still has only ITEM
            // stage inputs and routes, so emitting this step in a chain would erase the
            // fluid from both the reservation and the executor. Keep the final target
            // orderable by falling back to its last machine, with any fluid-made
            // intermediate supplied by the player, until the composite ledger has an
            // explicit FLUID contract.
            if (!step.fluidInputsPerBatch().isEmpty()) {
                return fallbackToSingleMachine(reversed, targetQuantity, rawMaterials,
                        "EXPANSION_FLUID_INPUT_UNSUPPORTED:" + step.target());
            }
            List<ResourceId> produced = step.inputsPerBatch().keySet().stream()
                    .filter(input -> !rawMaterials.contains(input))
                    .filter(byOutput::containsKey)
                    .toList();
            if (produced.isEmpty()) {
                if (reversed.size() >= MIN_LINEAR_STAGES) {
                    return build(target, targetQuantity, reversed, rawMaterials);
                }
                // One recipe start to finish is not a composite and never should be:
                // it is one machine, which is what the ordinary single-machine order
                // path exists for. Calling that a refusal counted an entire category of
                // reachable product as a missing capability.
                if (reversed.size() == 1) {
                    return singleMachine(reversed.get(0), targetQuantity, rawMaterials);
                }
                return Result.refused("EXPANSION_CHAIN_LENGTH_UNSUPPORTED:" + reversed.size());
            }
            if (produced.size() > 1) {
                return fallbackToSingleMachine(reversed, targetQuantity, rawMaterials,
                        "EXPANSION_BRANCHING_NOT_LINEAR:" + step.target());
            }
            current = produced.get(0);
        }
        return fallbackToSingleMachine(reversed, targetQuantity, rawMaterials,
                "EXPANSION_CHAIN_LONGER_THAN_LINEAR_LAYOUT");
    }

    /**
     * When no legal chain exists, make the thing with one machine and buy the rest.
     *
     * <p>A chain that runs past the layout's three stages, or loops, used to be refused
     * outright. That was fine while few products had an upstream recipe at all; once tag
     * ingredients started resolving, 40 products that a warehouse had been keeping in
     * stock acquired an upstream, became chains, exceeded the limit and stopped being
     * producible. The agent got worse at its job because it learned more recipes.
     *
     * <p>The last recipe on its own still makes the product; its inputs simply come from
     * the player rather than from a stage we build. That is exactly the single-machine
     * order, and it is what the goal was before the extra recipes arrived.
     *
     * <p>Only when a chain cannot be formed. A legal chain is still preferred, because it
     * is the one that makes its own intermediates.</p>
     */
    private static Result fallbackToSingleMachine(
            List<GoalCatalogEntry> reversed,
            long targetQuantity,
            Set<ResourceId> rawMaterials,
            String chainRefusal) {
        if (reversed.isEmpty()) return Result.refused(chainRefusal);
        return singleMachine(reversed.get(0), targetQuantity, rawMaterials);
    }

    /** Fewest input types first, then recipe id, so the same goal always expands alike. */
    private static GoalCatalogEntry select(List<GoalCatalogEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        // No executionVerified filter. Every candidate here already passed
        // RecipeProjectionPolicy, which is the admission this step relies on; the field
        // means "something has physically run this", which is false for everything a
        // live registry yields and would filter out the entire input.
        // Counted by what the player has to put together, tools included.
        //
        // A borrowed tool is still a thing to be found and reserved, and leaving it out
        // made every deployer recipe look like the simplest way to make its product. They
        // were then chosen ahead of recipes that actually plan, and 40 products that a
        // warehouse could keep in stock stopped being maintainable — measured, not
        // guessed: 145 fell to 105 the moment tool tags started resolving.
        return candidates.stream()
                .min(java.util.Comparator
                        // Composite has no FLUID stage ledger yet. Prefer a legal ITEM-only
                        // route when one exists; selecting a smaller fluid recipe first and
                        // then falling back made newly discovered knowledge hide an older,
                        // buildable chain.
                        .comparing((GoalCatalogEntry entry) ->
                                !entry.fluidInputsPerBatch().isEmpty())
                        .thenComparingInt(entry -> entry.inputsPerBatch().size()
                                + entry.retainedToolsPerBatch().size())
                        .thenComparing(entry -> entry.recipe().toString()))
                .orElse(null);
    }

    /**
     * Classifies a one-recipe goal as a single-machine order.
     *
     * <p>Deliberately a classification and not a plan. The existing single-machine path
     * accepts eleven hard-coded (target, quantity) pairs, so almost nothing classified
     * here can currently be executed — generalising that path is separate work of about
     * the same size as this expander. Reporting it as a refusal, which is what happened
     * before, made a routing gap look like a missing capability.</p>
     */
    private static Result singleMachine(
            GoalCatalogEntry recipe, long targetQuantity, Set<ResourceId> rawMaterials) {
        long batches = ceilDiv(targetQuantity, recipe.outputPerBatch());
        Map<ResourceId, Long> inputs = scale(recipe.inputsPerBatch(), batches);
        if (inputs == null) {
            return Result.refused("EXPANSION_INPUT_QUANTITY_OVERFLOW:" + recipe.recipe());
        }
        List<ResourceId> missing = inputs.keySet().stream()
                .filter(input -> !rawMaterials.contains(input)).toList();
        return new Result(true, "SINGLE_MACHINE_ORDER", Optional.empty(), missing,
                Optional.of(new SingleMachineOrder(recipe, targetQuantity, batches,
                        batches * recipe.outputPerBatch(), inputs)));
    }

    /**
     * The same goal expressed as a one-stage chain, for callers that need the production
     * path rather than the classification.
     *
     * <p>{@link SingleMachineOrder} says correctly that a goal is one machine, and C3a
     * draws that line on purpose. But everything a production order needs around a
     * machine — the material ledger, multi-source reservation, a carried delivery,
     * residency, unattended dispatch, restart recovery, site failover — is built on the
     * composite path and on no other, so the classification alone left eighty-five
     * single-machine products with none of it.
     *
     * <p>This is an addition, not a reclassification: {@link #expand} still reports the
     * goal as one machine, and a caller that wants a runnable order asks for this.</p>
     */
    public static Result expandSingleMachineAsChain(
            SingleMachineOrder order, long targetQuantity, Set<ResourceId> rawMaterials) {
        Objects.requireNonNull(order, "order");
        if (!order.recipe().fluidInputsPerBatch().isEmpty()) {
            return Result.refused("EXPANSION_FLUID_INPUT_UNSUPPORTED:"
                    + order.recipe().target());
        }
        // Built by the same routine as every other chain rather than by a parallel
        // constructor: a hand-rolled one-stage spec is exactly the kind of thing that
        // drifts from what the layout installs. With one recipe there are no edges, so
        // the batch propagation loop does not run and the infrastructure comes out at
        // two chests and no hoppers, which is what a lone machine needs.
        // Returns the whole Result rather than an Optional: a refusal that says only
        // "empty" is a refusal nobody can act on, and every earlier version of this in
        // the project cost a survey round to re-derive.
        return build(order.recipe().target(), targetQuantity,
                List.of(order.recipe()), rawMaterials);
    }

    private static Result build(
            ResourceId target,
            long targetQuantity,
            List<GoalCatalogEntry> reversed,
            Set<ResourceId> rawMaterials) {
        List<GoalCatalogEntry> ordered = new ArrayList<>(reversed);
        java.util.Collections.reverse(ordered);
        String slug = target.path().replace('/', '_');
        ResourceId orderType = ResourceId.parse("steve_industrial:derived/" + slug);
        ResourceId line = ResourceId.parse("steve_industrial:derived/" + slug + "_line");

        // Batch propagation, sink first. A recipe states what one batch yields and
        // consumes, so a stage whose downstream needs more than one batch must run more
        // than once — and everything it consumes scales with it. Treating per-batch
        // figures as totals happened to work only because every reviewed chain needed
        // exactly one batch per stage; on real recipe data it emits edges claiming more
        // than their producer makes, which is a graph whose material cannot balance.
        int count = ordered.size();
        long[] batches = new long[count];
        long[] producedTotal = new long[count];
        long[] carriedUnits = new long[count];
        batches[count - 1] = ceilDiv(targetQuantity, ordered.get(count - 1).outputPerBatch());
        producedTotal[count - 1] = batches[count - 1] * ordered.get(count - 1).outputPerBatch();
        for (int index = count - 2; index >= 0; index--) {
            GoalCatalogEntry downstream = ordered.get(index + 1);
            ResourceId carried = ordered.get(index).target();
            long perBatch = downstream.inputsPerBatch().getOrDefault(carried, 0L);
            if (perBatch < 1) {
                return Result.refused("EXPANSION_STAGE_DOES_NOT_CONSUME_ITS_PREDECESSOR");
            }
            long needed = Math.multiplyExact(perBatch, batches[index + 1]);
            carriedUnits[index] = needed;
            batches[index] = ceilDiv(needed, ordered.get(index).outputPerBatch());
            producedTotal[index] = batches[index] * ordered.get(index).outputPerBatch();
        }

        List<CompositeProductionGraph.Node> nodes = new ArrayList<>();
        List<CompositeProductionGraph.MaterialEdge> edges = new ArrayList<>();
        List<CompositePlayerOrderSpecV1.StageSpec> stages = new ArrayList<>();
        List<CompositePlayerOrderSpecV1.RouteSpec> routes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            GoalCatalogEntry recipe = ordered.get(index);
            ResourceId nodeId = ResourceId.parse(
                    "steve_industrial:derived/" + slug + "_" + index);
            nodes.add(new CompositeProductionGraph.Node(
                    nodeId, recipe.capability(), line, Set.of()));
            // Every stage declares what it will actually produce, the last one included.
            // It used to declare the requested quantity instead, which is the same number
            // only when the final recipe yields exactly one per batch — true for
            // Composite-01's cogwheel, so the inconsistency stayed invisible until a
            // derived chain ended in cutting, where one log yields six planks. The order
            // then promised one plank, six arrived, and settlement correctly refused to
            // call that verified.
            long produced = producedTotal[index];
            Map<ResourceId, Long> scaledInputs = scale(recipe.inputsPerBatch(), batches[index]);
            if (scaledInputs == null) {
                return Result.refused("EXPANSION_INPUT_QUANTITY_OVERFLOW:" + recipe.recipe());
            }
            try {
                stages.add(new CompositePlayerOrderSpecV1.StageSpec(nodeId, recipe.target(),
                        produced, scaledInputs, 3));
            } catch (IllegalArgumentException failure) {
                return Result.refused("EXPANSION_STAGE_OUTSIDE_BOUNDS:" + failure.getMessage());
            }
            if (index == 0) continue;
            long needed = carriedUnits[index - 1];
            if (needed > producedTotal[index - 1]) {
                return Result.refused("EXPANSION_UPSTREAM_CANNOT_SUPPLY_DOWNSTREAM");
            }
            ResourceId edgeId = ResourceId.parse(
                    "steve_industrial:derived/" + slug + "_edge_" + index);
            edges.add(new CompositeProductionGraph.MaterialEdge(
                    edgeId, nodes.get(index - 1).nodeId(), nodeId,
                    ordered.get(index - 1).target(), needed, producedTotal[index - 1]));
            // Overflow exists exactly where the upstream stage makes more than is claimed.
            routes.add(new CompositePlayerOrderSpecV1.RouteSpec(
                    edgeId, producedTotal[index - 1] > needed));
        }

        try {
            CompositeProductionGraph graph = new CompositeProductionGraph(
                    ResourceId.parse("steve_industrial:derived/" + slug),
                    CompositeProductionGraph.Shape.LINEAR_CHAIN, nodes, edges);
            // The order's quantity is what the batch really yields, not what was asked
            // for. Settlement compares the delivery chest against this on the nose, and
            // that strictness is worth keeping — it is what stops an under-producing run
            // from settling as a success. So the promise has to be the honest number.
            CompositePlayerOrderSpecV1 spec = new CompositePlayerOrderSpecV1(
                    orderType, target, producedTotal[count - 1], graph, stages, routes,
                    Optional.empty(),
                    infrastructureFor(routes), capabilities(ordered));
            return new Result(true, "OK", Optional.of(spec),
                    unsuppliedRaw(ordered, rawMaterials), Optional.empty());
        } catch (IllegalArgumentException failure) {
            return Result.refused("EXPANSION_REJECTED_BY_CONTRACT:" + failure.getMessage());
        }
    }

    /**
     * One chest per stage source, one per intermediate buffer, one final delivery, one
     * salvage chest per overflow route, plus a locked hopper per route. This mirrors
     * what the layout actually installs; a mismatch is caught by the spec itself.
     */
    private static Map<ResourceId, Long> infrastructureFor(
            List<CompositePlayerOrderSpecV1.RouteSpec> routes) {
        long overflow = routes.stream()
                .filter(CompositePlayerOrderSpecV1.RouteSpec::overflowRequired).count();
        Map<ResourceId, Long> bill = new LinkedHashMap<>();
        // One chest per stage plus the routing chests; stages is routes+1.
        // This read LINEAR_STAGES, which quietly over-ordered a chest for every
        // chain shorter than three.
        bill.put(ResourceId.parse("minecraft:chest"),
                (long) (routes.size() + 1) + routes.size() + 1 + overflow);
        // Only what is actually needed appears. A chain with no edges needs no hoppers,
        // and a bill that says "zero hoppers" is not a smaller bill — the spec refuses a
        // quantity outside its bound, so every single-stage chain was rejected by the
        // contract with a message about materials rather than about stages.
        if (!routes.isEmpty()) {
            bill.put(ResourceId.parse("minecraft:hopper"), (long) routes.size());
            bill.put(ResourceId.parse("minecraft:redstone_block"), (long) routes.size());
        }
        return Map.copyOf(bill);
    }

    private static List<IndustrialCapability> capabilities(List<GoalCatalogEntry> ordered) {
        return List.of(IndustrialCapability.ITEM_PROCESSING,
                IndustrialCapability.ROTATIONAL_POWER, IndustrialCapability.LOGISTICS);
    }

    /** Raw inputs the chain needs that the player was not expected to supply. */
    private static List<ResourceId> unsuppliedRaw(
            List<GoalCatalogEntry> ordered, Set<ResourceId> rawMaterials) {
        Set<ResourceId> produced = new LinkedHashSet<>();
        ordered.forEach(recipe -> produced.add(recipe.target()));
        List<ResourceId> missing = new ArrayList<>();
        ordered.forEach(recipe -> recipe.inputsPerBatch().keySet().stream()
                .filter(input -> !produced.contains(input))
                .filter(input -> !rawMaterials.contains(input))
                .filter(input -> !missing.contains(input))
                .forEach(missing::add));
        return List.copyOf(missing);
    }

    private static long ceilDiv(long value, long divisor) {
        return divisor < 1 ? Long.MAX_VALUE : (value + divisor - 1) / divisor;
    }

    /** Scales one batch's inputs by the batch count, or null if that leaves the bounds. */
    private static Map<ResourceId, Long> scale(Map<ResourceId, Long> perBatch, long batches) {
        Map<ResourceId, Long> scaled = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, Long> entry : perBatch.entrySet()) {
            try {
                scaled.put(entry.getKey(), Math.multiplyExact(entry.getValue(), batches));
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        return scaled;
    }

    private static Map<ResourceId, List<GoalCatalogEntry>> index(List<GoalCatalogEntry> recipes) {
        Map<ResourceId, List<GoalCatalogEntry>> byOutput = new LinkedHashMap<>();
        recipes.forEach(recipe -> byOutput
                .computeIfAbsent(recipe.target(), ignored -> new ArrayList<>()).add(recipe));
        return byOutput;
    }

    /**
     * @param unsuppliedRawMaterials inputs the chain needs beyond what the player offered;
     *     reported rather than silently assumed, so a quote cannot omit a cost
     */
    public record Result(
            boolean success,
            String code,
            Optional<CompositePlayerOrderSpecV1> spec,
            List<ResourceId> unsuppliedRawMaterials,
            Optional<SingleMachineOrder> singleMachine) {
        public Result {
            Objects.requireNonNull(code, "code");
            spec = Objects.requireNonNull(spec, "spec");
            unsuppliedRawMaterials = List.copyOf(
                    Objects.requireNonNull(unsuppliedRawMaterials, "unsuppliedRawMaterials"));
            singleMachine = Objects.requireNonNull(singleMachine, "singleMachine");
            if (spec.isPresent() && singleMachine.isPresent()) {
                throw new IllegalArgumentException("a goal is either a composite or one machine");
            }
        }

        static Result refused(String code) {
            return new Result(false, code, Optional.empty(), List.of(), Optional.empty());
        }
    }

    /**
     * A goal one machine satisfies. Carries totals rather than per-batch figures, for
     * the same reason the composite stages do.
     */
    public record SingleMachineOrder(
            GoalCatalogEntry recipe,
            long targetQuantity,
            long batches,
            long producedTotal,
            Map<ResourceId, Long> inputs) {
        public SingleMachineOrder {
            Objects.requireNonNull(recipe, "recipe");
            inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
            if (targetQuantity < 1 || batches < 1 || producedTotal < targetQuantity) {
                throw new IllegalArgumentException("single machine order quantities are invalid");
            }
        }

        /** Output the goal does not claim, which the player still receives. */
        public long surplus() {
            return producedTotal - targetQuantity;
        }
    }
}
