package dev.stevecreate.agent.core.electrical;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ElectricalNetworkVerifierTest {
    @Test
    void acceptsGeneratorRelayTransformerStorageAndConsumerTopology() {
        ElectricalNetworkGraph graph = graph(false, false, false);
        var report = new ElectricalNetworkVerifier().verify(graph);
        assertThat(report.accepted()).isTrue();
        assertThat(report.generationPerTick()).isEqualTo(128);
        assertThat(report.loadPerTick()).isEqualTo(64);
        assertThat(report.failures()).isEmpty();
        assertThat(graph.fingerprint()).hasSize(64);
    }

    @Test
    void reportsVoltageDistanceCollisionAndDisconnectIndependently() {
        var report = new ElectricalNetworkVerifier().verify(graph(true, true, true));
        assertThat(report.accepted()).isFalse();
        assertThat(report.failures()).contains(
                ElectricalNetworkFailure.EDGE_TOO_LONG,
                ElectricalNetworkFailure.WIRE_COLLISION,
                ElectricalNetworkFailure.DISCONNECTED,
                ElectricalNetworkFailure.CONSUMER_UNREACHABLE);
    }

    private static ElectricalNetworkGraph graph(
            boolean tooLong, boolean collision, boolean disconnected) {
        ElectricalNetworkNode generator = node("generator", ElectricalNodeKind.GENERATOR,
                128, 0, 0, 0, Set.of(), Set.of(Direction6.EAST), VoltageTier.LV, Optional.empty());
        ElectricalNetworkNode relay = node("relay", ElectricalNodeKind.RELAY,
                0, 0, 0, 0, Set.of(Direction6.WEST), Set.of(Direction6.EAST),
                VoltageTier.LV, Optional.empty());
        ElectricalNetworkNode transformer = node("transformer", ElectricalNodeKind.TRANSFORMER,
                0, 0, 0, 0, Set.of(Direction6.WEST), Set.of(Direction6.EAST),
                VoltageTier.LV, Optional.of(VoltageTier.MV));
        ElectricalNetworkNode storage = node("storage", ElectricalNodeKind.STORAGE,
                0, 0, 64, 256, Set.of(Direction6.WEST), Set.of(Direction6.EAST),
                VoltageTier.MV, Optional.empty());
        ElectricalNetworkNode consumer = node("consumer", ElectricalNodeKind.CONSUMER,
                0, 64, 0, 0, Set.of(Direction6.WEST), Set.of(), VoltageTier.MV, Optional.empty());
        return new ElectricalNetworkGraph(id("electrical:test"), "world",
                id("minecraft:overworld"),
                List.of(generator, relay, transformer, storage, consumer),
                List.of(
                        edge("g_r", generator, relay, VoltageTier.LV, 8, 16, false, false),
                        edge("r_t", relay, transformer, VoltageTier.LV,
                                tooLong ? 17 : 8, 16, collision, false),
                        edge("t_s", transformer, storage, VoltageTier.MV, 8, 16, false, false),
                        edge("s_c", storage, consumer, VoltageTier.MV, 8, 16, false, disconnected)), 0);
    }

    private static ElectricalNetworkNode node(
            String id, ElectricalNodeKind kind, long generation, long load,
            long stored, long capacity, Set<Direction6> inputs, Set<Direction6> outputs,
            VoltageTier primary, Optional<VoltageTier> secondary) {
        int x = switch (id) { case "generator" -> 0; case "relay" -> 1;
            case "transformer" -> 2; case "storage" -> 3; default -> 4; };
        return new ElectricalNetworkNode(ElectricalNetworkVerifierTest.id("node:" + id), kind,
                new BlockPos3i(x, 0, 0), primary, secondary, inputs, outputs,
                generation, load, stored, capacity, "a".repeat(64), true);
    }

    private static ElectricalWireEdge edge(
            String id, ElectricalNetworkNode from, ElectricalNetworkNode to, VoltageTier tier,
            int distance, int max, boolean collision, boolean disconnected) {
        return new ElectricalWireEdge(ElectricalNetworkVerifierTest.id("edge:" + id),
                from.nodeId(), to.nodeId(), tier, distance, max, 128,
                List.of(from.position(), to.position()), "b".repeat(64),
                !disconnected, !collision, true);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
