package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IndustrialModAdapterContractTest {
    private static final RuntimeFingerprint RUNTIME = new RuntimeFingerprint(
            "1.20.1", "forge", "47.4.10", Map.of("create", "6.0.6"),
            "steve_industrial:test", 1);

    @Test
    void specializedAdaptersKeepTheirActualTargetModIdentity() {
        assertThat(new FakeCreateAdapter().targetModId()).isEqualTo("create");
        assertThat(new FakeMekanismAdapter().targetModId()).isEqualTo("mekanism");
    }

    @Test
    void absentOptionalModIsAnExplicitFailureRatherThanAnException() {
        AdapterResult<WorldSnapshot> result = new FakeMekanismAdapter()
                .capture(new ScanRequest(new BlockPos3i(0, 64, 0), 4));
        assertThat(result).isEqualTo(new AdapterResult.Failure<WorldSnapshot>(
                AdapterFailureCode.UNSUPPORTED_RUNTIME, "mekanism absent"));
    }

    @Test
    void successfulCaptureReturnsAnImmutableSnapshot() {
        AdapterResult<WorldSnapshot> result = new FakeCreateAdapter()
                .capture(new ScanRequest(new BlockPos3i(0, 64, 0), 4));
        assertThat(result).isInstanceOf(AdapterResult.Success.class);
        WorldSnapshot snapshot = (WorldSnapshot) ((AdapterResult.Success<?>) result).value();
        assertThat(snapshot.components()).hasSize(1);
    }

    private static final class FakeCreateAdapter implements CreateVersionAdapter {
        @Override
        public ResourceId adapterId() {
            return ResourceId.parse("steve_industrial:test_create");
        }

        @Override
        public RuntimeFingerprint runtime() {
            return RUNTIME;
        }

        @Override
        public AdapterResult<WorldSnapshot> capture(ScanRequest request) {
            ObservedComponent component = new ObservedComponent(
                    ResourceId.parse("create:shaft"), request.center(), Map.of("axis", "x"));
            return new AdapterResult.Success<>(new WorldSnapshot(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    100L, RUNTIME, request.center(), request.radius(), List.of(component)));
        }

        @Override
        public AdapterResult<KineticSnapshot> captureKinetics(KineticCaptureRequest request) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.CREATE_API_FAILURE, "not implemented by contract fake");
        }
    }

    private static final class FakeMekanismAdapter implements MekanismVersionAdapter {
        @Override
        public ResourceId adapterId() {
            return ResourceId.parse("steve_industrial:test_mekanism");
        }

        @Override
        public RuntimeFingerprint runtime() {
            return RUNTIME;
        }

        @Override
        public AdapterResult<WorldSnapshot> capture(ScanRequest request) {
            return new AdapterResult.Failure<>(AdapterFailureCode.UNSUPPORTED_RUNTIME, "mekanism absent");
        }
    }
}
