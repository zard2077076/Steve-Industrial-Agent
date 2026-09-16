package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HybridExecutorTest {
    private static final ResourceId HYBRID = id("executor:hybrid");
    private static final ResourceId DIRECT = id("executor:direct");
    private static final ResourceId BOTS = id("executor:bots");
    private static final ResourceId WORKER = id("worker:hybrid_one");
    private static final Set<ResourceId> ROUTER_CAPABILITIES = Set.of(id("executor:mode_router"));

    @Test
    void safeDefaultsRouteMaterialToBotsAndSensitivePlacementAndVerificationToDirect() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);
        HybridExecutor hybrid = hybrid(direct, bots, HybridRoutingPolicy.safeDefaults());

        TaskExecutionResult fetched = hybrid.execute(
                graph, graph.task(id("task:fetch")),
                assignment(graph, graph.task(id("task:fetch")), true, 1), context(1));
        TaskExecutionResult placed = hybrid.execute(
                graph, graph.task(id("task:place")),
                assignment(graph, graph.task(id("task:place")), false, 2), context(2));
        TaskExecutionResult verified = hybrid.execute(
                graph, graph.task(id("task:verify")),
                assignment(graph, graph.task(id("task:verify")), false, 3), context(3));

        assertThat(fetched.outcome()).isEqualTo(TaskExecutionOutcome.SUCCEEDED);
        assertThat(fetched.mode()).isEqualTo(ExecutionMode.HYBRID);
        assertThat(fetched.executorId()).isEqualTo(HYBRID);
        assertThat(fetched.evidence()).allSatisfy(value -> {
            assertThat(value.belongsTo(assignment(
                    graph, graph.task(id("task:fetch")), true, 1))).isTrue();
            assertThat(value.provenance()).startsWith("hybrid-route=bots;");
        });
        assertThat(placed.evidence()).allSatisfy(value ->
                assertThat(value.provenance()).startsWith("hybrid-route=direct;"));
        assertThat(verified.evidence()).allSatisfy(value ->
                assertThat(value.provenance()).startsWith("hybrid-route=direct;"));
        assertThat(bots.executeCalls).hasValue(1);
        assertThat(direct.executeCalls).hasValue(2);
    }

    @Test
    void highRiskIsRefusedBeforeEitherBackendAndCannotBeConfiguredAway() {
        ConstructionTaskGraph graph = graph(
                highRiskTask(), descriptor(CapabilitySupport.SUPPORTED,
                        CapabilitySupport.SUPPORTED, CapabilitySupport.SUPPORTED));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);
        HybridExecutor hybrid = hybrid(direct, bots, HybridRoutingPolicy.safeDefaults());
        ConstructionTask task = graph.task(id("task:high_risk"));

        TaskExecutionResult result = hybrid.execute(
                graph, task, assignment(graph, task, false, 1), context(1));

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.TERMINAL_FAILURE);
        assertThat(result.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED.id());
        assertThat(direct.executeCalls).hasValue(0);
        assertThat(bots.executeCalls).hasValue(0);

        EnumMap<ConstructionTaskClass, HybridTaskRoute> unsafe =
                new EnumMap<>(HybridRoutingPolicy.safeDefaults().routes());
        unsafe.put(ConstructionTaskClass.HIGH_RISK_INTERACTION, HybridTaskRoute.BOTS);
        assertThatThrownBy(() -> new HybridRoutingPolicy(unsafe, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permanently refused");
    }

    @Test
    void invokedBotRefusalNeverSwitchesTheAssignmentToDirect() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        ConstructionTask task = graph.task(id("task:fetch"));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.UNSUPPORTED);
        HybridExecutor hybrid = hybrid(direct, bots, HybridRoutingPolicy.safeDefaults());

        TaskExecutionResult result = hybrid.execute(
                graph, task, assignment(graph, task, true, 4), context(4));

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.UNSUPPORTED);
        assertThat(result.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED.id());
        assertThat(bots.executeCalls).hasValue(1);
        assertThat(direct.executeCalls).hasValue(0);
    }

    @Test
    void botRefusalStaysHonestWhenPolicyDoesNotPermitFallback() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        ConstructionTask task = graph.task(id("task:fetch"));
        EnumMap<ConstructionTaskClass, HybridTaskRoute> routes =
                new EnumMap<>(HybridRoutingPolicy.safeDefaults().routes());
        HybridRoutingPolicy noFallback = new HybridRoutingPolicy(routes, Set.of());
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);

        TaskExecutionResult result = hybrid(direct, bots, noFallback).execute(
                graph, task, assignment(graph, task, false, 5), context(5));

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.UNSUPPORTED);
        assertThat(result.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED.id());
        assertThat(bots.executeCalls).hasValue(0);
        assertThat(direct.executeCalls).hasValue(0);
    }

    @Test
    void semanticsOnlyBotDeclarationUsesDirectFallbackWithoutCallingBot() {
        ConstructionTask task = materialTask(EnumSet.of(ExecutionMode.DIRECT, ExecutionMode.HYBRID));
        ConstructionTaskGraph graph = graph(
                task, descriptor(CapabilitySupport.SUPPORTED,
                        CapabilitySupport.UNSUPPORTED, CapabilitySupport.SUPPORTED));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);

        TaskExecutionResult result = hybrid(
                direct, bots, HybridRoutingPolicy.safeDefaults()).execute(
                graph, task, assignment(graph, task, false, 6), context(6));

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.SUCCEEDED);
        assertThat(result.evidence()).allSatisfy(value ->
                assertThat(value.provenance()).startsWith("hybrid-route=direct;"));
        assertThat(bots.executeCalls).hasValue(0);
        assertThat(direct.executeCalls).hasValue(1);
    }

    @Test
    void pendingBotMutationNeverFallsThroughToDirect() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        ConstructionTask task = graph.task(id("task:fetch"));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.PENDING_MUTATION);

        TaskExecutionResult result = hybrid(
                direct, bots, HybridRoutingPolicy.safeDefaults()).execute(
                graph, task, assignment(graph, task, true, 7), context(7));

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.PENDING);
        assertThat(result.materialMutationCount()).isEqualTo(1);
        assertThat(bots.executeCalls).hasValue(1);
        assertThat(direct.executeCalls).hasValue(0);
    }

    @Test
    void priorHybridEvidenceIsReboundToTheSameStableBackendAssignment() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        ConstructionTask task = graph.task(id("task:fetch"));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(
                BOTS, ExecutionMode.BOTS, Behavior.PENDING_EVIDENCE, Behavior.SUCCESS);
        HybridExecutor hybrid = hybrid(direct, bots, HybridRoutingPolicy.safeDefaults());
        TaskAssignment assignment = assignment(graph, task, true, 8);

        TaskExecutionResult first = hybrid.execute(graph, task, assignment, context(8));
        TaskExecutionResult second = hybrid.execute(
                graph, task, assignment,
                new ConstructionExecutionContext(
                        ConstructionExecutionCommand.CONTINUE, 9, first.evidence(),
                        Optional.empty(), Optional.empty()));

        assertThat(first.outcome()).isEqualTo(TaskExecutionOutcome.PENDING);
        assertThat(second.outcome()).isEqualTo(TaskExecutionOutcome.SUCCEEDED);
        assertThat(bots.lastPriorEvidenceCount).isEqualTo(1);
        assertThat(second.evidence()).allSatisfy(value -> {
            assertThat(value.belongsTo(assignment)).isTrue();
            assertThat(value.provenance()).startsWith("hybrid-route=bots;");
            assertThat(value.provenance().split("hybrid-route=bots;", -1)).hasSize(2);
        });
    }

    @Test
    void constructorRejectsDelegateModeOrIdentityConfusion() {
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);
        assertThatThrownBy(() -> new HybridExecutor(
                HYBRID, ROUTER_CAPABILITIES, bots, direct, HybridRoutingPolicy.safeDefaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directExecutor must use mode DIRECT");
        assertThatThrownBy(() -> new HybridExecutor(
                DIRECT, ROUTER_CAPABILITIES, direct, bots, HybridRoutingPolicy.safeDefaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identities must be distinct");
    }

    @Test
    void recoveryRefusalStopsBeforeEitherBackend() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        ConstructionTask task = graph.task(id("task:fetch"));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);
        TaskAssignment assignment = assignment(graph, task, true, 10);
        ExecutionEvidence prior = evidence(
                assignment, task.postconditions().get(0), 10, "fixture:pre-reload");

        TaskExecutionResult result = hybrid(
                direct, bots, HybridRoutingPolicy.safeDefaults()).execute(
                graph, task, assignment,
                new ConstructionExecutionContext(
                        ConstructionExecutionCommand.RECOVER, 11, List.of(prior),
                        Optional.empty(), Optional.empty()));

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.RECOVERY_REQUIRED);
        assertThat(result.failure().orElseThrow().code()).isEqualTo(
                ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION.id());
        assertThat(bots.executeCalls).hasValue(0);
        assertThat(direct.executeCalls).hasValue(0);
    }

    @Test
    void cancellationCannotBeReportedAsSuccessByADelegate() {
        ConstructionTaskGraph graph = ConstructionContractFixtures.simpleGraph();
        ConstructionTask task = graph.task(id("task:fetch"));
        FixtureExecutor direct = executor(DIRECT, ExecutionMode.DIRECT, Behavior.SUCCESS);
        FixtureExecutor bots = executor(BOTS, ExecutionMode.BOTS, Behavior.SUCCESS);
        TaskAssignment assignment = assignment(graph, task, true, 12);

        assertThatThrownBy(() -> hybrid(
                direct, bots, HybridRoutingPolicy.safeDefaults()).execute(
                graph, task, assignment,
                new ConstructionExecutionContext(
                        ConstructionExecutionCommand.CANCEL, 12, List.of(),
                        Optional.empty(), Optional.of(id("reason:test")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must return CANCELLED");
        assertThat(bots.executeCalls).hasValue(1);
        assertThat(direct.executeCalls).hasValue(0);
    }

    private static HybridExecutor hybrid(
            FixtureExecutor direct,
            FixtureExecutor bots,
            HybridRoutingPolicy policy) {
        return new HybridExecutor(HYBRID, ROUTER_CAPABILITIES, direct, bots, policy);
    }

    private static FixtureExecutor executor(
            ResourceId id,
            ExecutionMode mode,
            Behavior... behaviors) {
        return new FixtureExecutor(id, mode, behaviors);
    }

    private static TaskAssignment assignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            boolean worker,
            long tick) {
        TaskOwnership ownership = new TaskOwnership(
                id("session:hybrid"), graph.graphId(), task.taskId(), HYBRID,
                ExecutionMode.HYBRID, worker ? Optional.of(WORKER) : Optional.empty(),
                1, tick, tick + 100, "d".repeat(64));
        return TaskAssignment.assign(
                graph, task.taskId(), id("assignment:" + task.taskId().path()),
                ownership, 1, tick);
    }

    private static ConstructionExecutionContext context(long tick) {
        return new ConstructionExecutionContext(
                ConstructionExecutionCommand.START, tick, List.of(),
                Optional.empty(), Optional.empty());
    }

    private static ConstructionTask highRiskTask() {
        return new ConstructionTask(
                id("task:high_risk"),
                new VerifiedPlanTaskSource(
                        ConstructionContractFixtures.PLAN_ID,
                        TaskSourceKind.VERIFIED_PORT,
                        id("physical:high_risk"), 0),
                id("capability:hybrid_test"),
                TaskKind.SAFE_MACHINE_INTERACTION,
                ConstructionTaskClass.HIGH_RISK_INTERACTION,
                EnumSet.allOf(ExecutionMode.class),
                List.of(),
                List.of(post("condition:high_risk")),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                new CleanupPolicy(CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                        false, true, 1),
                Set.of(id("placement:high_risk")), Set.of(), Set.of(),
                100, Map.of());
    }

    private static ConstructionTask materialTask(Set<ExecutionMode> modes) {
        return new ConstructionTask(
                id("task:material"),
                new VerifiedPlanTaskSource(
                        ConstructionContractFixtures.PLAN_ID,
                        TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                        id("physical:material"), 0),
                id("capability:hybrid_test"),
                TaskKind.FETCH_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                modes,
                List.of(),
                List.of(post("condition:material")),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                CleanupPolicy.NONE,
                Set.of(), Set.of(id("material:one")), Set.of(),
                100, Map.of());
    }

    private static TaskPostcondition post(String conditionId) {
        return new TaskPostcondition(
                id(conditionId), TaskConditionKind.CURRENT_STATE_MATCHES,
                id("subject:" + conditionId.substring(conditionId.indexOf(':') + 1)),
                ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                Map.of(id("value:state"), "verified"));
    }

    private static ExecutionEvidence evidence(
            TaskAssignment assignment,
            TaskPostcondition postcondition,
            long tick,
            String provenance) {
        return new ExecutionEvidence(
                id("evidence:" + postcondition.conditionId().path()),
                postcondition.conditionId(), assignment.sessionId(), assignment.graphId(),
                assignment.taskId(), assignment.assignmentId(), assignment.executorId(),
                assignment.mode(), postcondition.requiredEvidenceKind(), postcondition.subjectId(),
                postcondition.expectedValues(), postcondition.expectedValues(), tick, true, provenance);
    }

    private static CapabilityExecutionDescriptor descriptor(
            CapabilitySupport direct,
            CapabilitySupport bots,
            CapabilitySupport hybrid) {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, declaration(
                ExecutionMode.DIRECT, direct, Set.of(id("executor:direct_cap"))));
        modes.put(ExecutionMode.BOTS, declaration(
                ExecutionMode.BOTS, bots, Set.of(id("executor:bot_cap"))));
        modes.put(ExecutionMode.HYBRID,
                declaration(ExecutionMode.HYBRID, hybrid, ROUTER_CAPABILITIES));
        return new CapabilityExecutionDescriptor(
                id("capability:hybrid_test"), id("implementation:hybrid_test"), id("adapter:test"),
                ConstructionContractFixtures.RUNTIME, EnumSet.allOf(TaskKind.class), modes, Map.of());
    }

    private static ModeCapabilityDeclaration declaration(
            ExecutionMode mode,
            CapabilitySupport support,
            Set<ResourceId> capabilities) {
        Set<ResourceId> required = support == CapabilitySupport.SUPPORTED ? capabilities : Set.of();
        return new ModeCapabilityDeclaration(
                mode, support, required,
                support == CapabilitySupport.SUPPORTED ? "" : "fixture unsupported");
    }

    private static ConstructionTaskGraph graph(
            ConstructionTask task,
            CapabilityExecutionDescriptor descriptor) {
        return new ConstructionTaskGraph(
                id("graph:" + task.taskId().path()),
                ConstructionContractFixtures.PLAN_ID,
                ConstructionContractFixtures.RUNTIME,
                ConstructionContractFixtures.SNAPSHOT,
                List.of(descriptor), List.of(task), List.of());
    }

    private enum Behavior { SUCCESS, UNSUPPORTED, PENDING_MUTATION, PENDING_EVIDENCE }

    private static final class FixtureExecutor implements ConstructionExecutor {
        private final ResourceId executorId;
        private final ExecutionMode mode;
        private final ArrayDeque<Behavior> behaviors;
        private final AtomicInteger executeCalls = new AtomicInteger();
        private int lastPriorEvidenceCount;

        private FixtureExecutor(ResourceId executorId, ExecutionMode mode, Behavior... behaviors) {
            this.executorId = executorId;
            this.mode = mode;
            this.behaviors = new ArrayDeque<>(List.of(behaviors));
        }

        @Override
        public ResourceId executorId() {
            return executorId;
        }

        @Override
        public ExecutionMode mode() {
            return mode;
        }

        @Override
        public TaskExecutionResult execute(
                ConstructionTaskGraph graph,
                ConstructionTask task,
                TaskAssignment assignment,
                ConstructionExecutionContext context) {
            executeCalls.incrementAndGet();
            lastPriorEvidenceCount = context.priorEvidence().size();
            assertThat(context.priorEvidence()).allSatisfy(value ->
                    assertThat(value.belongsTo(assignment)).isTrue());
            Behavior behavior = behaviors.size() > 1 ? behaviors.removeFirst() : behaviors.getFirst();
            if (behavior == Behavior.UNSUPPORTED) {
                TaskFailure failure = new TaskFailure(
                        ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED.id(),
                        TaskFailureCategory.UNSUPPORTED_CAPABILITY, false,
                        Optional.empty(), Optional.of(task.taskId()), "fixture Bot unsupported",
                        List.of(assignment.assignmentId()), "use declared Direct fallback");
                return TaskExecutionResult.unsupported(
                        task, assignment, context.currentTick(), failure, "fixture unsupported");
            }
            List<ExecutionEvidence> evidence = behavior == Behavior.PENDING_MUTATION
                    ? List.of()
                    : task.postconditions().stream()
                    .map(postcondition -> new ExecutionEvidence(
                            id("evidence:" + postcondition.conditionId().path()),
                            postcondition.conditionId(), assignment.sessionId(), assignment.graphId(),
                            assignment.taskId(), assignment.assignmentId(), assignment.executorId(),
                            assignment.mode(), postcondition.requiredEvidenceKind(),
                            postcondition.subjectId(), postcondition.expectedValues(),
                            postcondition.expectedValues(), context.currentTick(), true,
                            "fixture:" + mode.serializedName()))
                    .toList();
            if (behavior == Behavior.PENDING_MUTATION) {
                return TaskExecutionResult.pending(
                        task, assignment, context.currentTick(), evidence, 0, 1,
                        "fixture mutation pending");
            }
            if (behavior == Behavior.PENDING_EVIDENCE) {
                return TaskExecutionResult.pending(
                        task, assignment, context.currentTick(), evidence, 0, 0,
                        "fixture evidence pending");
            }
            return TaskExecutionResult.success(
                    task, assignment, context.currentTick(), evidence,
                    task.kind().mutatesWorld() ? 1 : 0,
                    task.kind().operatesOnMaterial() ? 1 : 0,
                    "fixture success");
        }
    }
}
