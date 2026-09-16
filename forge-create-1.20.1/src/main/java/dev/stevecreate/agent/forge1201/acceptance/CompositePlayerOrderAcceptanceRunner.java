package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderAcceptanceFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Runs the IPO-02 player-order gate once per requested mode on a disposable server.
 *
 * <p>Each mode is a separate order on its own site: the reviewed Composite evidence is
 * per mode, and a Direct pass says nothing about whether the Bots fleet can carry the
 * same physical intermediates.  A failure halts the server rather than logging a pass
 * for a partially settled order.</p>
 */
public final class CompositePlayerOrderAcceptanceRunner {
    public static final String ENABLE_PROPERTY = "steve_industrial.test.compositePlayerOrder";
    /** Comma-separated subset, e.g. {@code -Dsteve_industrial.test.compositePlayerOrderModes=direct,bots}. */
    public static final String MODES_PROPERTY = "steve_industrial.test.compositePlayerOrderModes";
    /** Comma-separated graph subset, e.g. {@code ...compositePlayerOrderGraphs=01,03}. */
    public static final String GRAPHS_PROPERTY = "steve_industrial.test.compositePlayerOrderGraphs";

    private static final List<ExecutionMode> DEFAULT_MODES =
            List.of(ExecutionMode.DIRECT, ExecutionMode.BOTS, ExecutionMode.HYBRID);

    private static boolean refusalChecked;
    private static Logger logger;
    private static List<Run> pending;
    private static CompositePlayerOrderAcceptanceFixture active;
    private static Run current;

    /** One graph in one mode: the unit the reviewed Composite evidence is recorded in. */
    private record Run(ResourceId orderType, ExecutionMode mode) {}

    private CompositePlayerOrderAcceptanceRunner() {}

    /**
     * Arms the gate only.  The first order is created on the first server tick, after
     * every ServerStarted handler has run: reload recovery deliberately pauses each
     * non-terminal order envelope it finds, and a fixture that created one during that
     * same event would race it and be paused before its first tick.
     */
    public static void start(MinecraftServer server, Logger fixtureLogger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("CompositePlayerOrderAcceptanceRunner");
        if (pending != null) throw new IllegalStateException("composite order fixture already started");
        logger = Objects.requireNonNull(fixtureLogger, "fixtureLogger");
        pending = new ArrayList<>();
        for (ResourceId orderType : requestedGraphs()) {
            for (ExecutionMode mode : requestedModes()) {
                pending.add(new Run(orderType, mode));
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (pending == null) return;
        if (active == null) {
            if (!refusalChecked) {
                refusalChecked = true;
                verifyRefusedStartReturnsMaterial(server);
                return;
            }
            if (pending.isEmpty()) return;
            current = pending.remove(0);
            active = CompositePlayerOrderAcceptanceFixture.start(
                    server, current.orderType(), current.mode());
            return;
        }
        CompositePlayerOrderAcceptanceFixture.Result result = active.tick();
        if (result == null) return;
        if (!result.success()) {
            active = null;
            server.halt(false);
            throw new IllegalStateException("composite player-order acceptance failed: "
                    + current.orderType() + ":" + current.mode() + ":" + result.code());
        }
        logger.info("COMPOSITE_PLAYER_ORDER PASS graph={} mode={} playerStarted=true "
                        + "freeBuild=false reservedFromPlayerChest=true output={} "
                        + "salvageTransferred={} materialLedgerBalanced={} baselineRestored={} "
                        + "cleanup=true ticks={}",
                current.orderType(), current.mode(), result.report().outputs(),
                result.report().salvageTransferred(), result.report().materialLedgerBalanced(),
                result.report().baselineRestored(), result.ticks());
        if (pending.isEmpty()) {
            active = null;
            logger.info("COMPOSITE_PLAYER_ORDER_SUITE PASS graphs={} modes={}",
                    requestedGraphs(), requestedModes());
            server.halt(false);
            return;
        }
        current = pending.remove(0);
        active = CompositePlayerOrderAcceptanceFixture.start(
                server, current.orderType(), current.mode());
    }

    /**
     * A refused order must not cost the player anything.
     *
     * <p>This exercises the refusal a player will actually hit — the site is not clear —
     * and asserts the order took nothing and built nothing. It runs first, on its own
     * site, so the happy-path gates below are unaffected.</p>
     */
    private static void verifyRefusedStartReturnsMaterial(MinecraftServer server) {
        CompositePlayerOrderAcceptanceFixture.RefusedStart refused =
                CompositePlayerOrderAcceptanceFixture.startRefused(
                        server, CompositePlayerOrderCatalogV1.COMPOSITE_01.orderType());
        if (!refused.chestAfter().equals(refused.chestBefore())) {
            server.halt(false);
            throw new IllegalStateException("a refused composite start did not return the "
                    + "player's material: before=" + refused.chestBefore()
                    + " after=" + refused.chestAfter());
        }
        if (refused.installedBlocks() != 0) {
            server.halt(false);
            throw new IllegalStateException("a refused composite order installed "
                    + refused.installedBlocks() + " blocks anyway");
        }
        logger.info("COMPOSITE_REFUSED_START PASS code={} materialsUntouched=true "
                        + "installedBlocks=0 chest={}",
                refused.code(), refused.chestAfter());
    }

    /**
     * Defaults to every reviewed graph. A value containing ':' is taken as a product id
     * and ordered by derivation, which is how the gate proves a chain nobody wrote can
     * actually be built rather than merely passing an offline contract.
     */
    private static List<ResourceId> requestedGraphs() {
        String requested = System.getProperty(GRAPHS_PROPERTY);
        if (requested == null || requested.isBlank()) {
            return CompositePlayerOrderCatalogV1.entries().stream()
                    .map(entry -> entry.orderType()).toList();
        }
        List<ResourceId> graphs = new ArrayList<>();
        for (String value : requested.split(",")) {
            String trimmed = value.trim();
            if (trimmed.contains(":")) {
                graphs.add(ResourceId.parse(trimmed));
                continue;
            }
            ResourceId orderType = ResourceId.parse("steve_industrial:composite/" + trimmed);
            if (CompositePlayerOrderCatalogV1.find(orderType).isEmpty()) {
                throw new IllegalStateException("unknown composite gate graph: " + orderType);
            }
            graphs.add(orderType);
        }
        if (graphs.isEmpty()) throw new IllegalStateException("composite gate graph list is empty");
        return List.copyOf(graphs);
    }

    private static List<ExecutionMode> requestedModes() {
        String requested = System.getProperty(MODES_PROPERTY);
        if (requested == null || requested.isBlank()) return DEFAULT_MODES;
        List<ExecutionMode> modes = new ArrayList<>();
        for (String value : requested.split(",")) {
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "direct" -> modes.add(ExecutionMode.DIRECT);
                case "bots" -> modes.add(ExecutionMode.BOTS);
                case "hybrid" -> modes.add(ExecutionMode.HYBRID);
                case "" -> { }
                default -> throw new IllegalArgumentException("unknown composite mode " + value);
            }
        }
        if (modes.isEmpty()) throw new IllegalArgumentException("no composite mode requested");
        return List.copyOf(modes);
    }
}
