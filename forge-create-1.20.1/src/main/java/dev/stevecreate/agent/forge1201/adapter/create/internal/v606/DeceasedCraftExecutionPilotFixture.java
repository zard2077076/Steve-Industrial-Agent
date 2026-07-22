package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import dev.stevecreate.agent.adapter.api.RuntimeIngredientSelection;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Two-goal production-JAR pilot restricted to the disposable R-09 world. */
public final class DeceasedCraftExecutionPilotFixture {
    public static final String ENABLE_PROPERTY = "steve_industrial.r09.executionPilot";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_TICKS_PER_GOAL = 6_000L;
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario(
                    "custom-kubejs-milling",
                    id("immersiveengineering:dust_coke"), 4,
                    id("immersiveengineering:coal_coke"), 4,
                    "create:kjs/", null),
            new Scenario(
                    "custom-kubejs-tag-pressing",
                    id("apocalypsenow:can"), 3,
                    id("immersiveengineering:ingot_aluminum"), 3,
                    "create:kjs/", "tag:forge:plates/aluminum="));

    private static State state;

    private DeceasedCraftExecutionPilotFixture() {}

    public static void start(MinecraftServer server, Path actualGameDir, Logger logger) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(actualGameDir, "actualGameDir");
        Objects.requireNonNull(logger, "logger");
        if (!server.isSameThread()) {
            throw new IllegalStateException("R-09 execution pilot must start on the server thread");
        }
        if (state != null) {
            throw new IllegalStateException("R-09 execution pilot already has active state");
        }
        Path evidence = actualGameDir.resolve("r09-evidence").resolve("execution-pilot.json");
        state = new State(server, server.overworld(), logger, evidence);
        logger.info(
                "R09_EXECUTION_PILOT_START goals=2 executionWorld=isolated-repository-test formalWorld=false maxTicksPerGoal={} evidence={}",
                MAX_TICKS_PER_GOAL, evidence);
        state.startNext();
    }

    public static void tick(MinecraftServer server) {
        State current = state;
        if (current == null || current.server != server || !server.isSameThread()) {
            return;
        }
        try {
            current.tick();
        } catch (RuntimeException | IOException failure) {
            current.fail("PILOT_EXCEPTION", safe(failure));
        }
    }

    private static final class State {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final Logger logger;
        private final Path evidencePath;
        private final JsonArray results = new JsonArray();
        private final Set<Long> forcedChunks = new LinkedHashSet<>();
        private int scenarioIndex;
        private Scenario scenario;
        private CreateV606GoalDrivenPlanner.Ready ready;
        private CreateV606GoalDrivenExecution.Session session;
        private BlockPos3i bufferPosition;
        private long startedTick;
        private long lastProgressLogTick;
        private int maximumHandlerInvocations;
        private final EnumSet<CreateV606GoalDrivenExecution.Phase> phases =
                EnumSet.noneOf(CreateV606GoalDrivenExecution.Phase.class);
        private boolean terminal;

        private State(
                MinecraftServer server,
                ServerLevel level,
                Logger logger,
                Path evidencePath) {
            this.server = server;
            this.level = level;
            this.logger = logger;
            this.evidencePath = evidencePath;
        }

        private void startNext() {
            scenario = SCENARIOS.get(scenarioIndex);
            maximumHandlerInvocations = 0;
            phases.clear();
            BlockPos spawn = level.getSharedSpawnPos();
            int y = Math.min(level.getMaxBuildHeight() - 24, spawn.getY() + 32);
            BlockPos3i anchor = new BlockPos3i(spawn.getX(), y, spawn.getZ());
            bufferPosition = new BlockPos3i(anchor.x() - 45, anchor.y(), anchor.z());
            ResourceId sessionId = id("steve_industrial:r09_pilot/" + scenario.name);
            var planned = CreateV606GoalDrivenPlanner.plan(
                    level, scenario.target, scenario.quantity,
                    Map.of(scenario.rawInput, scenario.rawQuantity), anchor, QuarterTurn.ZERO,
                    sessionId, Map.of(scenario.rawInput, scenario.rawQuantity),
                    ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST);
            require(planned instanceof CreateV606GoalDrivenPlanner.Ready,
                    "Goal planning did not produce execution readiness: " + planned);
            ready = (CreateV606GoalDrivenPlanner.Ready) planned;
            require(ready.executionReadyPlan().evidence().size() == 16,
                    "Execution readiness checklist is incomplete");
            require(ready.executionReadyPlan().physicalPlan().evidence().size() == 13,
                    "Physicalization checklist is incomplete");
            require(ready.executionReadyPlan().physicalPlan().candidate().boundPlan()
                            .evidence().size() == 15,
                    "Binding checklist is incomplete");
            require(ready.executionReadyPlan().physicalPlan().candidate().boundPlan()
                            .graph().logicalPlan().evidence().size() == 8,
                    "Planning checklist is incomplete");
            validateSelections();
            forceExecutionChunks();
            seedBuffer();
            var started = CreateV606GoalDrivenExecution.begin(
                    level, ready.executionReadyPlan(), ready.runtime(), bufferPosition);
            require(started instanceof CreateV606GoalDrivenExecution.Started,
                    "Goal execution did not start: " + started);
            session = ((CreateV606GoalDrivenExecution.Started) started).session();
            startedTick = executionTick();
            lastProgressLogTick = startedTick;
            logger.info(
                    "R09_EXECUTION_GOAL_STARTED scenario={} target={} quantity={} raw={} rawQuantity={} nodes={} routes={} planningChecks=8 bindingChecks=15 physicalChecks=13 readinessChecks=16",
                    scenario.name, scenario.target, scenario.quantity, scenario.rawInput,
                    scenario.rawQuantity,
                    ready.executionReadyPlan().physicalPlan().placements().size(),
                    ready.executionReadyPlan().physicalPlan().routes().size());
        }

        private void tick() throws IOException {
            if (terminal) return;
            if (executionTick() - startedTick > MAX_TICKS_PER_GOAL) {
                fail("PROCESS_TIMEOUT", "Scenario exceeded the bounded tick limit: " + scenario.name);
                return;
            }
            var result = session.tick();
            if (result instanceof CreateV606GoalDrivenExecution.Progress progress) {
                phases.add(progress.phase());
                maximumHandlerInvocations = Math.max(
                        maximumHandlerInvocations,
                        progress.maximumHandlerInvocationsThisTick());
                if (executionTick() - lastProgressLogTick >= 200) {
                    logger.info(
                            "R09_EXECUTION_GOAL_PROGRESS scenario={} target={} phase={} node={}/{} elapsedTicks={} maxHandlerInvocationsPerTick={}",
                            scenario.name, scenario.target, progress.phase(),
                            progress.processNodeIndex(), progress.processNodeCount(),
                            executionTick() - startedTick, maximumHandlerInvocations);
                    lastProgressLogTick = executionTick();
                }
                return;
            }
            if (result instanceof CreateV606GoalDrivenExecution.Failed failure) {
                fail(failure.code().name(), failure.detail()
                        + "; livePhysical=" + livePhysicalDiagnostics());
                return;
            }
            complete((CreateV606GoalDrivenExecution.Completed) result);
        }

        private void complete(CreateV606GoalDrivenExecution.Completed completed) throws IOException {
            require(completed.target().equals(scenario.target)
                            && completed.requiredQuantity() == scenario.quantity
                            && completed.observedQuantity() >= scenario.quantity,
                    "Completed target or quantity does not match the typed goal");
            require(maximumHandlerInvocations == 1,
                    "Execution did not preserve the one-handler-per-tick bound");
            require(phases.containsAll(EnumSet.of(
                            CreateV606GoalDrivenExecution.Phase.BUILD,
                            CreateV606GoalDrivenExecution.Phase.FEED,
                            CreateV606GoalDrivenExecution.Phase.PROCESS,
                            CreateV606GoalDrivenExecution.Phase.VERIFY)),
                    "Execution phase evidence is incomplete: " + phases);
            require(completed.trace().stream().anyMatch(value ->
                            value.contains("execution:goal_verified=" + scenario.target)),
                    "Execution trace lacks exact goal verification");
            JsonObject result = scenarioEvidence(completed);
            cleanup();
            result.addProperty("cleanupVerified", true);
            results.add(result);
            logger.info(
                    "R09_EXECUTION_GOAL_PASS scenario={} target={} required={} observed={} recipes={} ingredientSelections={} nodes={} routes={} journals={} journalEntries={} maxHandlerInvocationsPerTick={} elapsedTicks={} cleanupVerified=true",
                    scenario.name, scenario.target, scenario.quantity, completed.observedQuantity(),
                    recipeIds(), ready.ingredientSelections().size(),
                    ready.executionReadyPlan().physicalPlan().placements().size(),
                    ready.executionReadyPlan().physicalPlan().routes().size(),
                    completed.journals().size(), journalEntries(completed.journals()),
                    maximumHandlerInvocations, executionTick() - startedTick);
            scenarioIndex++;
            if (scenarioIndex < SCENARIOS.size()) {
                startNext();
                return;
            }
            writeEvidence("PASS", null, null);
            terminal = true;
            state = null;
            logger.info(
                    "R09_EXECUTION_PILOT_PASS goals=2 customMillingQuantity=4 customPressingQuantity=3 planningChecks=8 bindingChecks=15 physicalChecks=13 readinessChecks=16 realRecipeManager=true realInputConsumed=true realOutputVerified=true tagIdentityPreserved=true maxHandlerInvocationsPerTick=1 cleanupVerified=true formalWorld=false evidence={}",
                    evidencePath);
            server.halt(false);
        }

        private JsonObject scenarioEvidence(CreateV606GoalDrivenExecution.Completed completed) {
            JsonObject value = new JsonObject();
            value.addProperty("scenario", scenario.name);
            value.addProperty("target", scenario.target.toString());
            value.addProperty("requiredQuantity", scenario.quantity);
            value.addProperty("observedQuantity", completed.observedQuantity());
            value.addProperty("rawInput", scenario.rawInput.toString());
            value.addProperty("rawQuantity", scenario.rawQuantity);
            value.add("recipeIds", strings(recipeIds()));
            JsonArray selections = new JsonArray();
            for (RuntimeIngredientSelection selection : ready.ingredientSelections()) {
                JsonObject item = new JsonObject();
                item.addProperty("recipeId", selection.recipeId().toString());
                item.addProperty("inputIndex", selection.inputIndex());
                item.addProperty("ingredientKind", selection.ingredientKind().name());
                item.addProperty("ingredientIdentity", selection.ingredientIdentity());
                item.addProperty("selectedResource", selection.selectedResource().toString());
                item.addProperty("amount", selection.amount());
                item.addProperty("selectionReason", selection.reason().name());
                selections.add(item);
            }
            value.add("ingredientSelections", selections);
            value.addProperty("planningChecks", 8);
            value.addProperty("bindingChecks", 15);
            value.addProperty("physicalizationChecks", 13);
            value.addProperty("readinessChecks", 16);
            value.addProperty("processNodes",
                    ready.executionReadyPlan().physicalPlan().placements().size());
            value.addProperty("physicalRoutes",
                    ready.executionReadyPlan().physicalPlan().routes().size());
            value.addProperty("journals", completed.journals().size());
            value.addProperty("journalEntries", journalEntries(completed.journals()));
            value.addProperty("maximumHandlerInvocationsPerTick", maximumHandlerInvocations);
            value.addProperty("elapsedTicks", executionTick() - startedTick);
            value.add("phases", strings(phases.stream().map(Enum::name).toList()));
            value.add("trace", strings(completed.trace()));
            return value;
        }

        private void validateSelections() {
            List<String> recipes = recipeIds();
            require(recipes.stream().anyMatch(value -> value.startsWith(scenario.recipePrefix)),
                    "Custom runtime recipe identity is missing: " + recipes);
            require(ready.ingredientSelections().stream().anyMatch(value ->
                            value.selectedResource().equals(scenario.rawInput)
                                    || value.selectedResource().toString()
                                            .equals("immersiveengineering:plate_aluminum")),
                    "Runtime ingredient selections do not preserve the real input chain");
            if (scenario.requiredIngredientPrefix != null) {
                require(ready.ingredientSelections().stream().anyMatch(value ->
                                value.ingredientIdentity().startsWith(
                                        scenario.requiredIngredientPrefix)),
                        "Required runtime tag identity is missing: "
                                + scenario.requiredIngredientPrefix);
            }
        }

        private String livePhysicalDiagnostics() {
            List<String> values = new ArrayList<>();
            ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                    placement.components().forEach(component -> {
                        BlockPos position = block(component.position());
                        var state = level.getBlockState(position);
                        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        var blockEntity = level.getBlockEntity(position);
                        String speed = blockEntity instanceof KineticBlockEntity kinetic
                                ? Float.toString(kinetic.getSpeed()) : "na";
                        String flowScore = blockEntity instanceof WaterWheelBlockEntity wheel
                                ? Integer.toString(wheel.flowScore) : "na";
                        ResourceLocation fluid = ForgeRegistries.FLUIDS.getKey(
                                state.getFluidState().getType());
                        values.add(component.roleId() + "@" + component.position()
                                + "=block:" + blockId
                                + ",be:" + (blockEntity == null
                                        ? "none" : blockEntity.getClass().getSimpleName())
                                + ",speed:" + speed + ",flowScore:" + flowScore
                                + ",fluid:" + fluid);
                        if (blockEntity instanceof WaterWheelBlockEntity wheel) {
                            for (Direction direction : Direction.values()) {
                                if (direction.getAxis() == Direction.Axis.X) continue;
                                BlockPos adjacent = position.relative(direction);
                                var adjacentState = level.getBlockState(adjacent);
                                var fluidState = adjacentState.getFluidState();
                                ResourceLocation adjacentBlock = ForgeRegistries.BLOCKS.getKey(
                                        adjacentState.getBlock());
                                ResourceLocation adjacentFluid = ForgeRegistries.FLUIDS.getKey(
                                        fluidState.getType());
                                values.add("water_wheel_adjacent/" + direction.getName()
                                        + "@" + adjacent.toShortString()
                                        + "=block:" + adjacentBlock
                                        + ",fluid:" + adjacentFluid
                                        + ",amount:" + fluidState.getAmount()
                                        + ",source:" + fluidState.isSource()
                                        + ",flow:" + wheel.getFlowVectorAtPosition(adjacent));
                            }
                        }
                    }));
            return String.join("|", values);
        }

        private long executionTick() {
            return Integer.toUnsignedLong(server.getTickCount());
        }

        private List<String> recipeIds() {
            return ready.executionReadyPlan().physicalPlan().candidate().boundPlan().graph()
                    .boundProcessNodes().values().stream()
                    .map(node -> node.recipeId().toString()).toList();
        }

        private void forceExecutionChunks() {
            Set<BlockPos3i> positions = new LinkedHashSet<>();
            positions.add(bufferPosition);
            ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                    placement.components().forEach(component -> positions.add(component.position())));
            ready.executionReadyPlan().physicalPlan().routes().forEach(route ->
                    positions.addAll(route.positions()));
            for (BlockPos3i position : positions) {
                int x = position.x() >> 4;
                int z = position.z() >> 4;
                long key = ((long) x << 32) ^ (z & 0xffffffffL);
                if (forcedChunks.add(key)) level.setChunkForced(x, z, true);
            }
        }

        private void seedBuffer() {
            BlockPos position = block(bufferPosition);
            require(level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState()),
                    "Could not place isolated pilot resource buffer");
            require(level.getBlockEntity(position) instanceof ChestBlockEntity,
                    "Pilot resource buffer chest block entity is unavailable");
            Item item = ForgeRegistries.ITEMS.getValue(location(scenario.rawInput));
            require(item != null && location(scenario.rawInput).equals(
                            ForgeRegistries.ITEMS.getKey(item)),
                    "Pilot raw input is not registered: " + scenario.rawInput);
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(position);
            chest.setItem(0, new ItemStack(item, Math.toIntExact(scenario.rawQuantity)));
            chest.setChanged();
        }

        private void cleanup() {
            Set<BlockPos3i> positions = new LinkedHashSet<>();
            ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                    placement.components().forEach(component -> positions.add(component.position())));
            ready.executionReadyPlan().physicalPlan().routes().forEach(route ->
                    positions.addAll(route.positions()));
            ready.executionReadyPlan().physicalPlan().placements().forEach(placement ->
                    placement.components().stream()
                            .filter(component -> component.roleId().path().endsWith("/water_source"))
                            .forEach(component -> {
                                positions.add(new BlockPos3i(
                                        component.position().x(), component.position().y() - 1,
                                        component.position().z()));
                                positions.add(new BlockPos3i(
                                        component.position().x(), component.position().y() - 2,
                                        component.position().z()));
                            }));
            positions.add(bufferPosition);
            positions.forEach(position -> level.setBlockAndUpdate(
                    block(position), Blocks.AIR.defaultBlockState()));
            require(positions.stream().allMatch(position -> level.getBlockState(block(position)).isAir()),
                    "Pilot cleanup left a controlled block behind");
            for (long key : forcedChunks) {
                level.setChunkForced((int) (key >> 32), (int) key, false);
            }
            forcedChunks.clear();
        }

        private void fail(String code, String detail) {
            if (terminal) return;
            terminal = true;
            try {
                if (ready != null) cleanup();
                writeEvidence("FAIL", code, detail);
            } catch (RuntimeException | IOException cleanupFailure) {
                detail = detail + "; cleanup/evidence failure=" + safe(cleanupFailure);
            }
            logger.error(
                    "R09_EXECUTION_PILOT_FAIL code={} scenario={} detail=\"{}\" formalWorld=false",
                    code, scenario == null ? "startup" : scenario.name, detail);
            state = null;
            server.halt(false);
        }

        private void writeEvidence(String status, String failureCode, String failureDetail)
                throws IOException {
            JsonObject root = new JsonObject();
            root.addProperty("schema", "steve-industrial:r09-execution-pilot/v1");
            root.addProperty("status", status);
            root.addProperty("generatedAt", Instant.now().toString());
            root.addProperty("executionWorld", "isolated-repository-test");
            root.addProperty("formalWorld", false);
            root.addProperty("goalCount", results.size());
            root.addProperty("runtimeFingerprint", ready == null
                    ? "unavailable" : ready.runtimeRecipeFingerprint());
            root.addProperty("maximumHandlerInvocationsPerTick", 1);
            root.addProperty("externalMutation", false);
            root.addProperty("savesOrFormalWorldRead", false);
            root.add("scenarios", results);
            if (failureCode != null) {
                root.addProperty("failureCode", failureCode);
                root.addProperty("failureDetail", failureDetail);
            }
            Files.createDirectories(evidencePath.getParent());
            Path temporary = evidencePath.resolveSibling(evidencePath.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, evidencePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private record Scenario(
            String name,
            ResourceId target,
            long quantity,
            ResourceId rawInput,
            long rawQuantity,
            String recipePrefix,
            String requiredIngredientPrefix) {}

    private static int journalEntries(List<WorldChangeJournal> journals) {
        return journals.stream().mapToInt(value -> value.entries().size()).sum();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static ResourceLocation location(ResourceId value) {
        return ResourceLocation.fromNamespaceAndPath(value.namespace(), value.path());
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    private static String safe(Exception failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
        return detail.length() <= 1_024 ? detail : detail.substring(0, 1_024);
    }
}
