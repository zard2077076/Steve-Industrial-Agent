package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.MaterialEdge;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Node;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Shape;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reviewed Composite orders a player may start from the terminal.
 *
 * <p>Every entry mirrors a graph that already has real three-mode acceptance evidence.
 * Nothing here admits a new topology: a player chooses one of these, and the runtime
 * still re-plans, re-reserves and re-validates before any handler runs.</p>
 */
public final class CompositePlayerOrderCatalogV1 {
    /** Composite-01: oak log -> stripped log -> six planks -> one cogwheel. */
    public static final CompositePlayerOrderSpecV1 COMPOSITE_01 = composite01();

    /**
     * Composite-03: one typed split feeds two independent branches whose outputs merge
     * into a single large cogwheel.
     */
    public static final CompositePlayerOrderSpecV1 COMPOSITE_03 = composite03();

    private static final Map<ResourceId, CompositePlayerOrderSpecV1> BY_ORDER_TYPE =
            index(List.of(COMPOSITE_01, COMPOSITE_03));

    private CompositePlayerOrderCatalogV1() {}

    public static List<CompositePlayerOrderSpecV1> entries() {
        return List.copyOf(BY_ORDER_TYPE.values());
    }

    public static Optional<CompositePlayerOrderSpecV1> find(ResourceId orderType) {
        return Optional.ofNullable(BY_ORDER_TYPE.get(Objects.requireNonNull(orderType, "orderType")));
    }

