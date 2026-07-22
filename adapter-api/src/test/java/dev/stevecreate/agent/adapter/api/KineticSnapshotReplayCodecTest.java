package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.diagnostic.FaultCode;
import dev.stevecreate.agent.core.diagnostic.KineticNetworkAnalyzer;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class KineticSnapshotReplayCodecTest {
    private static final String POWERED_GOLDEN =
            "U0lBSwAAAAEAAAAAAAAAewAGMS4yMC4xAAVmb3JnZQAHNDcuNC4xMAAAAAEABmNyZWF0ZQAJNi4wLjYtMTUw"
                    + "ACpzdGV2ZV9pbmR1c3RyaWFsOmZvcmdlXzFfMjBfMV9jcmVhdGVfNl8wXzYAAAACABNtaW5lY3JhZnQ6b3Zl"
                    + "cndvcmxkABVjcmVhdGU6Y3JlYXRpdmVfbW90b3IAAAAEAAAAQP____4BAAAAAAAAAGMAAAACQDAAAAAAAAAB"
                    + "AUAwAAAAAAAAAAAAAAAAAAAA";

    @Test
    void replayEncodingIsCanonicalAndRoundTrips() {
        LinkedHashMap<String, String> firstOrder = new LinkedHashMap<>();
        firstOrder.put("mekanism", "absent");
        firstOrder.put("create", "6.0.6-150");
        LinkedHashMap<String, String> secondOrder = new LinkedHashMap<>();
        secondOrder.put("create", "6.0.6-150");
        secondOrder.put("mekanism", "absent");

        KineticSnapshot first = poweredSnapshot(firstOrder);
        KineticSnapshot second = poweredSnapshot(secondOrder);
        String encoded = KineticSnapshotReplayCodec.encode(first);

        assertThat(encoded).isEqualTo(KineticSnapshotReplayCodec.encode(second));
        assertThat(KineticSnapshotReplayCodec.decode(encoded)).isEqualTo(first);

        String goldenEncoded = KineticSnapshotReplayCodec.encode(
                poweredSnapshot(Map.of("create", "6.0.6-150")));
        assertThat(goldenEncoded).isEqualTo(POWERED_GOLDEN);
        assertThat(KineticSnapshotReplayCodec.encode(KineticSnapshotReplayCodec.decode(goldenEncoded)))
                .isEqualTo(POWERED_GOLDEN);
    }

    @Test
    void replayPreservesStoppedPoweredAndOverstressedStates() {
        RuntimeFingerprint runtime = runtime(Map.of("create", "6.0.6-150"));
        List<KineticSnapshot> snapshots = List.of(
                new KineticSnapshot(
                        100, runtime, ResourceId.parse("minecraft:overworld"), ResourceId.parse("create:shaft"),
                        new BlockPos3i(0, 64, 0), OptionalLong.empty(), 0, 0,
                        KineticRotationDirection.STATIONARY, true, 0, 0, false),
                new KineticSnapshot(
                        101, runtime, ResourceId.parse("minecraft:overworld"),
                        ResourceId.parse("create:creative_motor"), new BlockPos3i(4, 64, 0),
                        OptionalLong.of(4), 1, 16, KineticRotationDirection.NEGATIVE, true, 16, 0, false),
                new KineticSnapshot(
                        102, runtime, ResourceId.parse("minecraft:overworld"),
                        ResourceId.parse("create:creative_motor"), new BlockPos3i(8, 64, 0),
                        OptionalLong.of(8), 2, 0, KineticRotationDirection.STATIONARY, true, 16, 32, true));

        List<KineticSnapshot> replayed = snapshots.stream()
                .map(KineticSnapshotReplayCodec::encode)
                .map(KineticSnapshotReplayCodec::decode)
                .toList();

        assertThat(replayed).extracting(KineticSnapshot::operatingState)
                .containsExactly(
                        KineticOperatingState.STOPPED,
                        KineticOperatingState.POWERED,
                        KineticOperatingState.OVERSTRESSED);
        assertThat(replayed.get(1).signedSpeedRpm()).isEqualTo(-16);
        assertThat(replayed.get(1).toTelemetry().networkId())
                .isEqualTo("minecraft:overworld#4");
        KineticSnapshot netherPeer = new KineticSnapshot(
                101, runtime, ResourceId.parse("minecraft:the_nether"),
                ResourceId.parse("create:creative_motor"), new BlockPos3i(4, 64, 0),
                OptionalLong.of(4), 1, 16, KineticRotationDirection.NEGATIVE, true, 16, 0, false);
        assertThat(netherPeer.toTelemetry().networkId())
                .isNotEqualTo(replayed.get(1).toTelemetry().networkId());
        assertThat(new KineticNetworkAnalyzer().analyze(
                        replayed.stream().map(KineticSnapshot::toTelemetry).toList())
                .findings())
                .extracting(finding -> finding.code())
                .containsExactly(FaultCode.STRESS_OVERLOAD);
    }

    @Test
    void replayRejectsTrailingOrMalformedInput() {
        String encoded = KineticSnapshotReplayCodec.encode(poweredSnapshot(Map.of("create", "6.0.6-150")));

        assertThatThrownBy(() -> KineticSnapshotReplayCodec.decode(encoded + "AA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KineticSnapshotReplayCodec.decode("not-base64!"))
                .isInstanceOf(IllegalArgumentException.class);
        KineticSnapshot paddingCandidate = new KineticSnapshot(
                123,
                runtime(Map.of("create", "6.0.6-150")),
                ResourceId.parse("minecraft:overworld"),
                ResourceId.parse("create:creative_motorx"),
                new BlockPos3i(4, 64, -2),
                OptionalLong.of(99),
                2,
                16,
                KineticRotationDirection.POSITIVE,
                true,
                16,
                0,
                false);
        String unpadded = KineticSnapshotReplayCodec.encode(paddingCandidate);
        int paddingLength = (4 - unpadded.length() % 4) % 4;
        assertThat(paddingLength).isPositive();
        String padded = unpadded + "=".repeat(paddingLength);
        assertThatThrownBy(() -> KineticSnapshotReplayCodec.decode(padded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-canonical");
        RuntimeFingerprint oversizedRuntime = new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                Map.of("create", "6.0.6-150"),
                "x".repeat(1_025),
                2);
        KineticSnapshot oversized = new KineticSnapshot(
                1, oversizedRuntime, ResourceId.parse("minecraft:overworld"), ResourceId.parse("create:shaft"),
                new BlockPos3i(0, 64, 0), OptionalLong.empty(), 0, 0,
                KineticRotationDirection.STATIONARY, true, 0, 0, false);
        assertThatThrownBy(() -> KineticSnapshotReplayCodec.encode(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text field");
    }

    private static KineticSnapshot poweredSnapshot(Map<String, String> versions) {
        return new KineticSnapshot(
                123,
                runtime(versions),
                ResourceId.parse("minecraft:overworld"),
                ResourceId.parse("create:creative_motor"),
                new BlockPos3i(4, 64, -2),
                OptionalLong.of(99),
                2,
                16,
                KineticRotationDirection.POSITIVE,
                true,
                16,
                0,
                false);
    }

    private static RuntimeFingerprint runtime(Map<String, String> versions) {
        return new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                versions,
                "steve_industrial:forge_1_20_1_create_6_0_6",
                2);
    }
}
