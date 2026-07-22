package dev.stevecreate.agent.adapter.api.claim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionEvidenceState;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ClaimAdapterBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-07-17T15:00:00Z");

    @Test
    void isolatedAllowAdapterAllowsOnlyVerifiedIsolatedScope() {
        ClaimAdapter adapter = new IsolatedTestAllowClaimAdapter();

        PermissionEvidence isolated = adapter.query(query(WorldEnvironmentType.ISOLATED_TEST_WORLD));
        PermissionEvidence formal = adapter.query(query(WorldEnvironmentType.FORMAL_PLAYER_WORLD));

        assertThat(isolated.decision()).isEqualTo(PermissionDecision.ALLOWED);
        assertThat(isolated.state()).isEqualTo(PermissionEvidenceState.VERIFIED);
        assertThat(isolated.permitsConstruction()).isTrue();
        assertThat(formal.decision()).isEqualTo(PermissionDecision.DENIED);
        assertThat(formal.permitsConstruction()).isFalse();
    }

    @Test
    void denyUnknownAndNotInstalledRemainDistinctAndFailClosed() {
        PermissionQuery formal = query(WorldEnvironmentType.FORMAL_PLAYER_WORLD);

        assertThat(new ExplicitDenyClaimAdapter().query(formal).decision())
                .isEqualTo(PermissionDecision.DENIED);
        assertThat(new UnknownClaimAdapter().query(formal).decision())
                .isEqualTo(PermissionDecision.UNKNOWN);
        assertThat(new NotInstalledClaimAdapter().query(formal).decision())
                .isEqualTo(PermissionDecision.NOT_INSTALLED);
        assertThat(new ExplicitDenyClaimAdapter().query(formal).permitsConstruction()).isFalse();
        assertThat(new UnknownClaimAdapter().query(formal).permitsConstruction()).isFalse();
        assertThat(new NotInstalledClaimAdapter().query(formal).permitsConstruction()).isFalse();
    }

    @Test
    void fakeThirdPartyJvmAdapterProvesBoundaryIsNotHardcoded() {
        ClaimAdapter adapter = new FakeThirdPartyClaimAdapter();
        PermissionQuery query = query(WorldEnvironmentType.ISOLATED_TEST_WORLD);

        PermissionEvidence evidence = adapter.query(query);

        assertThat(adapter.adapterId()).isEqualTo(id("thirdparty:claims-fixture"));
        assertThat(evidence.adapterId()).isEqualTo(adapter.adapterId());
        assertThat(evidence.query()).isSameAs(query);
        assertThat(evidence.decision()).isEqualTo(PermissionDecision.ALLOWED);
        assertThat(evidence.permitsConstruction()).isTrue();
    }

    @Test
    void queryAndEvidenceCarryEveryRequiredScopeAndProvenanceField() {
        PermissionQuery query = query(WorldEnvironmentType.ISOLATED_TEST_WORLD);
        PermissionEvidence evidence = new FakeThirdPartyClaimAdapter().query(query);

        assertThat(query.operation()).isEqualTo(RegionAuthorizedOperation.PLACE_BLOCK);
        assertThat(query.actorIdentity()).isEqualTo("actor:fixture");
        assertThat(query.region()).isEqualTo(box());
        assertThat(query.worldIdentity()).isEqualTo("world:fixture");
        assertThat(query.source()).isEqualTo("fixture:request");
        assertThat(query.requestedAt()).isEqualTo(NOW);
        assertThat(query.generation()).isEqualTo(9);
        assertThat(query.fingerprint()).isEqualTo("permission-scope:fingerprint");
        assertThat(evidence.source()).isEqualTo("thirdparty:jvm-fixture");
        assertThat(evidence.observedAt()).isEqualTo(NOW);
        assertThat(evidence.observedGeneration()).isEqualTo(9);
        assertThat(evidence.fingerprint()).isEqualTo("permission-scope:fingerprint");
        assertThat(evidence.provenance()).isEqualTo("test-only fake third-party adapter");
    }

    @Test
    void decisionVocabularyIsExactAndFormalAllowedClaimStillCannotPermitConstruction() {
        assertThat(Arrays.stream(PermissionDecision.values()).map(Enum::name))
                .containsExactly("UNKNOWN", "ALLOWED", "DENIED", "NOT_INSTALLED");
        PermissionQuery formal = query(WorldEnvironmentType.FORMAL_PLAYER_WORLD);
        PermissionEvidence claimedAllowed = new PermissionEvidence(
                formal, PermissionDecision.ALLOWED, id("thirdparty:untrusted"),
                PermissionEvidenceState.VERIFIED, "untrusted:source", NOW, 9,
                "permission-scope:fingerprint", "supplied claim only");

        assertThat(claimedAllowed.permitsConstruction()).isFalse();
    }

    private static PermissionQuery query(WorldEnvironmentType environment) {
        return new PermissionQuery(
                "permission-query-1", RegionAuthorizedOperation.PLACE_BLOCK, "actor:fixture",
                box(), "world:fixture", environment, id("minecraft:overworld"),
                "fixture:request", NOW, 9, "permission-scope:fingerprint");
    }

    private static DeploymentBoundingBox box() {
        return new DeploymentBoundingBox(
                new BlockPos3i(1, 2, 3), new BlockPos3i(4, 5, 6));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static final class FakeThirdPartyClaimAdapter implements ClaimAdapter {
        @Override
        public ResourceId adapterId() { return id("thirdparty:claims-fixture"); }

        @Override
        public PermissionEvidence query(PermissionQuery query) {
            return new PermissionEvidence(
                    query, PermissionDecision.ALLOWED, adapterId(),
                    PermissionEvidenceState.VERIFIED, "thirdparty:jvm-fixture",
                    query.requestedAt(), query.generation(), query.fingerprint(),
                    "test-only fake third-party adapter");
        }
    }
}