    private static CompositePlayerOrderSpecV1 composite01() {
        ResourceId cutLog = id("steve_industrial:composite/01_cut_log");
        ResourceId cutPlanks = id("steve_industrial:composite/01_cut_planks");
        ResourceId deploy = id("steve_industrial:composite/01_deploy");
        ResourceId line = id("steve_industrial:composite/01_line");
        ResourceId strippedRoute = id("steve_industrial:composite/01_stripped_route");
        ResourceId plankRoute = id("steve_industrial:composite/01_plank_route");
        CompositeProductionGraph graph = new CompositeProductionGraph(
                id("steve_industrial:composite/01"), Shape.LINEAR_CHAIN,
                List.of(
                        new Node(cutLog, id("create:cutting"), line, Set.of()),
                        new Node(cutPlanks, id("create:cutting"), line, Set.of()),
                        new Node(deploy, id("create:deploying"), line, Set.of())),
                List.of(
                        new MaterialEdge(strippedRoute, cutLog, cutPlanks,
                                id("minecraft:stripped_oak_log"), 1, 1),
                        new MaterialEdge(plankRoute, cutPlanks, deploy,
                                id("minecraft:oak_planks"), 1, 1)));
        return new CompositePlayerOrderSpecV1(
                id("steve_industrial:composite/01"), id("create:cogwheel"), 1, graph,
                List.of(
                        new CompositePlayerOrderSpecV1.StageSpec(cutLog,
                                id("minecraft:stripped_oak_log"), 1,
                                Map.of(id("minecraft:oak_log"), 1L), 3),
                        new CompositePlayerOrderSpecV1.StageSpec(cutPlanks,
                                id("minecraft:oak_planks"), 6,
                                Map.of(id("minecraft:stripped_oak_log"), 1L), 3),
                        new CompositePlayerOrderSpecV1.StageSpec(deploy,
                                id("create:cogwheel"), 1,
                                Map.of(id("minecraft:oak_planks"), 1L, id("create:shaft"), 1L), 3)),
                List.of(
                        new CompositePlayerOrderSpecV1.RouteSpec(strippedRoute, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(plankRoute, true)),
                Optional.empty(),
                // Three stage sources, two intermediate buffers, one final delivery and
                // one salvage chest, plus one locked hopper per admitted edge.
                Map.of(id("minecraft:chest"), 7L,
                        id("minecraft:hopper"), 2L,
                        id("minecraft:redstone_block"), 2L),
                List.of(IndustrialCapability.ITEM_PROCESSING, IndustrialCapability.ROTATIONAL_POWER,
                        IndustrialCapability.LOGISTICS));
    }

    private static CompositePlayerOrderSpecV1 composite03() {
        ResourceId split = id("steve_industrial:composite/03_split");
        ResourceId wood = id("steve_industrial:composite/03_wood");
        ResourceId alloy = id("steve_industrial:composite/03_alloy");
        ResourceId merge = id("steve_industrial:composite/03_merge");
        ResourceId line = id("steve_industrial:composite/03_line");
        ResourceId rawWood = id("steve_industrial:composite/03_raw_wood");
        ResourceId rawAlloy = id("steve_industrial:composite/03_raw_alloy");
        ResourceId rawPlank = id("steve_industrial:composite/03_raw_plank");
        ResourceId planks = id("steve_industrial:composite/03_planks");
        ResourceId cogwheel = id("steve_industrial:composite/03_cogwheel");
        CompositeProductionGraph graph = new CompositeProductionGraph(
                id("steve_industrial:composite/03"), Shape.BRANCH_MERGE,
                List.of(
                        new Node(split, id("steve_industrial:typed_split"), line, Set.of()),
                        new Node(wood, id("create:cutting"), line, Set.of()),
                        new Node(alloy, id("create:deploying"), line, Set.of()),
                        new Node(merge, id("create:deploying"), line, Set.of())),
                List.of(
                        new MaterialEdge(rawWood, split, wood,
                                id("minecraft:stripped_oak_log"), 1, 1),
                        new MaterialEdge(rawAlloy, split, alloy, id("create:shaft"), 1, 1),
                        new MaterialEdge(rawPlank, split, alloy, id("minecraft:oak_planks"), 1, 1),
                        new MaterialEdge(planks, wood, merge, id("minecraft:oak_planks"), 1, 6),
                        new MaterialEdge(cogwheel, alloy, merge, id("create:cogwheel"), 1, 1)));
        return new CompositePlayerOrderSpecV1(
                id("steve_industrial:composite/03"), id("create:large_cogwheel"), 1, graph,
                List.of(
                        new CompositePlayerOrderSpecV1.StageSpec(wood,
                                id("minecraft:oak_planks"), 6,
                                Map.of(id("minecraft:stripped_oak_log"), 1L), 2),
                        new CompositePlayerOrderSpecV1.StageSpec(alloy,
                                id("create:cogwheel"), 1,
                                Map.of(id("create:shaft"), 1L, id("minecraft:oak_planks"), 1L), 3),
                        new CompositePlayerOrderSpecV1.StageSpec(merge,
                                id("create:large_cogwheel"), 1,
                                Map.of(id("minecraft:oak_planks"), 1L, id("create:cogwheel"), 1L), 3)),
                List.of(
                        new CompositePlayerOrderSpecV1.RouteSpec(rawWood, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(rawAlloy, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(rawPlank, false),
                        new CompositePlayerOrderSpecV1.RouteSpec(planks, true),
                        new CompositePlayerOrderSpecV1.RouteSpec(cogwheel, true)),
                Optional.of(new CompositePlayerOrderSpecV1.RootSplitSpec(split, Map.of(
                        id("minecraft:stripped_oak_log"), 1L,
                        id("create:shaft"), 1L,
                        id("minecraft:oak_planks"), 1L))),
                // Raw source, two branch sources, two branch deliveries, one merge
                // source, two branch salvage boundaries and one final delivery, plus a
                // locked hopper for the split and one per merge route.
                Map.of(id("minecraft:chest"), 9L,
                        id("minecraft:hopper"), 3L,
                        id("minecraft:redstone_block"), 3L),
                List.of(IndustrialCapability.ITEM_PROCESSING, IndustrialCapability.ROTATIONAL_POWER,
                        IndustrialCapability.LOGISTICS));
    }

    private static Map<ResourceId, CompositePlayerOrderSpecV1> index(
            List<CompositePlayerOrderSpecV1> specs) {
        LinkedHashMap<ResourceId, CompositePlayerOrderSpecV1> indexed = new LinkedHashMap<>();
        specs.forEach(spec -> {
            if (indexed.put(spec.orderType(), spec) != null) {
                throw new IllegalStateException("duplicate composite order type " + spec.orderType());
            }
        });
        return Map.copyOf(indexed);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
