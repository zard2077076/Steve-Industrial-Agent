package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.assignment;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.simpleGraph;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConstructionExecutorContractTest {
    private static final List<Class<?>> CONTRACT_TYPES = List.of(
            ConstructionExecutor.class,
            DirectWorldExecutor.class,
            BotFleetExecutor.class,
            BotWorker.class,
            BotWorkerSnapshot.class,
            BotInventory.class,
            MaterialSource.class,
            MaterialDelivery.class,
            ReservationLedgerSnapshot.class,
            BotFleetCoordinatorSnapshot.class,
            ConstructionExecutionContext.class,
            ConstructionTask.class,
            ConstructionTaskGraph.class,
            CapabilityExecutionDescriptor.class,
            TaskAssignment.class,
            TaskOwnership.class,
            TaskExecutionResult.class,
            ExecutionEvidence.class,
            PlacementReservation.class,
            MaterialReservation.class,
            SharedInfrastructureReservation.class);

    @Test
    void contractSurfaceIsLoaderNeutralAndCarriesNoWorldObject() {
        for (Class<?> type : CONTRACT_TYPES) {
            for (Field field : type.getDeclaredFields()) {
                assertNeutral(field.getType());
            }
            for (Method method : type.getDeclaredMethods()) {
                assertNeutral(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) assertNeutral(parameter);
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) assertNeutral(parameter);
            }
            for (RecordComponent component : type.getRecordComponents() == null
                    ? new RecordComponent[0]
                    : type.getRecordComponents()) {
                assertNeutral(component.getType());
            }
        }
    }

    @Test
    void executorSupportIsBoundToGraphTaskAssignmentModeAndCapability() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment directAssignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        ConstructionExecutor direct = new FixtureExecutor();

        assertThat(direct.supports(graph, task, directAssignment)).isTrue();
        TaskAssignment botAssignment = assignment(graph, task, ExecutionMode.BOTS, 1, 10);
        assertThat(direct.supports(graph, task, botAssignment)).isFalse();
    }

    @Test
    void cancellationAndRecoveryContextCannotBeAmbiguous() {
        assertThatThrownBy(() -> new ConstructionExecutionContext(
                ConstructionExecutionCommand.CANCEL,
                10,
                List.of(),
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANCEL requires");
        assertThatThrownBy(() -> new ConstructionExecutionContext(
                ConstructionExecutionCommand.RECOVER,
                10,
                List.of(),
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation evidence");
    }

    private static void assertNeutral(Class<?> type) {
        assertThat(type.getName())
                .doesNotStartWith("net.minecraft")
                .doesNotStartWith("net.minecraftforge")
                .doesNotStartWith("com.simibubi.create")
                .doesNotStartWith("mekanism");
    }

    private static final class FixtureExecutor implements ConstructionExecutor {
        @Override
        public dev.stevecreate.agent.core.model.ResourceId executorId() {
            return id("executor:direct");
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.DIRECT;
        }

        @Override
        public TaskExecutionResult execute(
                ConstructionTaskGraph graph,
                ConstructionTask task,
                TaskAssignment assignment,
                ConstructionExecutionContext context) {
            throw new UnsupportedOperationException("contract-only fixture");
        }
    }
}
