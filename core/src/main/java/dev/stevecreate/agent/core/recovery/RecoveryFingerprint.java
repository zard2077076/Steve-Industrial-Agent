package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionStep;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.graph.MachineEdge;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachinePort;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Canonical SHA-256 over trusted graph and plan descriptors; it never executes them. */
final class RecoveryFingerprint {
    private RecoveryFingerprint() {
    }

    static String graph(UnifiedMachineGraph graph) {
        Hasher hasher = new Hasher("steve-industrial-graph-v1");
        writeGraph(hasher, graph);
        return hasher.finish();
    }

    static String plan(GenericExecutionPlan plan) {
        Hasher hasher = new Hasher("steve-industrial-plan-v1");
        hasher.id(plan.planId());
        writeGraph(hasher, plan.machineGraph());
        writeProcess(hasher, plan.processSpec());
        hasher.integer(plan.steps().size());
        for (GenericExecutionStep step : plan.steps()) {
            writeStep(hasher, step);
        }
        return hasher.finish();
    }

    private static void writeGraph(Hasher hasher, UnifiedMachineGraph graph) {
        hasher.id(graph.id());
        List<MachineNode> nodes = graph.nodes().values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
        hasher.integer(nodes.size());
        for (MachineNode node : nodes) {
            hasher.id(node.id());
            hasher.id(node.roleId());
            hasher.id(node.implementationId());
            hasher.position(node.relativePosition());
            hasher.optionalEnum(node.orientation().facing());
            hasher.optionalEnum(node.orientation().axis());
            hasher.ids(node.requiredCapabilities());
            hasher.textMap(node.configuration());
        }

        List<MachinePort> ports = graph.ports().values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
        hasher.integer(ports.size());
        for (MachinePort port : ports) {
            hasher.id(port.id());
            hasher.id(port.nodeId());
            hasher.text(port.resourceType().serializedName());
            hasher.text(port.mode().name());
            hasher.optionalEnum(port.side());
            hasher.optionalLong(port.capacity());
            hasher.textMap(port.constraints());
        }

        List<MachineEdge> edges = graph.edges().values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
        hasher.integer(edges.size());
        for (MachineEdge edge : edges) {
            hasher.id(edge.id());
            hasher.id(edge.sourcePortId());
            hasher.id(edge.targetPortId());
            hasher.text(edge.resourceType().serializedName());
            hasher.text(edge.mode().name());
            hasher.optionalLong(edge.maximumThroughput());
            hasher.textMap(edge.connectionRequirements());
        }
    }

    private static void writeProcess(Hasher hasher, GenericProcessSpec process) {
        hasher.id(process.recipeId());
        hasher.id(process.recipeType());
        writeResources(hasher, process.inputs());
        writeResources(hasher, process.outputs());
        writeResources(hasher, process.optionalByproducts());
        hasher.ids(process.requiredMachineCapabilities());
        hasher.ids(process.requiredCompletionEvidence());
        hasher.integer(process.maximumWaitTicks());
        hasher.text(process.inputConsumptionRequirement().name());
        hasher.text(process.outputVerificationRequirement().name());
        hasher.idTextMap(process.extensionData());
    }

    private static void writeResources(Hasher hasher, List<ProcessResource> resources) {
        hasher.integer(resources.size());
        for (ProcessResource resource : resources) {
            hasher.id(resource.resourceId());
            hasher.text(resource.resourceType().serializedName());
            hasher.longValue(resource.amount());
        }
    }

    private static void writeStep(Hasher hasher, GenericExecutionStep step) {
        hasher.id(step.stepId());
        hasher.text(step.phase().serializedName());
        writeConditions(hasher, step.preconditions());
        writeAction(hasher, step.action());
        writeConditions(hasher, step.successConditions());
        writeConditions(hasher, step.failureConditions());
        hasher.integer(step.timeoutTicks());
        hasher.integer(step.retryPolicy().maximumAttempts());
        hasher.integer(step.retryPolicy().backoffTicks());
        hasher.ids(step.retryPolicy().retryableFailures());
        hasher.bool(step.cancellable());
        hasher.bool(step.rollbackAction().isPresent());
        step.rollbackAction().ifPresent(value -> writeAction(hasher, value));
        hasher.ids(step.requiredEvidence());
    }

    private static void writeConditions(Hasher hasher, List<StepCondition> conditions) {
        hasher.integer(conditions.size());
        for (StepCondition condition : conditions) {
            hasher.id(condition.conditionId());
            hasher.id(condition.evaluatorId());
            hasher.id(condition.conditionType());
            hasher.idTextMap(condition.parameters());
        }
    }

    private static void writeAction(Hasher hasher, StepActionDescriptor action) {
        hasher.id(action.handlerId());
        hasher.id(action.operationId());
        hasher.idTextMap(action.parameters());
    }

    private static final class Hasher {
        private final MessageDigest digest;

        private Hasher(String domain) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
            text(domain);
        }

        private void bool(boolean value) {
            digest.update((byte) (value ? 1 : 0));
        }

        private void integer(int value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }

        private void longValue(long value) {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        }

        private void text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            integer(bytes.length);
            digest.update(bytes);
        }

        private void id(ResourceId value) {
            text(value.toString());
        }

        private void position(BlockPos3i value) {
            integer(value.x());
            integer(value.y());
            integer(value.z());
        }

        private void ids(Set<ResourceId> values) {
            List<ResourceId> sorted = values.stream()
                    .sorted(Comparator.comparing(ResourceId::toString))
                    .toList();
            integer(sorted.size());
            sorted.forEach(this::id);
        }

        private void textMap(Map<String, String> values) {
            List<Map.Entry<String, String>> sorted = values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            integer(sorted.size());
            for (Map.Entry<String, String> entry : sorted) {
                text(entry.getKey());
                text(entry.getValue());
            }
        }

        private void idTextMap(Map<ResourceId, String> values) {
            List<Map.Entry<ResourceId, String>> sorted = values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                    .toList();
            integer(sorted.size());
            for (Map.Entry<ResourceId, String> entry : sorted) {
                id(entry.getKey());
                text(entry.getValue());
            }
        }

        private void optionalLong(OptionalLong value) {
            bool(value.isPresent());
            if (value.isPresent()) {
                longValue(value.getAsLong());
            }
        }

        private void optionalEnum(Optional<? extends Enum<?>> value) {
            bool(value.isPresent());
            value.ifPresent(item -> text(item.name()));
        }

        private String finish() {
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
